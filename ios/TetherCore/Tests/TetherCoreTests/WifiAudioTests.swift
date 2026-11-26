import Foundation
import XCTest
@testable import TetherCore

/// `x.tether.wifiaudio` (§7.11): the policy's decision matrix, the key derivation pinned against the
/// Kotlin side's, the channel over an in-memory pipe, and the offer/accept negotiation driven through
/// two registries wired back to back — the counterpart of the Kotlin `WifiAudioExtensionTest`.
final class WifiAudioTests: XCTestCase {
    private let th = Data((0..<32).map { UInt8($0 * 7 + 1) })
    private let cid = Data((0..<16).map { UInt8($0) }).base64URL()   // "AAECAwQFBgcICQoLDA0ODw"

    private func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

    // MARK: key derivation (pinned: the Kotlin test asserts the same bytes)

    func testKeyDerivationIsPinned() {
        let keys = WifiAudioKeys.derive(th: th, cid: cid, key1: Data(repeating: 0x11, count: 16), key2: Data(repeating: 0x22, count: 16))
        XCTAssertEqual(hex(keys.clientToPhone), "aab8850e8726d66344cb974b9d3d96a7d8d6683944b67a5ca996273ab1fedc99")
        XCTAssertEqual(hex(keys.phoneToClient), "fa57d79d9b113f72ee7500e61cae8599f971f34124bd48fd684200aebdd808eb")
        // A different channel id or salt derives different keys (a channel's keys are its own).
        let other = WifiAudioKeys.derive(th: th, cid: Data((0..<16).map { UInt8($0 + 9) }).base64URL(),
                                         key1: Data(repeating: 0x11, count: 16), key2: Data(repeating: 0x22, count: 16))
        XCTAssertNotEqual(keys, other)
        // And different from the files derivation over the same inputs (the info string separates them).
        let files = FilesKeys.derive(th: th, tid: cid, key1: Data(repeating: 0x11, count: 16), key2: Data(repeating: 0x22, count: 16))
        XCTAssertNotEqual(keys.clientToPhone, files.offererToAcceptor)
    }

    // MARK: policy

    func testOfferPolicy() {
        let t: Int64 = 100_000
        // The happy path, and each single reason not to offer.
        XCTAssertTrue(WifiAudioPolicy.shouldOffer(enabled: true, inCall: true, hasNetwork: true, channelBusy: false, attemptsThisCall: 0, lastAttemptMs: nil, nowMs: t))
        XCTAssertFalse(WifiAudioPolicy.shouldOffer(enabled: false, inCall: true, hasNetwork: true, channelBusy: false, attemptsThisCall: 0, lastAttemptMs: nil, nowMs: t))
        XCTAssertFalse(WifiAudioPolicy.shouldOffer(enabled: true, inCall: false, hasNetwork: true, channelBusy: false, attemptsThisCall: 0, lastAttemptMs: nil, nowMs: t))
        XCTAssertFalse(WifiAudioPolicy.shouldOffer(enabled: true, inCall: true, hasNetwork: false, channelBusy: false, attemptsThisCall: 0, lastAttemptMs: nil, nowMs: t))
        XCTAssertFalse(WifiAudioPolicy.shouldOffer(enabled: true, inCall: true, hasNetwork: true, channelBusy: true, attemptsThisCall: 0, lastAttemptMs: nil, nowMs: t))
        // Attempts are capped per call, and retries wait out the delay.
        XCTAssertFalse(WifiAudioPolicy.shouldOffer(enabled: true, inCall: true, hasNetwork: true, channelBusy: false, attemptsThisCall: WifiAudioPolicy.maxAttemptsPerCall, lastAttemptMs: nil, nowMs: t))
        XCTAssertFalse(WifiAudioPolicy.shouldOffer(enabled: true, inCall: true, hasNetwork: true, channelBusy: false, attemptsThisCall: 1, lastAttemptMs: t - 1_000, nowMs: t))
        XCTAssertTrue(WifiAudioPolicy.shouldOffer(enabled: true, inCall: true, hasNetwork: true, channelBusy: false, attemptsThisCall: 1, lastAttemptMs: t - WifiAudioPolicy.retryDelayMs, nowMs: t))
    }

    func testSendPathAndDeath() {
        let t: Int64 = 100_000
        XCTAssertEqual(WifiAudioPolicy.sendPath(channelUp: true, lastHeardMs: t - 1_000, nowMs: t), .wifi)
        XCTAssertEqual(WifiAudioPolicy.sendPath(channelUp: true, lastHeardMs: t - WifiAudioPolicy.heardTimeoutMs, nowMs: t), .wifi)
        XCTAssertEqual(WifiAudioPolicy.sendPath(channelUp: true, lastHeardMs: t - WifiAudioPolicy.heardTimeoutMs - 1, nowMs: t), .bluetooth)
        XCTAssertEqual(WifiAudioPolicy.sendPath(channelUp: true, lastHeardMs: nil, nowMs: t), .bluetooth)
        XCTAssertEqual(WifiAudioPolicy.sendPath(channelUp: false, lastHeardMs: t, nowMs: t), .bluetooth)
        XCTAssertFalse(WifiAudioPolicy.channelDead(lastHeardMs: t - WifiAudioPolicy.deadTimeoutMs, nowMs: t))
        XCTAssertTrue(WifiAudioPolicy.channelDead(lastHeardMs: t - WifiAudioPolicy.deadTimeoutMs - 1, nowMs: t))
        XCTAssertTrue(WifiAudioPolicy.channelDead(lastHeardMs: nil, nowMs: t))
        XCTAssertTrue(WifiAudioPolicy.pingDue(lastPingMs: nil, nowMs: t))
        XCTAssertFalse(WifiAudioPolicy.pingDue(lastPingMs: t - 1_000, nowMs: t))
        XCTAssertTrue(WifiAudioPolicy.pingDue(lastPingMs: t - WifiAudioPolicy.pingIntervalMs, nowMs: t))
    }

    func testDeclineReasons() {
        XCTAssertEqual(WifiAudioPolicy.declineReason(dry: true, channelOpen: false, bridging: true, hasAddress: true), "dry")
        XCTAssertEqual(WifiAudioPolicy.declineReason(dry: false, channelOpen: true, bridging: true, hasAddress: true), "busy")
        XCTAssertEqual(WifiAudioPolicy.declineReason(dry: false, channelOpen: false, bridging: false, hasAddress: true), "unavailable")
        XCTAssertEqual(WifiAudioPolicy.declineReason(dry: false, channelOpen: false, bridging: true, hasAddress: false), "unavailable")
        XCTAssertNil(WifiAudioPolicy.declineReason(dry: false, channelOpen: false, bridging: true, hasAddress: true))
        // A test link is refused before anything else is even considered.
        XCTAssertEqual(WifiAudioPolicy.declineReason(dry: true, channelOpen: true, bridging: false, hasAddress: false), "dry")
    }

    // MARK: the channel over a pipe

    /// Two ends of one connection: what one writes, the other reads; close ends the peer's reads.
    private final class PipeEnd: FilesByteStream {
        private let cond = NSCondition()
        private var inbox: [Data] = []
        private var closed = false
        var peer: PipeEnd?

        func write(_ data: Data) throws {
            guard let peer else { throw FilesStreamError.closed }
            peer.deliver(data)
        }
        private func deliver(_ data: Data) { cond.lock(); inbox.append(data); cond.signal(); cond.unlock() }
        func readSome() throws -> Data? {
            cond.lock()
            while inbox.isEmpty && !closed { cond.wait() }
            let out = inbox.isEmpty ? nil : inbox.removeFirst()
            cond.unlock()
            return out
        }
        func close() {
            cond.lock(); closed = true; cond.signal(); cond.unlock()
            peer?.peerClosed()
        }
        private func peerClosed() { cond.lock(); closed = true; cond.signal(); cond.unlock() }
    }

    private final class Clock { var nowMs: Int64 = 1_000_000 }

    private func pipePair() -> (PipeEnd, PipeEnd) {
        let a = PipeEnd(), b = PipeEnd()
        a.peer = b; b.peer = a
        return (a, b)
    }

    private func keys() -> WifiAudioKeys.ChannelKeys {
        WifiAudioKeys.derive(th: th, cid: cid, key1: Data(repeating: 0x11, count: 16), key2: Data(repeating: 0x22, count: 16))
    }

    /// Runs a channel on a background queue; the end reason lands in the returned box.
    private final class EndBox { let exp: XCTestExpectation; var end: WifiAudioChannel.End?; init(_ e: XCTestExpectation) { exp = e } }

    private func start(_ channel: WifiAudioChannel, on stream: PipeEnd, onFrame: @escaping (Data) -> Void) -> EndBox {
        let box = EndBox(expectation(description: "channel ended"))
        DispatchQueue.global().async {
            channel.run(stream, onFrame: onFrame) { end in box.end = end; box.exp.fulfill() }
        }
        return box
    }

    func testChannelCarriesAudioBothWaysAndAnswersPings() {
        let clock = Clock()
        let (a, b) = pipePair()
        let client = WifiAudioChannel(keys: keys(), cid: cid, initiator: true, now: { clock.nowMs })
        let phone = WifiAudioChannel(keys: keys(), cid: cid, initiator: false, now: { clock.nowMs })
        let gotByPhone = expectation(description: "phone got audio")
        let gotByClient = expectation(description: "client got audio")
        let frame = AudioFrame(codec: .opus, seq: 41, payload: Data([9, 8, 7])).encoded()
        let back = AudioFrame(codec: .opus, seq: 7, payload: Data([1, 2])).encoded()
        let phoneEnd = start(phone, on: b) { payload in
            XCTAssertEqual(payload, frame)
            gotByPhone.fulfill()
        }
        let clientEnd = start(client, on: a) { payload in
            XCTAssertEqual(payload, back)
            gotByClient.fulfill()
        }
        // Sends gate on the stored stream, which the initiator sets only after writing HELLO: a ping
        // that goes out proves the greeting is on the wire, and its PONG proves the phone read it first.
        clock.nowMs = 1_000_500
        let greeted = expectation(description: "greeted")
        DispatchQueue.global().async {
            let deadline = Date().addingTimeInterval(5)
            while !client.sendPing() && Date() < deadline { Thread.sleep(forTimeInterval: 0.002) }
            while client.lastPongRttMs == nil && Date() < deadline { Thread.sleep(forTimeInterval: 0.002) }
            greeted.fulfill()
        }
        wait(for: [greeted], timeout: 6)
        XCTAssertEqual(client.lastPongRttMs, 0)
        XCTAssertTrue(client.sendAudio(frame))
        XCTAssertTrue(phone.sendAudio(back))
        wait(for: [gotByPhone, gotByClient], timeout: 5)
        // Heard: both sides saw a frame at the advanced clock.
        XCTAssertEqual(client.lastHeardMs, 1_000_500)
        XCTAssertEqual(phone.lastHeardMs, 1_000_500)
        // A ping is answered, and the round trip is measured (0 at a frozen clock).
        XCTAssertTrue(client.sendPing())
        let ponged = expectation(description: "pong measured")
        DispatchQueue.global().async {
            while client.lastPongRttMs == nil { Thread.sleep(forTimeInterval: 0.005) }
            ponged.fulfill()
        }
        wait(for: [ponged], timeout: 5)
        XCTAssertEqual(client.lastPongRttMs, 0)
        // BYE ends the peer cleanly; closing ends our own side.
        client.close()
        wait(for: [phoneEnd.exp], timeout: 5)
        XCTAssertEqual(phoneEnd.end, .bye)
        phone.close()
        wait(for: [clientEnd.exp], timeout: 5)
        XCTAssertEqual(clientEnd.end, .closed)
    }

    func testChannelWithWrongKeysDiesAsProtocol() {
        let (a, b) = pipePair()
        let client = WifiAudioChannel(keys: keys(), cid: cid, initiator: true, now: { 0 })
        let wrong = WifiAudioKeys.derive(th: th, cid: Data((0..<16).map { UInt8($0 + 9) }).base64URL(),
                                         key1: Data(repeating: 0x11, count: 16), key2: Data(repeating: 0x22, count: 16))
        let phone = WifiAudioChannel(keys: wrong, cid: cid, initiator: false, now: { 0 })
        let phoneEnd = start(phone, on: b) { _ in XCTFail("no frame may pass") }
        let clientEnd = start(client, on: a) { _ in }
        wait(for: [phoneEnd.exp], timeout: 5)
        XCTAssertEqual(phoneEnd.end, .failed("protocol"))
        client.close()
        wait(for: [clientEnd.exp], timeout: 5)
    }

    func testPhoneChannelRefusesAFirstFrameThatIsNotHello() throws {
        let (a, b) = pipePair()
        let k = keys()
        let phone = WifiAudioChannel(keys: k, cid: cid, initiator: false, now: { 0 })
        let phoneEnd = start(phone, on: b) { _ in XCTFail("no frame may pass") }
        // A correctly sealed AUDIO frame, but first: not a HELLO.
        let wire = WifiAudioWire(sendKey: k.clientToPhone, recvKey: k.phoneToClient)
        try a.write(wire.sealFrame(WifiAudioWire.audio, Data([1, 2, 3])).encoded())
        wait(for: [phoneEnd.exp], timeout: 5)
        XCTAssertEqual(phoneEnd.end, .failed("protocol"))
        a.close()
    }

    // MARK: negotiation through two registries

    private final class Port: ExtensionPort {
        var transport = "l2cap", inCall = false
        var deliver: ((MessageHeader, Data) -> Void)?
        private var nextId = 1
        func send(_ type: String, _ body: any Encodable, expectReply: Bool, re: Int?) -> Int? {
            let id = expectReply ? nextId : nil
            if expectReply { nextId += 1 }
            let data = try! Control.encode(type, body, id: id, re: re)
            deliver?(try! Control.header(data), data)
            return expectReply ? id : 0
        }
    }

    /// Two registries (client A, phone B) with both extensions registered and the extension agreed.
    private func linkedPair() -> (ExtensionRegistry, ExtensionRegistry, WifiAudioExtension, WifiAudioExtension) {
        let regA = ExtensionRegistry(), regB = ExtensionRegistry()
        let extA = WifiAudioExtension(keyMaterial: { [th] in th })
        let extB = WifiAudioExtension(keyMaterial: { [th] in th })
        try! regA.register(extA); try! regB.register(extB)
        regA.start(); regB.start()
        let portA = Port(), portB = Port()
        portA.deliver = { h, d in _ = regB.route(h, d) }
        portB.deliver = { h, d in _ = regA.route(h, d) }
        regA.linkUp(portA); regB.linkUp(portB)
        regA.peerCaps(["x.tether.wifiaudio/1"]); regB.peerCaps(["x.tether.wifiaudio/1"])
        return (regA, regB, extA, extB)
    }

    func testOfferAcceptDerivesTheSameKeysOnBothSides() {
        let (regA, regB, extA, extB) = linkedPair()
        var phoneKeys: WifiAudioKeys.ChannelKeys?
        var clientKeys: WifiAudioKeys.ChannelKeys?
        var acceptedHost: String?, acceptedPort: Int?
        extB.onOffer = { session, offer, message in
            phoneKeys = extB.accept(session, offer: offer, replyTo: message, host: "192.168.0.100", port: 41002)
        }
        extA.onAccepted = { _, accept, keys in
            clientKeys = keys; acceptedHost = accept.host; acceptedPort = accept.port
        }
        let session = regA.session(for: extA)
        XCTAssertNotNil(session)
        XCTAssertNotNil(extA.offer(session!))
        XCTAssertEqual(acceptedHost, "192.168.0.100")
        XCTAssertEqual(acceptedPort, 41002)
        XCTAssertNotNil(phoneKeys)
        XCTAssertEqual(clientKeys, phoneKeys)
        // A second offer while the channel is open is refused by the extension itself.
        XCTAssertNil(extA.offer(session!))
        _ = regB
    }

    func testOfferDeclineReachesTheClient() {
        let (regA, _, extA, extB) = linkedPair()
        var declined: (String, String?)?
        extB.onOffer = { session, offer, message in
            extB.decline(session, offer: offer, replyTo: message, why: "unavailable")
        }
        extA.onDeclined = { _, cid, why in declined = (cid, why) }
        let session = regA.session(for: extA)!
        let cid = extA.offer(session)
        XCTAssertNotNil(cid)
        XCTAssertEqual(declined?.0, cid)
        XCTAssertEqual(declined?.1, "unavailable")
        // And the client may offer again (the decline cleared its state).
        extB.onOffer = { _, _, _ in }
        XCTAssertNotNil(extA.offer(session))
    }

    func testOfferWithoutKeyMaterialIsNotSent() {
        let reg = ExtensionRegistry(), port = Port()
        let ext = WifiAudioExtension(keyMaterial: { nil })
        try! reg.register(ext); reg.start(); reg.linkUp(port)
        reg.peerCaps(["x.tether.wifiaudio/1"])
        XCTAssertNil(ext.offer(reg.session(for: ext)!))
    }

    func testDownClearsTheChannel() {
        let (regA, regB, extA, extB) = linkedPair()
        var downWhy: String??
        extB.onOffer = { session, offer, message in
            _ = extB.accept(session, offer: offer, replyTo: message, host: "192.168.0.100", port: 41002)
        }
        extA.onDown = { _, _, why in downWhy = why }
        let sessionA = regA.session(for: extA)!
        XCTAssertNotNil(extA.offer(sessionA))
        extB.sendDown(regB.session(for: extB)!, why: "ended")
        XCTAssertEqual(downWhy, "ended")
        // Both sides forgot the channel: a fresh offer goes out.
        XCTAssertNotNil(extA.offer(sessionA))
    }
}
