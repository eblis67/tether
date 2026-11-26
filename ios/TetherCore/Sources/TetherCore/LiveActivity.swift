import Foundation
#if canImport(ActivityKit)
import ActivityKit
#endif

/// What the iOS Live Activity draws (docs/LIVE-ACTIVITY.md): the Android phone's battery, SIMs and
/// current bridged call, flattened for display. This value is the contract between the app process
/// (which builds it from a `StatusBody`) and the widget extension process (which renders it), so it
/// is plain Codable data — no behaviour crosses, and the tests pin the JSON round-trip.
public struct PhoneLiveState: Codable, Hashable, Equatable, Sendable {
    public struct Sim: Codable, Hashable, Equatable, Sendable {
        public var name: String
        public var carrier: String
        public var slot: Int
        /// Signal bars, clamped to the 0…4 the UI draws.
        public var signal: Int
        /// Network label as phones print it ("5G", "LTE+", …); nil: unknown.
        public var net: String?
        /// "in" (or nil) is service; "none", "emergency", "off" are not.
        public var service: String?
        public var roaming: Bool
        /// The phone's default (data) SIM: the one the compact views feature.
        public var isDefault: Bool

        public var hasService: Bool { service == nil || service == "in" }

        public init(name: String, carrier: String, slot: Int, signal: Int, net: String?,
                    service: String?, roaming: Bool, isDefault: Bool) {
            self.name = name; self.carrier = carrier; self.slot = slot; self.signal = signal
            self.net = net; self.service = service; self.roaming = roaming; self.isDefault = isDefault
        }
    }

    public struct Call: Codable, Hashable, Equatable, Sendable {
        /// Who the call is with, already resolved to a contact name where the app knows one.
        public var label: String
        public var incoming: Bool
        public var held: Bool
        /// The call's audio is on another client: it is answered / held on the Android phone itself
        /// or the other device, not on this iPhone.
        public var elsewhere: Bool
        /// The call is connected (its timer runs), as opposed to ringing / dialing.
        public var connected: Bool
        /// When the call connected, on *our* clock (`CallClock`), for the elapsed-time view.
        public var since: Date?

        public init(label: String, incoming: Bool, held: Bool, elsewhere: Bool, connected: Bool, since: Date?) {
            self.label = label; self.incoming = incoming; self.held = held
            self.elsewhere = elsewhere; self.connected = connected; self.since = since
        }
    }

    /// The Android phone's name (what the user paired it as).
    public var device: String
    /// Battery percent, clamped to 0…100.
    public var battery: Int
    public var charging: Bool
    public var sims: [Sim]
    /// The link is up. The final state before dismissal carries `false` with the last known facts.
    public var linked: Bool
    public var call: Call?

    public init(device: String, battery: Int, charging: Bool, sims: [Sim], linked: Bool, call: Call? = nil) {
        self.device = device; self.battery = battery; self.charging = charging
        self.sims = sims; self.linked = linked; self.call = call
    }

    /// The SIM the compact views feature: the default one, else the first.
    public var featuredSim: Sim? { sims.first { $0.isDefault } ?? sims.first }

    /// A copy with only the link flag changed (the "out of reach" final state).
    public func withLinked(_ linked: Bool) -> PhoneLiveState {
        var s = self
        s.linked = linked
        return s
    }

    /// The display state for one status from the Android phone. `callLabel` lets the app resolve the
    /// call's number to a contact name (its index lives app-side); `peerOffsetMs` is the link's clock
    /// sample (`TetherLink.peerClockOffsetMs`), so the call's start lands on our clock.
    public static func make(status: StatusBody, deviceName: String, linked: Bool,
                            call: CallInfo? = nil, callLabel: String? = nil,
                            peerOffsetMs: Int64? = nil) -> PhoneLiveState {
        let sims = status.sims.map { sim in
            Sim(name: sim.name, carrier: sim.carrier, slot: sim.slot,
                signal: min(max(sim.signal, 0), 4), net: sim.net, service: sim.service,
                roaming: sim.roaming == true, isDefault: status.defaultSub == sim.sub)
        }
        var callState: Call? = nil
        if let call, !call.isEnded {
            callState = Call(label: callLabel ?? call.name ?? call.num,
                             incoming: call.isIncoming,
                             held: call.hold || call.state == "holding",
                             elsewhere: call.isElsewhere,
                             connected: call.state == "active" || call.state == "holding",
                             since: CallClock.connectedAt(sinceMs: call.since, peerOffsetMs: peerOffsetMs))
        }
        return PhoneLiveState(device: deviceName, battery: min(max(status.battery, 0), 100),
                              charging: status.charging, sims: sims, linked: linked, call: callState)
    }

    /// Which of the phone's calls the activity features: one whose audio is here before one
    /// elsewhere, then active before holding, connecting, dialing, ringing. Ended calls never
    /// feature; ties break on the call id so the choice is stable.
    public static func pickCall(from calls: [CallInfo]) -> CallInfo? {
        func rank(_ c: CallInfo) -> (Int, Int, String) {
            let state: Int
            switch c.state {
            case "active": state = 0
            case "holding": state = 1
            case "connecting": state = 2
            case "dialing": state = 3
            case "ringing": state = 4
            default: state = 5
            }
            return (c.isElsewhere ? 1 : 0, state, c.cid)
        }
        return calls.filter { !$0.isEnded }.min { rank($0) < rank($1) }
    }
}

/// The Live Activity lifecycle as a pure decision (docs/LIVE-ACTIVITY.md), so it is testable without
/// ActivityKit: the app feeds it the setting, the pairing, the demo, the link and the states, and
/// executes the answer.
public enum LiveActivityPolicy {
    public enum Action: Equatable {
        case none
        case start(PhoneLiveState)
        case update(PhoneLiveState)
        /// End the activity. `final` is the last state to show while it dismisses (nil: keep whatever
        /// is on screen); `immediate` dismisses at once instead of lingering briefly.
        case end(final: PhoneLiveState?, immediate: Bool)
    }

    public static func decide(enabled: Bool, paired: Bool, demo: Bool, connected: Bool,
                              current: PhoneLiveState?, next: PhoneLiveState?) -> Action {
        // Off, unpaired or in the demo there is nothing on the Lock Screen — and anything left from
        // before goes at once (the demo must never leave even the real state behind, let alone show
        // the sample there).
        guard enabled, paired, !demo else {
            return current == nil ? .none : .end(final: nil, immediate: true)
        }
        if connected {
            // No status yet: wait for the first one rather than start an empty shell.
            guard let next else { return .none }
            guard let current else { return .start(next) }
            return current == next ? .none : .update(next)
        }
        // Link down: one honest final state ("out of reach", last known facts), dismissed shortly
        // after by the system; a fresh activity starts if the link returns.
        guard let current, current.linked else { return .none }
        return .end(final: current.withLinked(false), immediate: false)
    }
}

#if canImport(ActivityKit)
/// The activity's attributes: no per-activity data of their own (there is one activity per paired
/// phone, and everything that changes is the content state). Defined here, in the core both targets
/// link, so the app and the widget extension can never disagree about the type.
@available(iOS 16.1, *)
public struct PhoneActivityAttributes: ActivityAttributes {
    public typealias ContentState = PhoneLiveState
    public init() {}
}
#endif
