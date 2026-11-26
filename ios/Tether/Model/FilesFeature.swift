import Foundation
import Network
import TetherCore
import UserNotifications
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// `x.tether.files` in the app (protocol §7.10, docs/FILES-TRANSFER.md): the stage-B host for `FilesExtension`.
/// The sealed session carries the negotiation; the bytes move over a short-lived TCP connection on the same
/// Wi-Fi (or hotspot) as `NWConnection` / `NWListener`, sealed with the one-time keys the extension derives.
/// Mirrors the Android apps' FilesFeature: an offer is never accepted on its own — the owner taps Receive —
/// progress is one state on the model, and received files land in the app's own folder.

/// What the Link tab shows of an incoming offer.
struct FilesIncomingOffer {
    var from: String
    var entries: [FilesExtension.FileEntry]
    var totalBytes: Int64
}

/// The one running transfer, for the Link tab's progress row.
enum FilesPhase: Equatable {
    case sending(name: String, fraction: Double)
    case receiving(name: String, fraction: Double)
}

/// One picked file or photo to send: a security-scoped URL (Files) or in-memory data (Photos).
struct FilesPick {
    var name: String
    var url: URL?
    var data: Data?
}

// MARK: - the blocking stream over NWConnection

/// A `FilesByteStream` over one `NWConnection`: writes wait for the system to take the bytes, reads hand out
/// what arrived; 30 s without progress is a timeout, like the Android engines' socket timeout.
final class NWFilesStream: FilesByteStream {
    private let conn: NWConnection
    private let queue = DispatchQueue(label: "tether.files.conn")
    private let cond = NSCondition()
    private var buffer = Data()
    private var ended = false
    private var failure: Error?
    private var closedFlag = false

    init(_ conn: NWConnection) { self.conn = conn }

    /// Starts the connection and waits (up to `timeout`) for it to be ready; then the receive loop runs.
    func open(timeout: TimeInterval) -> Bool {
        let sem = DispatchSemaphore(value: 0)
        var ok = false
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: ok = true; sem.signal()
            case .failed(let e): self?.finish(error: e); sem.signal()
            case .cancelled: sem.signal()
            default: break
            }
        }
        conn.start(queue: queue)
        if sem.wait(timeout: .now() + timeout) == .timedOut { conn.cancel(); return false }
        guard ok else { return false }
        conn.stateUpdateHandler = { [weak self] state in
            if case .failed(let e) = state { self?.finish(error: e) }
        }
        receiveMore()
        return true
    }

    private func receiveMore() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                cond.lock(); buffer.append(data); cond.broadcast(); cond.unlock()
            }
            if let error { finish(error: error); return }
            if isComplete { finish(error: nil); return }
            receiveMore()
        }
    }

    private func finish(error: Error?) {
        cond.lock()
        if error != nil, failure == nil { failure = error }
        ended = true
        cond.broadcast()
        cond.unlock()
    }

    func write(_ data: Data) throws {
        cond.lock(); let closed = closedFlag; cond.unlock()
        if closed { throw FilesStreamError.closed }
        let sem = DispatchSemaphore(value: 0)
        var writeError: NWError?
        conn.send(content: data, completion: .contentProcessed { error in writeError = error; sem.signal() })
        if sem.wait(timeout: .now() + 30) == .timedOut { throw FilesStreamError.timeout }
        if let writeError { throw writeError }
    }

    func readSome() throws -> Data? {
        cond.lock(); defer { cond.unlock() }
        let deadline = Date().addingTimeInterval(30)
        while buffer.isEmpty {
            if let failure { throw failure }
            if ended { return nil }
            if !cond.wait(until: deadline) { throw FilesStreamError.timeout }
        }
        let out = buffer
        buffer = Data()
        return out
    }

    func close() {
        cond.lock(); closedFlag = true; cond.unlock()
        conn.cancel()
        finish(error: nil)
    }
}

/// Listens on an ephemeral TCP port for the one transfer connection.
final class FilesListener {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "tether.files.listener")
    private let cond = NSCondition()
    private var pending: NWConnection?
    private var failed = false

    init() throws { listener = try NWListener(using: .tcp, on: .any) }

    var port: Int { Int(listener.port?.rawValue ?? 0) }

    func start() throws {
        let sem = DispatchSemaphore(value: 0)
        var startError: NWError?
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready: sem.signal()
            case .failed(let e): startError = e; sem.signal()
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] c in
            guard let self else { c.cancel(); return }
            cond.lock()
            if pending == nil, !failed { pending = c; cond.broadcast() } else { c.cancel() }
            cond.unlock()
        }
        listener.start(queue: queue)
        if sem.wait(timeout: .now() + 5) == .timedOut { listener.cancel(); throw FilesStreamError.timeout }
        if let startError { throw startError }
    }

    /// The sender's connection, opened; nil when none arrives in time.
    func accept(timeout: TimeInterval) -> NWFilesStream? {
        cond.lock()
        let deadline = Date().addingTimeInterval(timeout)
        while pending == nil, !failed {
            if !cond.wait(until: deadline) { break }
        }
        let c = pending; pending = nil
        cond.unlock()
        guard let c else { return nil }
        let stream = NWFilesStream(c)
        return stream.open(timeout: 15) ? stream : nil
    }

    func close() {
        cond.lock(); failed = true; cond.broadcast(); cond.unlock()
        listener.cancel()
    }
}

/// This iPhone's address on the local network: the Wi-Fi interface first, then the hotspot bridge — the
/// address the Android phone connects back to. Nil when there is no local network.
func localIPv4Address() -> String? {
    var head: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&head) == 0, let head else { return nil }
    defer { freeifaddrs(head) }
    var found: [String: String] = [:]
    var order: [String] = []
    var ptr: UnsafeMutablePointer<ifaddrs>? = head
    while let ifa = ptr {
        ptr = ifa.pointee.ifa_next
        guard let addr = ifa.pointee.ifa_addr, Int32(addr.pointee.sa_family) == AF_INET else { continue }
        let name = String(cString: ifa.pointee.ifa_name)
        guard name != "lo0" else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
        let ip = String(cString: host)
        if !ip.isEmpty, !ip.hasPrefix("169.254."), found[name] == nil { found[name] = ip; order.append(name) }
    }
    for name in ["en0", "bridge100", "en1"] { if let ip = found[name] { return ip } }
    return order.first.flatMap { found[$0] }
}

// MARK: - the stores behind the engines

/// Received files, in the app's Application Support folder; offsets make resume a store question.
final class ReceivedFilesStore: FilesDataStore {
    let dir: URL

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = base.appendingPathComponent("TetherReceived", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private func url(_ name: String) -> URL { dir.appendingPathComponent(name) }   // names are validated basenames

    func length(_ name: String) -> Int64 {
        let attrs = try? FileManager.default.attributesOfItem(atPath: url(name).path)
        return (attrs?[.size] as? NSNumber)?.int64Value ?? 0
    }

    func read(_ name: String, at offset: Int64, max: Int) -> Data {
        guard let handle = try? FileHandle(forReadingFrom: url(name)) else { return Data() }
        defer { try? handle.close() }
        guard (try? handle.seek(toOffset: UInt64(offset))) != nil else { return Data() }
        return (try? handle.read(upToCount: max)) ?? Data()
    }

    func write(_ name: String, at offset: Int64, data: Data) {
        let u = url(name)
        if !FileManager.default.fileExists(atPath: u.path) { FileManager.default.createFile(atPath: u.path, contents: nil) }
        guard let handle = try? FileHandle(forWritingTo: u) else { return }
        defer { try? handle.close() }
        guard (try? handle.seek(toOffset: UInt64(offset))) != nil else { return }
        try? handle.write(contentsOf: data)
    }

    func names() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).sorted()
    }
}

/// The picked files, staged in a cache folder so digests and reads see plain files.
final class StagedFilesSource: FilesDataSource {
    let entries: [FilesExtension.FileEntry]
    private let urls: [String: URL]

    init(entries: [FilesExtension.FileEntry], urls: [String: URL]) { self.entries = entries; self.urls = urls }

    func open(_ name: String, at offset: Int64) throws -> FilesDataReader {
        guard let url = urls[name] else { throw FilesStreamError.closed }
        return try FileReader(url: url, offset: offset)
    }

    private final class FileReader: FilesDataReader {
        private let handle: FileHandle
        init(url: URL, offset: Int64) throws {
            handle = try FileHandle(forReadingFrom: url)
            try handle.seek(toOffset: UInt64(offset))
        }
        func readChunk(max: Int) throws -> Data { try handle.read(upToCount: max) ?? Data() }
        func close() { try? handle.close() }
    }
}

private func sha256(of url: URL) throws -> String {
    var h = SHA256()
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    while let chunk = try handle.read(upToCount: 1 << 16), !chunk.isEmpty { h.update(data: chunk) }
    return Data(h.finalize()).base64URL()
}

// MARK: - the feature

@MainActor
final class FilesFeature {
    private unowned let model: AppModel
    let store = ReceivedFilesStore()
    private let work = DispatchQueue(label: "tether.files", qos: .userInitiated)

    private var pending: (session: ExtensionSession, offer: FilesExtension.Offer, message: ExtensionMessage)?
    private var sender: FilesSenderEngine?
    private var receiver: FilesReceiverEngine?
    private var listener: FilesListener?
    private var stagingDir: URL?
    private var sendingTid: String?
    private var receivingTid: String?
    private var sendEntries: [FilesExtension.FileEntry] = []
    private var recvEntries: [FilesExtension.FileEntry] = []

    init(model: AppModel) { self.model = model }

    private var ext: FilesExtension? { model.filesExt }

    /// Sets the extension's callbacks (called once, from `AppModel.registerExtensions`). They arrive on the
    /// link thread; everything here hops to the main actor before touching state.
    func wire(_ ext: FilesExtension) {
        // Browsing answers with the received folder's contents, like the Android apps; no app has a
        // browsing UI yet, so offers are always of locally picked files.
        ext.lister = { [store] _ in
            store.names().map { FilesExtension.DirEntry(name: $0, size: store.length($0), kind: "file") }
        }
        ext.onOffer = { [weak self] session, offer, message in
            Task { @MainActor [weak self] in self?.offerArrived(session, offer, message) }
        }
        ext.onAccepted = { [weak self] session, accept, keys in
            Task { @MainActor [weak self] in self?.accepted(session, accept, keys) }
        }
        ext.onDone = { [weak self] _, tid in Task { @MainActor [weak self] in self?.done(tid) } }
        ext.onDeclined = { [weak self] _, tid, _ in Task { @MainActor [weak self] in self?.declined(tid) } }
        ext.onCancelled = { [weak self] _, tid in Task { @MainActor [weak self] in self?.cancelled(tid) } }
        ext.onError = { [weak self] _, tid, code in Task { @MainActor [weak self] in self?.errored(tid, code) } }
        refreshReceived()
    }

    func refreshReceived() { model.filesReceivedNames = store.names() }

    // MARK: sending

    /// Stages the picks, digests them, and offers them. The transfer itself starts when the phone accepts.
    func send(_ picks: [FilesPick]) {
        guard let ext, let session = model.extensions.session(for: ext) else { return }
        guard model.filesPhase == nil, model.filesIncoming == nil, !picks.isEmpty else { return }
        work.async { [weak self] in
            guard let self else { return }
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("TetherOutgoing-\(UUID().uuidString)", isDirectory: true)
            do {
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                var entries: [FilesExtension.FileEntry] = []
                var urls: [String: URL] = [:]
                var used = Set<String>()
                for pick in picks {
                    let name = Self.unique(pick.name, used: &used)
                    let target = dir.appendingPathComponent(name)
                    if let url = pick.url {
                        let scoped = url.startAccessingSecurityScopedResource()
                        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                        try FileManager.default.copyItem(at: url, to: target)
                    } else if let data = pick.data {
                        try data.write(to: target)
                    } else { continue }
                    let size = (try FileManager.default.attributesOfItem(atPath: target.path)[.size] as? NSNumber)?.int64Value ?? 0
                    entries.append(FilesExtension.FileEntry(name: name, size: size, sha256: try sha256(of: target)))
                    urls[name] = target
                }
                guard !entries.isEmpty else { throw FilesStreamError.closed }
                let source = StagedFilesSource(entries: entries, urls: urls)
                Task { @MainActor [weak self] in self?.offerStaged(session, ext, source, dir) }
            } catch {
                Task { @MainActor [weak self] in
                    self?.model.filesNote = String(localized: "Transfer failed: \(error.localizedDescription)")
                }
            }
        }
    }

    private static func unique(_ name: String, used: inout Set<String>) -> String {
        var candidate = name
        var n = 2
        while used.contains(candidate) {
            let dot = name.lastIndex(of: ".")
            if let dot, dot != name.startIndex {
                candidate = "\(name[..<dot]) \(n)\(name[dot...])"
            } else {
                candidate = "\(name) \(n)"
            }
            n += 1
        }
        used.insert(candidate)
        return candidate
    }

    private func offerStaged(_ session: ExtensionSession, _ ext: FilesExtension, _ source: StagedFilesSource, _ dir: URL) {
        do {
            guard let tid = try ext.offer(session, entries: source.entries) else {
                model.filesNote = String(localized: "Transfer failed: busy")
                try? FileManager.default.removeItem(at: dir)
                return
            }
            sendingTid = tid
            sendEntries = source.entries
            stagingDir = dir
            model.filesNote = nil
            model.filesPhase = .sending(name: source.entries[0].name, fraction: 0)
            // The sending engine runs when the accept arrives; keep the source with the tid.
            pendingSource = source
        } catch {
            model.filesNote = String(localized: "Transfer failed: \(error.localizedDescription)")
            try? FileManager.default.removeItem(at: dir)
        }
    }

    private var pendingSource: StagedFilesSource?

    /// The phone accepted: connect and run the sending engine.
    private func accepted(_ session: ExtensionSession, _ accept: FilesExtension.AcceptBody, _ keys: FilesKeys.TransferKeys) {
        guard let tid = sendingTid, accept.tid == tid, let source = pendingSource,
              (1...65535).contains(accept.port), let port = NWEndpoint.Port(rawValue: UInt16(accept.port)) else { return }
        let resume = Dictionary(uniqueKeysWithValues: accept.have.map { ($0.name, $0.have) })
        let engine = FilesSenderEngine(keys: keys, tid: tid, source: source, resume: resume) { [weak self] name, pos in
            Task { @MainActor [weak self] in self?.progress(sending: true, name: name, pos: pos) }
        }
        sender = engine
        work.async { [weak self] in
            let conn = NWConnection(host: NWEndpoint.Host(accept.host), port: port, using: .tcp)
            let stream = NWFilesStream(conn)
            let outcome: FilesSendOutcome
            if stream.open(timeout: 15) {
                outcome = engine.run(stream)
            } else {
                outcome = FilesSendOutcome(files: [], error: "io")
            }
            Task { @MainActor [weak self] in self?.sendFinished(session, tid, outcome) }
        }
    }

    private func sendFinished(_ session: ExtensionSession, _ tid: String, _ outcome: FilesSendOutcome) {
        guard sendingTid == tid else { return }
        sender = nil
        if let error = outcome.error {
            ext?.sendError(session, tid: tid, code: error)
            finishSend(note: String(localized: "Transfer failed: \(error)"))
        } else if outcome.allOk {
            // The phase stays up: the phone's `done` closes it (or its error does).
        } else {
            ext?.sendError(session, tid: tid, code: "checksum")
            finishSend(note: String(localized: "Transfer failed: checksum"))
        }
    }

    private func finishSend(note: String?) {
        sendingTid = nil
        pendingSource = nil
        sendEntries = []
        if let stagingDir { try? FileManager.default.removeItem(at: stagingDir) }
        stagingDir = nil
        model.filesPhase = nil
        model.filesNote = note
    }

    // MARK: receiving

    private func offerArrived(_ session: ExtensionSession, _ offer: FilesExtension.Offer, _ message: ExtensionMessage) {
        pending = (session, offer, message)
        model.filesIncoming = FilesIncomingOffer(
            from: model.peerName, entries: offer.entries,
            totalBytes: offer.entries.reduce(0) { $0 + $1.size })
        if !model.appActive {
            let c = UNMutableNotificationContent()
            c.title = String(localized: "File transfer")
            c.body = String(localized: "\(model.peerName) wants to send \(offer.entries.count) file(s)")
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "files-offer", content: c, trigger: nil))
        }
    }

    /// The owner's Receive tap: listen on the local network and run the receiving engine.
    func accept() {
        guard let ext, let pending else { return }
        guard let host = localIPv4Address() else {
            model.filesNote = String(localized: "No local network found: put both devices on the same Wi-Fi, or connect one to the other's hotspot, then try again.")
            return
        }
        let have = pending.offer.entries.map {
            FilesExtension.HaveEntry(name: $0.name, have: min(store.length($0.name), $0.size))
        }
        let listener = try? FilesListener()
        guard let listener, (try? listener.start()) != nil, listener.port > 0 else {
            model.filesNote = String(localized: "Transfer failed: io")
            return
        }
        guard let keys = ext.accept(pending.session, offer: pending.offer, replyTo: pending.message,
                                    host: host, port: listener.port, have: have) else {
            listener.close()
            model.filesNote = String(localized: "Transfer failed: key")
            return
        }
        self.listener = listener
        receivingTid = pending.offer.tid
        recvEntries = pending.offer.entries
        let session = pending.session
        let tid = pending.offer.tid
        let entries = pending.offer.entries
        self.pending = nil
        model.filesIncoming = nil
        model.filesNote = nil
        model.filesPhase = .receiving(name: entries.first?.name ?? "", fraction: 0)
        let engine = FilesReceiverEngine(keys: keys, tid: tid, store: store, expected: entries) { [weak self] name, pos in
            Task { @MainActor [weak self] in self?.progress(sending: false, name: name, pos: pos) }
        }
        receiver = engine
        work.async { [weak self] in
            let outcome: FilesReceiveOutcome
            if let stream = listener.accept(timeout: 120) {
                outcome = engine.run(stream)
            } else {
                outcome = FilesReceiveOutcome(files: [], error: "timeout")
            }
            Task { @MainActor [weak self] in self?.receiveFinished(session, tid, outcome) }
        }
    }

    private func receiveFinished(_ session: ExtensionSession, _ tid: String, _ outcome: FilesReceiveOutcome) {
        guard receivingTid == tid else { return }
        receiver = nil
        listener?.close(); listener = nil
        receivingTid = nil
        recvEntries = []
        model.filesPhase = nil
        if outcome.allOk {
            refreshReceived()
            ext?.sendDone(session, tid: tid, files: outcome.files.count, bytes: outcome.bytes)
            model.filesNote = String(localized: "Received.")
        } else {
            if let error = outcome.error { ext?.sendError(session, tid: tid, code: error) }
            model.filesNote = String(localized: "Transfer failed: \(outcome.error ?? "checksum")")
        }
    }

    func decline() {
        guard let pending else { return }
        ext?.decline(pending.session, offer: pending.offer, replyTo: pending.message)
        self.pending = nil
        model.filesIncoming = nil
    }

    // MARK: shared endings

    /// The owner's Cancel tap, on whichever side of the open transfer we are.
    func cancel() {
        sender?.cancel(); sender = nil
        receiver?.cancel(); receiver = nil
        listener?.close(); listener = nil
        let tid = sendingTid ?? receivingTid
        if let tid, let ext, let session = model.extensions.session(for: ext) { ext.cancel(session, tid: tid) }
        if sendingTid != nil { finishSend(note: String(localized: "Transfer cancelled.")) }
        receivingTid = nil
        recvEntries = []
        model.filesPhase = nil
        model.filesNote = String(localized: "Transfer cancelled.")
    }

    private func done(_ tid: String) {
        guard sendingTid == tid else { return }
        finishSend(note: String(localized: "Sent and confirmed by the Android phone."))
    }

    private func declined(_ tid: String) {
        guard sendingTid == tid else { return }
        sender?.cancel(); sender = nil
        finishSend(note: String(localized: "Declined."))
    }

    private func cancelled(_ tid: String) {
        if receivingTid == tid {
            receiver?.cancel(); receiver = nil
            listener?.close(); listener = nil
            receivingTid = nil
            recvEntries = []
            model.filesPhase = nil
            model.filesNote = String(localized: "Transfer cancelled.")
        } else if sendingTid == tid {
            sender?.cancel(); sender = nil
            finishSend(note: String(localized: "Transfer cancelled."))
        }
        if pending?.offer.tid == tid {
            pending = nil
            model.filesIncoming = nil
        }
    }

    private func errored(_ tid: String?, _ code: String) {
        if let tid, tid != sendingTid, tid != receivingTid { return }
        sender?.cancel(); sender = nil
        receiver?.cancel(); receiver = nil
        listener?.close(); listener = nil
        if sendingTid != nil { finishSend(note: String(localized: "Transfer failed: \(code)")) }
        receivingTid = nil
        recvEntries = []
        model.filesPhase = nil
        model.filesNote = String(localized: "Transfer failed: \(code)")
    }

    private func progress(sending: Bool, name: String, pos: Int64) {
        let entries = sending ? sendEntries : recvEntries
        let total = entries.reduce(0) { $0 + $1.size }
        var base: Int64 = 0
        for e in entries {
            if e.name == name { break }
            base += e.size
        }
        let fraction = total > 0 ? Double(base + pos) / Double(total) : 1
        model.filesPhase = sending ? .sending(name: name, fraction: fraction) : .receiving(name: name, fraction: fraction)
    }
}
