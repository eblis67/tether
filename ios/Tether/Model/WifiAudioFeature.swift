import Foundation
import Network
@_spi(TetherApp) import TetherCore

/// The app host of `x.tether.wifiaudio` (§7.11, docs/WIFI-CALL-PATH.md): while a call's audio runs, it offers
/// the Android phone a TCP channel on the local network and moves the call's audio frames onto it when the
/// phone accepts. Bluetooth stays the control path and the fallback: the send decision is per frame
/// (`WifiAudioPolicy.sendPath` over the channel's freshness), so a Wi-Fi path that goes quiet mid-call simply
/// stops being chosen — nothing has to migrate or be renegotiated for the call to survive.
///
/// The channel's frames are §4 audio payloads verbatim: downlink frames go straight to `CallAudio.incoming`
/// (the same entry the link's frames use), and uplink frames reach the channel through `CallAudio`'s Wi-Fi
/// sink, which is consulted per captured frame before the link. All state lives on the main actor except the
/// sink's channel reference, which the audio thread reads under the core's unfair lock.
@MainActor
final class WifiAudioFeature {
    /// The Link tab's switch (on unless switched off); read live, so a change applies to the running call.
    static let enabledKey = "wifiAudioEnabled"

    private unowned let model: AppModel
    private let work = DispatchQueue(label: "tether.wifiaudio", qos: .userInitiated)

    private var channel: WifiAudioChannel?
    private var inCall = false
    private var attempts = 0
    private var lastAttemptMs: Int64?
    private var lastPingMs: Int64?
    private var timer: Timer?

    /// The channel the audio thread may send on: written here, read there, behind the core's unfair lock.
    private let sink = SinkBox()
    private final class SinkBox: @unchecked Sendable {
        private let lock = UnfairLock()
        private var current: WifiAudioChannel?
        var channel: WifiAudioChannel? {
            get { lock.withLock { current } }
            set { lock.withLock { current = newValue } }
        }
    }

    init(model: AppModel) { self.model = model }

    private var ext: WifiAudioExtension? { model.wifiAudioExt }
    private var enabled: Bool { UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? true }
    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Sets the extension's callbacks (called once, from `AppModel.registerExtensions`). They arrive on the
    /// link thread; everything here hops to the main actor before touching state.
    func wire(_ ext: WifiAudioExtension) {
        ext.onAccepted = { [weak self] _, accept, keys in
            Task { @MainActor [weak self] in self?.accepted(accept, keys) }
        }
        ext.onDeclined = { [weak self] _, _, why in
            Task { @MainActor [weak self] in self?.declined(why) }
        }
        ext.onDown = { [weak self] _, _, _ in
            Task { @MainActor [weak self] in self?.peerDown() }
        }
        ext.onError = { [weak self] _, _, code in
            Task { @MainActor [weak self] in self?.failed(code) }
        }
    }

    // MARK: call lifecycle (from AppModel's audio.start / audio.stop and link changes)

    /// Call audio started: begin offering (the policy decides when an offer actually goes out).
    func callAudioStarted() {
        inCall = true
        attempts = 0
        lastAttemptMs = nil
        model.wifiAudioInCall = true
        startTimer()
        evaluate()
    }

    /// Call audio stopped: the channel belongs to the call, so it ends with it — politely, over the session.
    func callAudioEnded() {
        inCall = false
        model.wifiAudioInCall = false
        stopTimer()
        if let ext, ext.channelOpen, let session = model.extensions.session(for: ext) {
            ext.sendDown(session, why: "ended")
            ext.channelEnded()
        }
        teardownChannel(notify: true)
        publishPath()
    }

    /// The link dropped: the session (and with it the extension's state) is gone; just drop the channel.
    func linkDown() {
        inCall = false
        model.wifiAudioInCall = false
        stopTimer()
        teardownChannel(notify: false)
        publishPath()
    }

    /// The Link tab's switch changed: off ends a running channel (the call stays on Bluetooth); on re-evaluates.
    func settingChanged() {
        if !enabled {
            if let ext, ext.channelOpen, let session = model.extensions.session(for: ext) {
                ext.sendDown(session, why: nil)
                ext.channelEnded()
            }
            teardownChannel(notify: true)
            publishPath()
        } else {
            evaluate()
        }
    }

    // MARK: the uplink sink (called from CallAudio's capture path, off the main thread)

    /// Offers one captured frame to the Wi-Fi channel; true when it went that way. The per-frame policy
    /// decision: Wi-Fi only while the channel is up and has been heard from within the freshness window.
    nonisolated func sendUplink(_ frame: AudioFrame) -> Bool {
        guard let channel = sink.channel else { return false }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard WifiAudioPolicy.sendPath(channelUp: channel.isUp, lastHeardMs: channel.lastHeardMs, nowMs: now) == .wifi
        else { return false }
        return channel.sendAudio(frame.encoded())
    }

    // MARK: negotiation outcomes

    private func accepted(_ accept: WifiAudioExtension.AcceptBody, _ keys: WifiAudioKeys.ChannelKeys) {
        guard inCall, channel == nil, let port = NWEndpoint.Port(rawValue: UInt16(clamping: accept.port)) else { return }
        let conn = NWConnection(host: NWEndpoint.Host(accept.host), port: port, using: .tcp)
        let stream = NWFilesStream(conn)
        let channel = WifiAudioChannel(keys: keys, cid: accept.cid, initiator: true)
        self.channel = channel
        sink.channel = channel
        lastPingMs = nil
        work.async { [weak self, stream, channel] in
            guard stream.open(timeout: 10) else {
                Task { @MainActor [weak self] in self?.channelGone(channel) }
                return
            }
            channel.run(stream, onFrame: { payload in
                // The same entry the link's audio frames use; it orders and conceals by sequence number.
                if let frame = AudioFrame.parse(payload) { CallAudio.incoming(frame) }
            }, onEnd: { [weak self] _ in
                Task { @MainActor [weak self] in self?.channelGone(channel) }
            })
        }
        publishPath()
    }

    private func declined(_ why: String?) {
        LinkJournal.shared.add("Wi-Fi call path declined\(why.map { " (\($0))" } ?? "") — the call stays on Bluetooth")
        publishPath()
    }

    private func peerDown() {
        ext?.channelEnded()
        teardownChannel(notify: false)
        publishPath()
    }

    private func failed(_ code: String) {
        LinkJournal.shared.add("Wi-Fi call path failed (\(code)) — the call stays on Bluetooth")
        ext?.channelEnded()
        teardownChannel(notify: false)
        publishPath()
    }

    /// The channel ended (or never connected): forget it; the timer's policy decides about another offer.
    private func channelGone(_ gone: WifiAudioChannel) {
        guard channel === gone else { return }
        ext?.channelEnded()
        teardownChannel(notify: false)
        publishPath()
    }

    // MARK: the per-second policy loop

    private func startTimer() {
        stopTimer()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.evaluate() }
        }
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    /// One policy tick: offer when the policy says to, ping when due, bury a channel that has gone dead.
    private func evaluate() {
        guard inCall else { return }
        let now = nowMs()
        if let channel, WifiAudioPolicy.channelDead(lastHeardMs: channel.lastHeardMs, nowMs: now) {
            LinkJournal.shared.add("Wi-Fi call path went quiet — the call is on Bluetooth")
            if let ext, let session = model.extensions.session(for: ext) {
                ext.sendDown(session, why: nil)
                ext.channelEnded()
            }
            teardownChannel(notify: true)
        }
        if let channel {
            if WifiAudioPolicy.pingDue(lastPingMs: lastPingMs, nowMs: now), channel.sendPing() {
                lastPingMs = now
            }
        } else if let ext, let session = model.extensions.session(for: ext),
                  WifiAudioPolicy.shouldOffer(enabled: enabled, inCall: !model.demoActive,
                                              hasNetwork: localIPv4Address() != nil, channelBusy: ext.channelOpen,
                                              attemptsThisCall: attempts, lastAttemptMs: lastAttemptMs, nowMs: now) {
            attempts += 1
            lastAttemptMs = now
            _ = ext.offer(session)
        }
        publishPath()
    }

    // MARK: state

    private func teardownChannel(notify: Bool) {
        channel?.close(notify: notify)
        channel = nil
        sink.channel = nil
        lastPingMs = nil
    }

    /// The path the UI reports: what the per-frame decision would choose right now.
    private func publishPath() {
        guard let channel else {
            model.wifiAudioPath = .bluetooth
            return
        }
        model.wifiAudioPath = WifiAudioPolicy.sendPath(channelUp: channel.isUp, lastHeardMs: channel.lastHeardMs, nowMs: nowMs())
    }
}
