#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation

/// `x.tether.wifiaudio` (protocol §7.11): a Wi-Fi path for call audio. Only the audio frames move — the
/// §4 audio channel's payloads travel over a short-lived TCP connection on the local network instead of
/// over Bluetooth — while signalling, pairing and the wake stay in the sealed session exactly as before.
/// The negotiation mirrors `x.tether.files` (§7.10): the client offers with a fresh salt, the Android
/// phone accepts with where it listens and its own salt, and both derive one-time keys from the session's
/// transcript hash, so the channel is bound to the paired session and the link keys are never reused.
///
/// This is the counterpart of the Kotlin `WifiAudioExtension`: same bodies, same state machine. The
/// socket engine is `WifiAudioChannel` below (the app owns the sockets, as for files).
public final class WifiAudioExtension: TetherExtension {
    // MARK: bodies (the same shapes the Kotlin codec builds)

    public struct OfferBody: Codable, Equatable, Sendable {
        public var cid: String; public var key1: String
        public init(cid: String, key1: String) { self.cid = cid; self.key1 = key1 }
    }
    /// A complete offer. `key1` is the client's raw key salt.
    public struct Offer: Sendable {
        public var cid: String; public var key1: Data
        public init(cid: String, key1: Data) { self.cid = cid; self.key1 = key1 }
    }
    public struct AcceptBody: Codable, Equatable, Sendable {
        public var cid: String; public var host: String; public var port: Int; public var key2: String
        public init(cid: String, host: String, port: Int, key2: String) {
            self.cid = cid; self.host = host; self.port = port; self.key2 = key2
        }
    }
    public struct DeclineBody: Codable, Equatable, Sendable {
        public var cid: String; public var why: String
        public init(cid: String, why: String) { self.cid = cid; self.why = why }
    }
    public struct DownBody: Codable, Equatable, Sendable {
        public var cid: String; public var why: String?
        public init(cid: String, why: String? = nil) { self.cid = cid; self.why = why }
    }
    public struct FailBody: Codable, Equatable, Sendable {
        public var cid: String; public var code: String; public var msg: String?
        public init(cid: String, code: String, msg: String? = nil) { self.cid = cid; self.code = code; self.msg = msg }
    }

    public static let acceptType = Ext.wifiAudio + ".accept"
    public static let declineType = Ext.wifiAudio + ".decline"
    public static let downType = Ext.wifiAudio + ".down"
    public static let errorType = Ext.wifiAudio + ".error"

    public let id = Ext.wifiAudio
    public var types: Set<String> { [id, Self.acceptType, Self.declineType, Self.downType, Self.errorType] }

    /// The session's transcript hash (§3), from the host; nil means this side cannot offer or accept (§7.11: `key`).
    private let keyMaterial: () -> Data?
    public init(keyMaterial: @escaping () -> Data?) { self.keyMaterial = keyMaterial }

    /// An offer arrived (the phone side); answer it with `accept` or `decline`.
    public var onOffer: ((ExtensionSession, Offer, ExtensionMessage) -> Void)?
    /// The phone accepted our offer: connect to `host`:`port` and run a `WifiAudioChannel` with these keys.
    public var onAccepted: ((ExtensionSession, AcceptBody, WifiAudioKeys.ChannelKeys) -> Void)?
    /// The phone declined our offer (or refused it with an `error`, §7.4): `why` / the error's code.
    public var onDeclined: ((ExtensionSession, String, String?) -> Void)?
    /// The peer closed the channel (`x.tether.wifiaudio.down`).
    public var onDown: ((ExtensionSession, String, String?) -> Void)?
    /// The channel failed outside an answer: `code` is one of §7.11's.
    public var onError: ((ExtensionSession, String?, String) -> Void)?

    private struct Outgoing { var cid: String; var key1: Data; var requestId: Int? }
    private var outgoing: Outgoing?
    /// The channel this side believes is open (offered and accepted, or accepted from the peer), if any.
    private var channelCid: String?

    /// Whether a channel is currently open on this link (the host's policy input: don't offer twice).
    public var channelOpen: Bool { channelCid != nil }

    // MARK: client side (offering)

    /// Offers a Wi-Fi audio channel to the Android phone; returns the channel id, or nil when it could not
    /// be sent (an offer or channel is already open, or the session's key material is missing).
    @discardableResult
    public func offer(_ session: ExtensionSession) -> String? {
        guard keyMaterial() != nil, outgoing == nil, channelCid == nil else { return nil }
        let cid = randomBytes(16).base64URL()
        let key1 = randomBytes(16)
        // State before sending: a fast peer's answer can be delivered inside `request` itself (the
        // Kotlin side keeps the same order), and it must find the offer it answers.
        var out = Outgoing(cid: cid, key1: key1, requestId: nil)
        outgoing = out
        guard let reqId = session.request(id, OfferBody(cid: cid, key1: key1.base64URL())) else {
            if outgoing?.cid == cid { outgoing = nil }
            return nil
        }
        out.requestId = reqId
        if outgoing?.cid == cid { outgoing = out }
        return cid
    }

    // MARK: phone side (answering) — the iPhone app never accepts; the core is symmetric, like files'

    /// Accepts `offer`: the host listens on `host`:`port` itself (its local-network interface) and runs a
    /// `WifiAudioChannel` with the returned keys. Nil when the session's key material is missing (the peer
    /// is told `key`) or a channel is already open on this link.
    public func accept(_ session: ExtensionSession, offer: Offer, replyTo: ExtensionMessage,
                       host: String, port: Int) -> WifiAudioKeys.ChannelKeys? {
        guard let th = keyMaterial() else {
            session.send(Self.errorType, FailBody(cid: offer.cid, code: "key"))
            return nil
        }
        guard channelCid == nil else { return nil }
        let key2 = randomBytes(16)
        let keys = WifiAudioKeys.derive(th: th, cid: offer.cid, key1: offer.key1, key2: key2)
        let body = AcceptBody(cid: offer.cid, host: host, port: port, key2: key2.base64URL())
        channelCid = offer.cid   // before replying, as in `offer`: the peer may answer inside the send
        guard session.reply(to: replyTo, body, type: Self.acceptType) else {
            if channelCid == offer.cid { channelCid = nil }
            return nil
        }
        return keys
    }

    /// Declines `offer` with one of §7.11's reasons (`busy`, `unavailable`, `dry`).
    public func decline(_ session: ExtensionSession, offer: Offer, replyTo: ExtensionMessage, why: String) {
        session.reply(to: replyTo, DeclineBody(cid: offer.cid, why: why), type: Self.declineType)
    }

    // MARK: shared endings

    /// Tells the peer the open channel is closed (call over, path lost, switched off).
    public func sendDown(_ session: ExtensionSession, why: String? = nil) {
        guard let cid = channelCid else { return }
        channelCid = nil
        session.send(Self.downType, DownBody(cid: cid, why: why))
    }

    /// Tells the peer a channel failed outside an answer (§7.11's `x.tether.wifiaudio.error`).
    public func sendError(_ session: ExtensionSession, cid: String, code: String) {
        if channelCid == cid { channelCid = nil }
        if outgoing?.cid == cid { outgoing = nil }
        session.send(Self.errorType, FailBody(cid: cid, code: code))
    }

    /// The channel the host is running ended (its engine reported): forget it, without telling the peer
    /// (the peer's own engine sees the connection close, and a `down` may already have been sent).
    public func channelEnded(_ cid: String) {
        if channelCid == cid { channelCid = nil }
    }

    public func linkChanged(_ session: ExtensionSession, up: Bool) {
        guard !up else { return }
        outgoing = nil; channelCid = nil
    }

    public func stop() {
        outgoing = nil; channelCid = nil
    }

    // MARK: messages

    public func received(_ message: ExtensionMessage, on session: ExtensionSession) throws {
        if message.t == MsgType.error {   // §7.4: an answer to one of our requests
            // The offer is this extension's only outstanding request; its id may not be stamped yet
            // if the answer was delivered inside `request` itself.
            guard let re = message.re, let out = outgoing, out.requestId == nil || out.requestId == re else { return }
            outgoing = nil
            let e = try message.body(ErrorBody.self)
            onDeclined?(session, out.cid, e.code ?? Ext.errFailed)
            return
        }
        switch message.t {
        case id:
            guard message.re == nil else { return }
            let body = try message.body(OfferBody.self)
            guard let key1 = Data(base64URL: body.key1), key1.count == 16,
                  let cidRaw = Data(base64URL: body.cid), cidRaw.count == 16 else {
                throw WifiAudioError.badOffer
            }
            onOffer?(session, Offer(cid: body.cid, key1: key1), message)
        case Self.acceptType:
            let acc = try message.body(AcceptBody.self)
            guard let out = outgoing, out.cid == acc.cid else { return }
            guard let th = keyMaterial(), let key2 = Data(base64URL: acc.key2), key2.count == 16 else {
                outgoing = nil
                session.send(Self.errorType, FailBody(cid: acc.cid, code: "key"))
                onError?(session, acc.cid, "key")
                return
            }
            outgoing = nil
            channelCid = acc.cid
            onAccepted?(session, acc, WifiAudioKeys.derive(th: th, cid: acc.cid, key1: out.key1, key2: key2))
        case Self.declineType:
            let note = try message.body(DeclineBody.self)
            if outgoing?.cid == note.cid { outgoing = nil }
            onDeclined?(session, note.cid, note.why)
        case Self.downType:
            let note = try message.body(DownBody.self)
            if channelCid == note.cid { channelCid = nil }
            if outgoing?.cid == note.cid { outgoing = nil }
            onDown?(session, note.cid, note.why)
        case Self.errorType:
            let note = try message.body(FailBody.self)
            if channelCid == note.cid { channelCid = nil }
            if outgoing?.cid == note.cid { outgoing = nil }
            onError?(session, note.cid, note.code)
        default:
            break
        }
    }
}

public enum WifiAudioError: Error { case badOffer }

/// The one-time channel keys (§7.11): HKDF-SHA256 with the session's transcript hash as salt — so the keys
/// are bound to this paired session — over both sides' fresh salts, with the channel id in the info. The
/// link's traffic keys are inputs to nothing here and are never reused. The same derivation as the Kotlin
/// `WifiAudioKeys`, and the same construction as `FilesKeys` with this extension's info string.
public enum WifiAudioKeys {
    public struct ChannelKeys: Equatable, Sendable {
        /// Seals the client's frames (and opens the phone's).
        public var clientToPhone: Data
        /// Seals the phone's frames (and opens the client's).
        public var phoneToClient: Data
        public init(clientToPhone: Data, phoneToClient: Data) {
            self.clientToPhone = clientToPhone; self.phoneToClient = phoneToClient
        }
    }

    public static func derive(th: Data, cid: String, key1: Data, key2: Data) -> ChannelKeys {
        var info = Data("tether/1 x.tether.wifiaudio".utf8)
        info.append(0)
        info.append(Data(base64URL: cid) ?? Data())
        let okm = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: key1 + key2),
                                         salt: th, info: info, outputByteCount: 64)
        let bytes = okm.withUnsafeBytes { Data($0) }
        return ChannelKeys(clientToPhone: bytes.prefix(32), phoneToClient: Data(bytes.suffix(32)))
    }
}

/// The sealed frame stream of §7.11: frames for the app's TCP engine to carry. A frame is a §2 record
/// sealed with the §4 record layer under the channel keys (channel byte 0); its plaintext is one frame
/// byte and a payload — JSON for HELLO, PING, PONG and ERR, a §4 audio payload verbatim for AUDIO.
public final class WifiAudioWire {
    public static let hello: UInt8 = 0x01
    public static let audio: UInt8 = 0x02
    public static let ping: UInt8 = 0x03
    public static let pong: UInt8 = 0x04
    public static let bye: UInt8 = 0x05
    public static let err: UInt8 = 0x06

    public struct HelloBody: Codable, Equatable, Sendable {
        public var cid: String
        public init(cid: String) { self.cid = cid }
    }
    public struct PingBody: Codable, Equatable, Sendable {
        public var ts: Int64
        public init(ts: Int64) { self.ts = ts }
    }
    public struct ErrBody: Codable, Equatable, Sendable {
        public var code: String
        public init(code: String) { self.code = code }
    }

    private let session: SecureSession
    /// [sendKey] seals this side's frames; [recvKey] opens the peer's. The client takes
    /// (clientToPhone, phoneToClient), the phone the same pair swapped.
    public init(sendKey: Data, recvKey: Data) { session = SecureSession(sendKey: sendKey, recvKey: recvKey) }

    public func sealFrame(_ type: UInt8, _ payload: Data) throws -> Record {
        try session.seal(channel: Proto.channelControl, Data([type]) + payload)
    }
    public func sealJSON<B: Encodable>(_ type: UInt8, _ body: B) throws -> Record {
        try sealFrame(type, JSONEncoder().encode(body))
    }
    /// The next frame off a record; throws on anything that does not open (§7.11: `protocol`).
    public func openFrame(_ record: Record) throws -> (UInt8, Data) {
        let (_, plain) = try session.open(record)
        guard let first = plain.first else { throw SessionError.tooShort }
        return (first, Data(plain.dropFirst()))
    }
}

/// One Wi-Fi audio channel (§7.11) over a blocking byte stream (the app's `NWConnection` adapter, a pipe
/// in tests): the client side opens with HELLO, both sides then exchange AUDIO frames (one §4 audio
/// payload each), the client pings and the phone pongs. `run` blocks until the channel ends; `sendAudio`
/// and `sendPing` are safe from any thread. Which path a frame *takes* is the caller's decision
/// (`WifiAudioPolicy.sendPath` over `lastHeardMs`); the channel only carries what it is given.
public final class WifiAudioChannel {
    /// How a channel ended: the peer said BYE, the connection closed, or it failed with a §7.11 code.
    public enum End: Equatable, Sendable {
        case bye, closed, failed(String)
    }

    private let wire: WifiAudioWire
    private let cid: String
    private let initiator: Bool
    private let now: () -> Int64
    private let writeLock = NSLock()
    private func withState<T>(_ body: () -> T) -> T { stateLock.lock(); defer { stateLock.unlock() }; return body() }
    private let stateLock = NSLock()
    private var stream: FilesByteStream?
    private var ended = false
    private var _lastHeardMs: Int64?
    private var _lastPongRttMs: Int64?

    /// The last time anything was heard from the peer on this channel (any frame), in `now`'s clock.
    public var lastHeardMs: Int64? { withState { _lastHeardMs } }
    /// The most recent PING/PONG round trip, when one was measured.
    public var lastPongRttMs: Int64? { withState { _lastPongRttMs } }
    /// The channel has not ended (frames may still be sent; the policy decides whether they should be).
    public var isUp: Bool { withState { !ended } }

    /// `initiator` is the client (it connects and speaks HELLO first); the phone expects the HELLO.
    public init(keys: WifiAudioKeys.ChannelKeys, cid: String, initiator: Bool,
                now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.cid = cid
        self.initiator = initiator
        self.now = now
        wire = initiator ? WifiAudioWire(sendKey: keys.clientToPhone, recvKey: keys.phoneToClient)
                         : WifiAudioWire(sendKey: keys.phoneToClient, recvKey: keys.clientToPhone)
    }

    /// Runs the channel to its end (blocking; call on a background queue). `onFrame` gets one §4 audio
    /// payload per AUDIO frame; `onEnd` fires exactly once.
    public func run(_ stream: FilesByteStream, onFrame: @escaping (Data) -> Void, onEnd: @escaping (End) -> Void) {
        let pump = FramePump(wire: wire, stream: stream, lock: writeLock)
        if initiator {
            // HELLO goes on the wire before the channel becomes sendable: sends gate on the stored
            // stream, which is only set after this write — no frame can ever precede the HELLO.
            try? pump.writeJSON(WifiAudioWire.hello, WifiAudioWire.HelloBody(cid: cid))
        }
        withState { self.stream = stream; _lastHeardMs = now() }   // liveness counts from the channel's birth
        let end = pumpLoop(pump, onFrame)
        withState { ended = true; self.stream = nil }
        stream.close()
        onEnd(end)
    }

    private func pumpLoop(_ pump: FramePump, _ onFrame: (Data) -> Void) -> End {
        do {
            if !initiator {
                guard let first = try pump.readFrame() else { return .closed }
                guard first.0 == WifiAudioWire.hello,
                      (try? JSONDecoder().decode(WifiAudioWire.HelloBody.self, from: first.1))?.cid == cid
                else { return .failed("protocol") }
                heard()
            }
            while true {
                guard let (type, payload) = try pump.readFrame() else { return .closed }
                heard()
                switch type {
                case WifiAudioWire.audio:
                    onFrame(payload)
                case WifiAudioWire.ping:
                    let ping = try JSONDecoder().decode(WifiAudioWire.PingBody.self, from: payload)
                    try pump.writeJSON(WifiAudioWire.pong, ping)
                case WifiAudioWire.pong:
                    let pong = try JSONDecoder().decode(WifiAudioWire.PingBody.self, from: payload)
                    let rtt = now() - pong.ts
                    if rtt >= 0, rtt < 60_000 { withState { _lastPongRttMs = rtt } }
                case WifiAudioWire.bye:
                    return .bye
                case WifiAudioWire.err:
                    let e = try? JSONDecoder().decode(WifiAudioWire.ErrBody.self, from: payload)
                    return .failed(e?.code ?? "io")
                default:
                    return .failed("protocol")
                }
            }
        } catch {
            return .failed(Self.code(for: error))
        }
    }

    private func heard() { withState { _lastHeardMs = now() } }

    static func code(for error: Error) -> String {
        switch error {
        case FilesStreamError.timeout: return "timeout"
        case is SessionError: return "protocol"
        default: return "io"
        }
    }

    /// Seals and writes one audio payload; false when the channel has ended or the write failed.
    @discardableResult
    public func sendAudio(_ payload: Data) -> Bool {
        guard isUp, let stream else { return false }
        return write { try $0.writeFrame(WifiAudioWire.audio, payload) } != nil
    }

    /// A PING with this side's clock (the client sends them; the phone's PONG echoes the body).
    @discardableResult
    public func sendPing() -> Bool {
        guard isUp, stream != nil else { return false }
        return write { try $0.writeJSON(WifiAudioWire.ping, WifiAudioWire.PingBody(ts: now())) } != nil
    }

    /// Ends the channel: a BYE for the peer (best effort), then the stream closes, which ends `run`.
    public func close(notify: Bool = true) {
        if notify, isUp { _ = write { try $0.writeFrame(WifiAudioWire.bye, Data()) } }
        withState { ended = true }
        stream?.close()
    }

    /// One sealed write under the write lock, so a seal's counter and its bytes never part company.
    private func write(_ body: (FramePump) throws -> Void) -> Bool? {
        guard let stream else { return nil }
        writeLock.lock(); defer { writeLock.unlock() }
        do { try body(FramePump(wire: wire, stream: stream, lock: nil)); return true } catch { return nil }
    }
}

/// Records in and out of a `WifiAudioChannel`'s stream: §2 framing over the blocking byte stream.
/// Writes are made through the channel's write lock (passed in, or already held when nil).
private final class FramePump {
    private let wire: WifiAudioWire
    private let stream: FilesByteStream
    private let lock: NSLock?
    private var reader = RecordReader()

    init(wire: WifiAudioWire, stream: FilesByteStream, lock: NSLock?) {
        self.wire = wire; self.stream = stream; self.lock = lock
    }

    func writeFrame(_ type: UInt8, _ payload: Data) throws {
        if let lock { lock.lock(); defer { lock.unlock() } }
        try stream.write(wire.sealFrame(type, payload).encoded())
    }

    func writeJSON<B: Encodable>(_ type: UInt8, _ body: B) throws {
        if let lock { lock.lock(); defer { lock.unlock() } }
        try stream.write(wire.sealJSON(type, body).encoded())
    }

    /// The next frame, or nil when the peer closed. Throws on anything that does not open (§7.11: `protocol`).
    func readFrame() throws -> (UInt8, Data)? {
        while true {
            if let record = try reader.next() { return try wire.openFrame(record) }
            guard let chunk = try stream.readSome() else { return nil }
            reader.append(chunk)
        }
    }
}
