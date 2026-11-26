import ActivityKit
import Foundation
import TetherCore

/// The Live Activity's app-side half (docs/LIVE-ACTIVITY.md): `LiveActivityPolicy` in TetherCore
/// decides what should be on the Lock Screen, this executes it against ActivityKit. Safe to call on
/// any iOS version — Live Activities begin at 16.1, and before that everything here is a no-op.
@MainActor
final class LiveActivityController {
    /// UserDefaults key of the Link tab's "Live Activity" switch (on unless switched off).
    static let enabledKey = "liveActivity"
    static var enabled: Bool { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true }

    /// Re-decide from the model's current state. Called whenever an input changes: the link, a new
    /// status, a call, entering or leaving the demo, the switch itself.
    func refresh(from model: AppModel) {
        guard #available(iOS 16.1, *) else { return }
        LiveActivityDriver.shared.refresh(from: model)
    }

    /// End any running activity at once (the pairing changed: whatever is on the Lock Screen
    /// belongs to the previous phone).
    func endNow() {
        guard #available(iOS 16.1, *) else { return }
        LiveActivityDriver.shared.endNow()
    }
}

@available(iOS 16.1, *)
@MainActor
private final class LiveActivityDriver {
    static let shared = LiveActivityDriver()

    private var activity: Activity<PhoneActivityAttributes>?
    /// The state last pushed (or adopted from a previous process): the policy's `current`.
    private var current: PhoneLiveState?

    func refresh(from model: AppModel) {
        adoptIfNeeded()
        let next: PhoneLiveState?
        if model.linkState.isConnected, !model.demoActive, let status = model.status {
            let call = PhoneLiveState.pickCall(from: Array(model.activeCalls.values))
            next = PhoneLiveState.make(status: status, deviceName: model.peerName, linked: true,
                                       call: call,
                                       callLabel: call.map { model.displayName($0.num) },
                                       peerOffsetMs: model.link.peerClockOffsetMs)
        } else {
            next = nil
        }
        switch LiveActivityPolicy.decide(enabled: LiveActivityController.enabled, paired: model.isPaired,
                                         demo: model.demoActive, connected: model.linkState.isConnected,
                                         current: current, next: next) {
        case .none:
            break
        case .start(let state):
            start(state)
        case .update(let state):
            update(state)
        case .end(let final, let immediate):
            end(final: final, immediate: immediate)
        }
    }

    func endNow() {
        guard activity != nil else { return }
        end(final: nil, immediate: true)
    }

    /// A previous process may have left an activity running (iOS relaunches the app for Bluetooth
    /// events): take it over instead of starting a second one. One that already ended is dropped.
    private func adoptIfNeeded() {
        if let activity {
            if activity.activityState == .ended || activity.activityState == .dismissed {
                self.activity = nil
                current = nil
            }
            return
        }
        guard let existing = Activity<PhoneActivityAttributes>.activities
            .first(where: { $0.activityState != .ended && $0.activityState != .dismissed }) else { return }
        activity = existing
        current = state(of: existing)
    }

    private func start(_ state: PhoneLiveState) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        do {
            activity = try request(state)
            current = state
        } catch {
            // Live Activities off for this app in iOS Settings, or the system refused: stay quiet —
            // the next status change retries, and nothing in the app depends on this.
            NSLog("[live] start failed: %@", "\(error)")
        }
    }

    private func update(_ state: PhoneLiveState) {
        guard let activity else { return }
        current = state
        Task { await update(activity, with: state) }
    }

    private func end(final: PhoneLiveState?, immediate: Bool) {
        guard let activity else { current = nil; return }
        self.activity = nil
        current = nil
        let policy: ActivityUIDismissalPolicy = immediate ? .immediate : .after(Date().addingTimeInterval(5 * 60))
        Task { await end(activity, final: final, dismissalPolicy: policy) }
    }

    // MARK: ActivityKit, 16.1 and 16.2 shapes

    /// An hour of silence marks the activity stale: if this app dies and the phone later dies too,
    /// the Lock Screen must not show an old battery as fresh. Any real change refreshes it first.
    private func staleDate() -> Date { Date().addingTimeInterval(60 * 60) }

    private func state(of activity: Activity<PhoneActivityAttributes>) -> PhoneLiveState {
        if #available(iOS 16.2, *) { return activity.content.state }
        return activity.contentState
    }

    private func request(_ state: PhoneLiveState) throws -> Activity<PhoneActivityAttributes> {
        if #available(iOS 16.2, *) {
            return try Activity.request(attributes: PhoneActivityAttributes(),
                                        content: ActivityContent(state: state, staleDate: staleDate()),
                                        pushType: nil)
        }
        return try Activity.request(attributes: PhoneActivityAttributes(), contentState: state, pushType: nil)
    }

    private func update(_ activity: Activity<PhoneActivityAttributes>, with state: PhoneLiveState) async {
        if #available(iOS 16.2, *) {
            await activity.update(ActivityContent(state: state, staleDate: staleDate()))
        } else {
            await activity.update(using: state)
        }
    }

    private func end(_ activity: Activity<PhoneActivityAttributes>, final: PhoneLiveState?,
                     dismissalPolicy: ActivityUIDismissalPolicy) async {
        if #available(iOS 16.2, *) {
            await activity.end(final.map { ActivityContent(state: $0, staleDate: nil) },
                               dismissalPolicy: dismissalPolicy)
        } else {
            await activity.end(using: final, dismissalPolicy: dismissalPolicy)
        }
    }
}
