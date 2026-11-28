import Foundation

/// The control panel's feature switches (protocol §5.4). The Android phone is the source of truth, like the
/// block / mute lists: a client sends its whole map with `features.set`, the phone answers every client with
/// `features.state`, and a switch flipped on any phone applies on all of them.
///
/// Every feature is OFF by default (the owner's explicit choice). A feature that is off is not shown in the
/// apps and does not run. Core telephony (bridged calls, SMS, contacts) is the base product and is never a
/// switch. Stage 3's features (file transfer, Live Activity, demo mode) are already named here; a later build
/// only has to add them to `available` — no protocol or panel rework.
public enum Feature {
    public static let internet = "internet"
    public static let ussd = "ussd"
    public static let find = "find"
    public static let ringer = "ringer"
    public static let files = "files"
    public static let liveActivity = "liveActivity"
    public static let demo = "demo"

    /// Every switch the panel knows, in panel order.
    public static let all = [internet, ussd, find, ringer, files, liveActivity, demo]
    /// The switches whose feature exists in this build; the panels show only these.
    public static let available: Set<String> = [internet, ussd, find, ringer]
}

/// One side's copy of the panel state: a value per feature id (absent = off) plus a revision that grows
/// with every change the side makes or adopts. Unknown ids are kept, so a newer phone's switches survive a
/// round trip through an older one. Mirrors the Kotlin `FeatureSettings` in android/core.
public struct FeatureSettings: Codable, Equatable, Sendable {
    public var values: [String: Bool]
    public var rev: Int64

    public init(values: [String: Bool] = [:], rev: Int64 = 0) {
        self.values = values; self.rev = rev
    }

    public static let `default` = FeatureSettings()

    public func isOn(_ id: String) -> Bool { values[id] == true }

    /// This side flips a switch: the value changes and the revision moves past everything seen so far.
    public func with(_ id: String, _ on: Bool) -> FeatureSettings {
        var v = values; v[id] = on
        return FeatureSettings(values: v, rev: rev + 1)
    }

    /// Adopt a state received from the other side. A newer revision wins whole. An older revision loses
    /// entirely (the holder of the newer one re-sends it, §5.4, so an offline flip still lands through
    /// that path). At the same revision, the one switch the sender says it just flipped (`changedId`) is
    /// still taken: two phones flipping two different switches at the same time must not lose either flip,
    /// so that single value is taken and the revision moves past both.
    public func adopt(_ incoming: FeatureSettings, changedId: String? = nil) -> FeatureSettings {
        if incoming.rev > rev { return incoming }
        if incoming.rev == rev, let id = changedId, incoming.isOn(id) != isOn(id) {
            var v = values; v[id] = incoming.isOn(id)
            return FeatureSettings(values: v, rev: rev + 1)
        }
        return self
    }

    public var body: FeaturesBody { FeaturesBody(features: values, rev: rev) }

    public init(_ body: FeaturesBody) {
        self.init(values: body.features, rev: body.rev)
    }
}
