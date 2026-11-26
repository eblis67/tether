#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation

/// `x.tether.files` (protocol §7.10): file and photo transfer between the two paired devices. The sealed session
/// carries only the negotiation (offer, listing, accept, done, cancel, error); the bytes move over a short-lived
/// TCP connection on the local network, sealed with a one-time key derived from the session's transcript hash and
/// two fresh salts, so the link keys are never reused and nothing leaves the two devices.
///
/// This is the counterpart of the Kotlin `FilesExtension`: same bodies, same paging, same state machine. The
/// socket engine itself is the app's (stage B): `accept` and the `onAccepted` callback hand it the derived
/// `FilesKeys.TransferKeys`, and `FilesWire` seals and opens the §7.10 frames over whatever stream it runs.
public final class FilesExtension: TetherExtension {
    // MARK: bodies (the same shapes the Kotlin codec builds)

    /// One offered file: a basename, its length and its SHA-256 (base64url).
    public struct FileEntry: Codable, Equatable, Sendable {
        public var name: String; public var size: Int64; public var sha256: String
        public init(name: String, size: Int64, sha256: String) { self.name = name; self.size = size; self.sha256 = sha256 }
    }
    /// One listing entry; `kind` is `"file"` or `"dir"`.
    public struct DirEntry: Codable, Equatable, Sendable {
        public var name: String; public var size: Int64; public var kind: String
        public init(name: String, size: Int64, kind: String) { self.name = name; self.size = size; self.kind = kind }
    }
    public struct OfferPage: Codable, Equatable, Sendable {
        public var tid: String; public var page: Int; public var pages: Int; public var key1: String; public var files: [FileEntry]
        public init(tid: String, page: Int, pages: Int, key1: String, files: [FileEntry]) {
            self.tid = tid; self.page = page; self.pages = pages; self.key1 = key1; self.files = files
        }
    }
    /// A complete offer, assembled from its pages. `key1` is the sender's raw key salt.
    public struct Offer: Sendable {
        public var tid: String; public var key1: Data; public var entries: [FileEntry]
        public init(tid: String, key1: Data, entries: [FileEntry]) { self.tid = tid; self.key1 = key1; self.entries = entries }
    }
    public struct ListRequest: Codable, Equatable, Sendable {
        public var path: String?; public var page: Int
        public init(path: String?, page: Int) { self.path = path; self.page = page }
    }
    public struct ListPage: Codable, Equatable, Sendable {
        public var page: Int; public var pages: Int; public var entries: [DirEntry]
        public init(page: Int, pages: Int, entries: [DirEntry]) { self.page = page; self.pages = pages; self.entries = entries }
    }
    public struct HaveEntry: Codable, Equatable, Sendable {
        public var name: String; public var have: Int64
        public init(name: String, have: Int64) { self.name = name; self.have = have }
    }
    public struct AcceptBody: Codable, Equatable, Sendable {
        public var tid: String; public var host: String; public var port: Int; public var key2: String; public var have: [HaveEntry]
        public init(tid: String, host: String, port: Int, key2: String, have: [HaveEntry]) {
            self.tid = tid; self.host = host; self.port = port; self.key2 = key2; self.have = have
        }
        public func haveBytes(_ name: String) -> Int64 { have.first { $0.name == name }?.have ?? 0 }
    }
    /// The `{tid, why?}` of a decline or a cancel.
    public struct NoteBody: Codable, Equatable, Sendable {
        public var tid: String; public var why: String?
        public init(tid: String, why: String? = nil) { self.tid = tid; self.why = why }
    }
    public struct DoneBody: Codable, Equatable, Sendable {
        public var tid: String; public var files: Int; public var bytes: Int64
        public init(tid: String, files: Int, bytes: Int64) { self.tid = tid; self.files = files; self.bytes = bytes }
    }
    public struct ErrorNote: Codable, Equatable, Sendable {
        public var tid: String; public var code: String; public var msg: String?
        public init(tid: String, code: String, msg: String? = nil) { self.tid = tid; self.code = code; self.msg = msg }
    }

    public enum FilesError: Error { case badEntries(String), badName(String) }

    public static let listType = Ext.files + ".list"
    public static let acceptType = Ext.files + ".accept"
    public static let declineType = Ext.files + ".decline"
    public static let doneType = Ext.files + ".done"
    public static let cancelType = Ext.files + ".cancel"
    public static let errorType = Ext.files + ".error"
    /// Offer and listing pages aim at this encoded weight, under §7.3's 16 KiB (the Kotlin `PAGE_WEIGHT`).
    public static let pageWeight = 11_000
    public static let maxFiles = 1024
    public static let maxFileSize: Int64 = 1 << 41
    public static let maxNameBytes = 255

    public let id = Ext.files
    public var types: Set<String> { [id, Self.listType, Self.acceptType, Self.declineType, Self.doneType, Self.cancelType, Self.errorType] }

    /// The session's transcript hash (§3), from the host; nil means this side cannot offer or accept (§7.10: `key`).
    private let keyMaterial: () -> Data?
    public init(keyMaterial: @escaping () -> Data?) { self.keyMaterial = keyMaterial }

    /// Answers `x.tether.files.list` requests: the shared entries under the path (nil: the shared roots).
    public var lister: ((String?) -> [DirEntry])?
    /// A complete offer arrived; answer it with `accept` or `decline` (the message is the offer's last page).
    public var onOffer: ((ExtensionSession, Offer, ExtensionMessage) -> Void)?
    /// The peer accepted our offer: run the sending engine with these keys to `host`:`port` (resume offsets inside).
    public var onAccepted: ((ExtensionSession, AcceptBody, FilesKeys.TransferKeys) -> Void)?
    /// The answer to one of our `requestList` calls.
    public var onListResult: ((ExtensionSession, ListPage) -> Void)?
    /// The receiver verified every file of a transfer we sent.
    public var onDone: ((ExtensionSession, String) -> Void)?
    /// The peer declined our offer (or refused it with an `error`, §7.4).
    public var onDeclined: ((ExtensionSession, String, String?) -> Void)?
    public var onCancelled: ((ExtensionSession, String) -> Void)?
    /// A transfer failed: `code` is one of §7.10's (`busy`, `checksum`, `io`, `key`, `offset`, `protocol`, `timeout`).
    public var onError: ((ExtensionSession, String?, String) -> Void)?

    private struct Outgoing { var tid: String; var key1: Data; var requestId: Int? }
    private struct PendingOffer {
        var tid: String; var pages: Int; var got: [Int: [FileEntry]] = [:]; var key1: Data?; var lastMessage: ExtensionMessage?
    }
    private var outgoing: Outgoing?
    private var incomingTid: String?
    private var pendingOffer: PendingOffer?
    private var listRequests = Set<Int>()

    // MARK: validation (§7.10: a receiver refuses an offer that breaks these)

    /// A transferable name: a basename only, so a receiver can store it without escaping its folder.
    public static func validName(_ name: String) -> Bool {
        !name.isEmpty && name.utf8.count <= maxNameBytes && name != "." && name != ".."
            && !name.contains("/") && !name.contains("\\")
    }

    public static func checkEntries(_ entries: [FileEntry]) throws {
        guard !entries.isEmpty, entries.count <= maxFiles else { throw FilesError.badEntries("a transfer holds 1..\(maxFiles) files") }
        guard Set(entries.map(\.name)).count == entries.count else { throw FilesError.badEntries("duplicate file name in one transfer") }
        for e in entries {
            guard validName(e.name) else { throw FilesError.badName(e.name) }
            guard e.size >= 0, e.size <= maxFileSize else { throw FilesError.badEntries("bad size for '\(e.name)'") }
            guard let digest = Data(base64URL: e.sha256), digest.count == 32 else { throw FilesError.badEntries("bad sha256 for '\(e.name)'") }
        }
    }

    /// The same greedy paging as the Kotlin side: pages whose summed weight stays under `pageWeight`.
    private static func paginate<T>(_ items: [T], weight: (T) -> Int) -> [[T]] {
        var pages: [[T]] = []; var cur: [T] = []; var w = 0
        for item in items {
            let iw = weight(item)
            if !cur.isEmpty, w + iw > pageWeight { pages.append(cur); cur = []; w = 0 }
            cur.append(item); w += iw
        }
        if !cur.isEmpty || pages.isEmpty { pages.append(cur) }
        return pages
    }

    // MARK: sending side

    /// Offers `entries` to the Android phone; returns the transfer id, or nil when it could not be sent (a transfer
    /// is already open, the session's key material is missing, or the registry refused a page).
    @discardableResult
    public func offer(_ session: ExtensionSession, entries: [FileEntry]) throws -> String? {
        try Self.checkEntries(entries)
        guard keyMaterial() != nil, outgoing == nil, incomingTid == nil else { return nil }
        let tid = randomBytes(16).base64URL()
        let key1 = randomBytes(16)
        let pages = Self.paginate(entries) { 96 + $0.name.utf8.count * 2 + $0.sha256.count }
        var out = Outgoing(tid: tid, key1: key1)
        for (i, page) in pages.enumerated() {
            let body = OfferPage(tid: tid, page: i, pages: pages.count, key1: key1.base64URL(), files: page)
            if i == pages.count - 1 {
                guard let reqId = session.request(id, body) else { return nil }
                out.requestId = reqId
            } else if !session.send(id, body) {
                return nil
            }
        }
        outgoing = out
        return tid
    }

    /// Asks the phone for one page of a listing; the answer arrives in `onListResult`. Nil when not sent.
    @discardableResult
    public func requestList(_ session: ExtensionSession, path: String? = nil, page: Int = 0) -> Int? {
        guard let reqId = session.request(Self.listType, ListRequest(path: path, page: page)) else { return nil }
        listRequests.insert(reqId)
        return reqId
    }

    // MARK: receiving side

    /// Accepts `offer`: the host listens on `host`:`port` itself (its Wi-Fi interface) and runs the receiving
    /// engine with the returned keys. Nil when the session's key material is missing (the peer is told `key`).
    public func accept(_ session: ExtensionSession, offer: Offer, replyTo: ExtensionMessage,
                       host: String, port: Int, have: [HaveEntry]) -> FilesKeys.TransferKeys? {
        guard let th = keyMaterial() else {
            session.send(Self.errorType, ErrorNote(tid: offer.tid, code: "key"))
            return nil
        }
        guard incomingTid == nil, outgoing == nil, pendingOffer?.tid == offer.tid else { return nil }
        pendingOffer = nil
        incomingTid = offer.tid
        let key2 = randomBytes(16)
        let keys = FilesKeys.derive(th: th, tid: offer.tid, key1: offer.key1, key2: key2)
        let body = AcceptBody(tid: offer.tid, host: host, port: port, key2: key2.base64URL(), have: have)
        guard session.reply(to: replyTo, body, type: Self.acceptType) else {
            incomingTid = nil
            return nil
        }
        return keys
    }

    /// Declines `offer` (the peer's `onDeclined` fires).
    public func decline(_ session: ExtensionSession, offer: Offer, replyTo: ExtensionMessage, why: String? = nil) {
        if pendingOffer?.tid == offer.tid { pendingOffer = nil }
        session.reply(to: replyTo, NoteBody(tid: offer.tid, why: why), type: Self.declineType)
    }

    /// Tells the peer a transfer is complete (the receiving side, after every file verified).
    public func sendDone(_ session: ExtensionSession, tid: String, files: Int, bytes: Int64) {
        if incomingTid == tid { incomingTid = nil }
        session.send(Self.doneType, DoneBody(tid: tid, files: files, bytes: bytes))
    }

    /// Tells the peer a transfer failed outside an answer (§7.10's `x.tether.files.error`).
    public func sendError(_ session: ExtensionSession, tid: String, code: String) {
        session.send(Self.errorType, ErrorNote(tid: tid, code: code))
    }

    /// Cancels the open transfer `tid`, on whichever side of it we are, and tells the peer.
    public func cancel(_ session: ExtensionSession, tid: String) {
        abort(tid)
        session.send(Self.cancelType, NoteBody(tid: tid))
    }

    private func abort(_ tid: String) {
        if outgoing?.tid == tid { outgoing = nil }
        if incomingTid == tid { incomingTid = nil }
        if pendingOffer?.tid == tid { pendingOffer = nil }
    }

    public func linkChanged(_ session: ExtensionSession, up: Bool) {
        guard !up else { return }
        outgoing = nil; incomingTid = nil; pendingOffer = nil; listRequests = []
    }

    public func stop() {
        outgoing = nil; incomingTid = nil; pendingOffer = nil; listRequests = []
    }

    // MARK: messages

    public func received(_ message: ExtensionMessage, on session: ExtensionSession) throws {
        if message.t == MsgType.error {   // §7.4: an answer to one of our requests
            guard let re = message.re else { return }
            let e = try message.body(ErrorBody.self)
            if let out = outgoing, out.requestId == re {
                outgoing = nil
                onDeclined?(session, out.tid, e.code ?? Ext.errFailed)
            } else if listRequests.remove(re) != nil {
                onError?(session, nil, e.code ?? Ext.errFailed)
            }
            return
        }
        switch message.t {
        case id:
            guard message.re == nil else { return }
            try onOfferPage(message, on: session)
        case Self.listType:
            if let re = message.re {
                listRequests.remove(re)
                onListResult?(session, try message.body(ListPage.self))
            } else if message.id != nil {
                let req = try message.body(ListRequest.self)
                let all = lister?(req.path) ?? []
                let pages = Self.paginate(all) { 96 + $0.name.utf8.count * 2 }
                let page = min(max(req.page, 0), pages.count - 1)
                session.reply(to: message, ListPage(page: page, pages: pages.count, entries: pages[page]), type: Self.listType)
            }
        case Self.acceptType:
            let acc = try message.body(AcceptBody.self)
            guard let out = outgoing, out.tid == acc.tid else { return }
            guard let th = keyMaterial(), let key2 = Data(base64URL: acc.key2) else {
                outgoing = nil
                session.send(Self.errorType, ErrorNote(tid: acc.tid, code: "key"))
                onError?(session, acc.tid, "key")
                return
            }
            onAccepted?(session, acc, FilesKeys.derive(th: th, tid: acc.tid, key1: out.key1, key2: key2))
        case Self.declineType:
            let note = try message.body(NoteBody.self)
            if outgoing?.tid == note.tid { outgoing = nil }
            onDeclined?(session, note.tid, note.why)
        case Self.doneType:
            let done = try message.body(DoneBody.self)
            if outgoing?.tid == done.tid { outgoing = nil }
            onDone?(session, done.tid)
        case Self.cancelType:
            let note = try message.body(NoteBody.self)
            abort(note.tid)
            onCancelled?(session, note.tid)
        case Self.errorType:
            let note = try message.body(ErrorNote.self)
            abort(note.tid)
            onError?(session, note.tid, note.code)
        default:
            break
        }
    }

    private func onOfferPage(_ message: ExtensionMessage, on session: ExtensionSession) throws {
        let page = try message.body(OfferPage.self)
        if let active = outgoing?.tid ?? incomingTid, active != page.tid {
            if page.page == page.pages - 1 { session.send(Self.errorType, ErrorNote(tid: page.tid, code: "busy")) }
            return
        }
        var pending: PendingOffer
        if let existing = pendingOffer, existing.tid == page.tid {
            pending = existing
        } else {
            pending = PendingOffer(tid: page.tid, pages: page.pages)
        }
        guard pending.pages == page.pages else { return }
        pending.got[page.page] = page.files
        if !page.key1.isEmpty { pending.key1 = Data(base64URL: page.key1) }
        if page.page == page.pages - 1 { pending.lastMessage = message }
        pendingOffer = pending
        guard pending.got.count == pending.pages, let key1 = pending.key1, let last = pending.lastMessage else { return }
        let entries = (0..<pending.pages).flatMap { pending.got[$0] ?? [] }
        try Self.checkEntries(entries)
        onOffer?(session, Offer(tid: page.tid, key1: key1, entries: entries), last)
    }
}

/// The one-time transfer keys (§7.10): HKDF-SHA256 with the session's transcript hash as salt — so the keys are
/// bound to this paired session — over both sides' fresh salts, with the transfer id in the info. The link's
/// traffic keys are inputs to nothing here and are never reused. The same derivation as the Kotlin `FilesKeys`.
public enum FilesKeys {
    public struct TransferKeys: Equatable, Sendable {
        /// Seals the sender → receiver frames (and opens the receiver's).
        public var offererToAcceptor: Data
        /// Seals the receiver → sender frames (and opens the sender's).
        public var acceptorToOfferer: Data
        public init(offererToAcceptor: Data, acceptorToOfferer: Data) {
            self.offererToAcceptor = offererToAcceptor; self.acceptorToOfferer = acceptorToOfferer
        }
    }

    public static func derive(th: Data, tid: String, key1: Data, key2: Data) -> TransferKeys {
        var info = Data("tether/1 x.tether.files".utf8)
        info.append(0)
        info.append(Data(base64URL: tid) ?? Data())
        let okm = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: key1 + key2),
                                         salt: th, info: info, outputByteCount: 64)
        let bytes = okm.withUnsafeBytes { Data($0) }
        return TransferKeys(offererToAcceptor: bytes.prefix(32), acceptorToOfferer: Data(bytes.suffix(32)))
    }
}

/// The sealed frame stream of §7.10: frames for the app's TCP engine (stage B) to carry. A frame is a §2 record
/// sealed with the §4 record layer under the transfer keys (channel byte 0); its plaintext is one frame byte and
/// a payload — JSON for HELLO, FILE, ACK and ERR, raw bytes for DATA.
public final class FilesWire {
    public static let hello: UInt8 = 0x01
    public static let file: UInt8 = 0x02
    public static let data: UInt8 = 0x03
    public static let ack: UInt8 = 0x04
    public static let bye: UInt8 = 0x05
    public static let err: UInt8 = 0x06
    public static let chunk = 16 * 1024

    public struct HelloBody: Codable, Equatable, Sendable {
        public var tid: String
        public init(tid: String) { self.tid = tid }
    }
    public struct FileBody: Codable, Equatable, Sendable {
        public var i: Int; public var name: String; public var size: Int64; public var off: Int64
        public init(i: Int, name: String, size: Int64, off: Int64) { self.i = i; self.name = name; self.size = size; self.off = off }
    }
    public struct AckBody: Codable, Equatable, Sendable {
        public var i: Int; public var ok: Bool; public var have: Int64
        public init(i: Int, ok: Bool, have: Int64) { self.i = i; self.ok = ok; self.have = have }
    }
    public struct ErrBody: Codable, Equatable, Sendable {
        public var code: String
        public init(code: String) { self.code = code }
    }

    private let session: SecureSession
    /// [sendKey] seals this side's frames; [recvKey] opens the peer's. The sender takes
    /// (offererToAcceptor, acceptorToOfferer), the receiver the same pair swapped.
    public init(sendKey: Data, recvKey: Data) { session = SecureSession(sendKey: sendKey, recvKey: recvKey) }

    public func sealFrame(_ type: UInt8, _ payload: Data) throws -> Record {
        try session.seal(channel: Proto.channelControl, Data([type]) + payload)
    }
    public func sealJSON<B: Encodable>(_ type: UInt8, _ body: B) throws -> Record {
        try sealFrame(type, JSONEncoder().encode(body))
    }
    /// The next frame off a record; throws on anything that does not open (§7.10: `protocol`).
    public func openFrame(_ record: Record) throws -> (UInt8, Data) {
        let (_, plain) = try session.open(record)
        guard let first = plain.first else { throw SessionError.tooShort }
        return (first, Data(plain.dropFirst()))
    }
}
