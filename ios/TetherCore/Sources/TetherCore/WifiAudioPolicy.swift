import Foundation

/// Which path the next call-audio frame takes (protocol §7.11): the home Wi-Fi, or Bluetooth as always.
public enum WifiAudioPath: String, Equatable, Sendable {
    case bluetooth, wifi
}

/// The Wi-Fi call path's decisions (protocol §7.11, docs/WIFI-CALL-PATH.md), pure so the app and the tests
/// drive the same rules: when the client offers a channel, which path a frame takes, when a silent channel
/// is dead, and why the phone declines an offer. Mirrored by the Kotlin `WifiAudioPolicy`; the constants are
/// part of the protocol (§7.11) and must not drift apart.
public enum WifiAudioPolicy {
    /// The client pings the channel this often; any inbound frame also counts as being heard.
    public static let pingIntervalMs: Int64 = 2_000
    /// Heard nothing for this long: stop sending on Wi-Fi (frames go over Bluetooth again).
    public static let heardTimeoutMs: Int64 = 6_000
    /// Heard nothing for this long: the channel is dead, close it.
    public static let deadTimeoutMs: Int64 = 15_000
    /// After a failed or declined attempt, wait this long before offering again.
    public static let retryDelayMs: Int64 = 15_000
    /// Offers per call at most: an isolated AP must not cost an attempt every 15 s of every call.
    public static let maxAttemptsPerCall = 4
    /// How long the phone listens for the connection its accept promised.
    public static let acceptWindowMs: Int64 = 15_000

    /// The client offers a channel now? `channelBusy`: a channel is up, being connected, or an offer is
    /// still unanswered. `attemptsThisCall` and `lastAttemptMs` are the client's own bookkeeping per call.
    public static func shouldOffer(enabled: Bool, inCall: Bool, hasNetwork: Bool, channelBusy: Bool,
                                   attemptsThisCall: Int, lastAttemptMs: Int64?, nowMs: Int64) -> Bool {
        guard enabled, inCall, hasNetwork, !channelBusy else { return false }
        guard attemptsThisCall < maxAttemptsPerCall else { return false }
        if let last = lastAttemptMs, nowMs - last < retryDelayMs { return false }
        return true
    }

    /// The path the next audio frame takes: Wi-Fi only while the channel is up and the peer was heard
    /// from in time; Bluetooth otherwise (and always while there is no channel).
    public static func sendPath(channelUp: Bool, lastHeardMs: Int64?, nowMs: Int64) -> WifiAudioPath {
        guard channelUp, let last = lastHeardMs, nowMs - last <= heardTimeoutMs else { return .bluetooth }
        return .wifi
    }

    /// Nothing heard for `deadTimeoutMs` (or ever): close the channel. The call itself is unaffected —
    /// its frames have been going over Bluetooth since `heardTimeoutMs`.
    public static func channelDead(lastHeardMs: Int64?, nowMs: Int64) -> Bool {
        guard let last = lastHeardMs else { return true }
        return nowMs - last > deadTimeoutMs
    }

    /// A PING is due (the client sends them; the phone only answers).
    public static func pingDue(lastPingMs: Int64?, nowMs: Int64) -> Bool {
        guard let last = lastPingMs else { return true }
        return nowMs - last >= pingIntervalMs
    }

    /// The phone's answer to an offer: nil accepts it, otherwise the decline's `why` (§7.11). A test
    /// (`dry`) link never opens a real socket; an open channel makes a second offer `busy`; without a
    /// bridged call to carry or a local address to listen on there is nothing to accept for.
    public static func declineReason(dry: Bool, channelOpen: Bool, bridging: Bool, hasAddress: Bool) -> String? {
        if dry { return "dry" }
        if channelOpen { return "busy" }
        if !bridging || !hasAddress { return "unavailable" }
        return nil
    }
}
