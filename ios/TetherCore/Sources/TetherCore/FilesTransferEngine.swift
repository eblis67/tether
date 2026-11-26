#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation

/// The TCP half of `x.tether.files` (protocol §7.10) for the iOS side: the sending and receiving engines over a
/// blocking byte stream, mirroring the Kotlin `FilesSender` / `FilesReceiver` frame for frame. `FilesExtension`
/// negotiates over the sealed session and hands the engine the one-time `FilesKeys.TransferKeys`; the app owns
/// the sockets (`NWConnection` / `NWListener` adapters) and the files behind `FilesDataSource` / `FilesDataStore`.
/// Engines block: run them on a background queue. `cancel()` closes the stream, which ends `run` with an `io`
/// outcome, as the Kotlin engines' `close()` does.

/// A blocking byte stream: one TCP connection. `readSome` returns the next chunk, nil at the end of the stream.
public protocol FilesByteStream: AnyObject {
    func write(_ data: Data) throws
    func readSome() throws -> Data?
    func close()
}

/// Errors a `FilesByteStream` can throw that the engines map to §7.10 codes; anything else maps to `io`,
/// except `SessionError` (a frame that does not open), which maps to `protocol`.
public enum FilesStreamError: Error {
    case timeout
    case closed
}

/// Where offered files are read from (the host's staged picks, the photo library, memory in tests).
public protocol FilesDataSource: AnyObject {
    var entries: [FilesExtension.FileEntry] { get }
    /// A reader of `name`'s bytes starting at `offset`.
    func open(_ name: String, at offset: Int64) throws -> FilesDataReader
}

public protocol FilesDataReader: AnyObject {
    /// Up to `max` bytes; an empty `Data` at the end.
    func readChunk(max: Int) throws -> Data
    func close()
}

/// Where received files are kept, addressed by the offer's names; offsets make resume a store question.
public protocol FilesDataStore: AnyObject {
    /// Bytes already held of `name` (0 when none).
    func length(_ name: String) -> Int64
    /// Up to `max` bytes of `name` starting at `offset`; empty at or past the end.
    func read(_ name: String, at offset: Int64, max: Int) -> Data
    /// Writes `data` at `offset` of `name`, growing it as needed.
    func write(_ name: String, at offset: Int64, data: Data)
}

/// One file's result in a finished (or failed) transfer.
public struct FilesFileOutcome: Equatable, Sendable {
    public var name: String; public var ok: Bool; public var have: Int64
    public init(name: String, ok: Bool, have: Int64) { self.name = name; self.ok = ok; self.have = have }
}

public struct FilesSendOutcome: Sendable {
    public var files: [FilesFileOutcome]; public var error: String?
    public init(files: [FilesFileOutcome], error: String? = nil) { self.files = files; self.error = error }
    public var allOk: Bool { error == nil && files.allSatisfy { $0.ok } }
}

public struct FilesReceiveOutcome: Sendable {
    public var files: [FilesFileOutcome]; public var error: String?
    public init(files: [FilesFileOutcome], error: String? = nil) { self.files = files; self.error = error }
    public var allOk: Bool { error == nil && files.allSatisfy { $0.ok } }
    public var bytes: Int64 { files.reduce(0) { $0 + $1.have } }
}

/// §7.10's error code for an engine failure, the same mapping the Kotlin engines use.
private func filesErrorCode(_ error: Error) -> String {
    switch error {
    case FilesStreamError.timeout: return "timeout"
    case is SessionError: return "protocol"
    default: return "io"
    }
}

/// The sealed frame stream over one connection: §2 records, §4 sealing, a frame byte per plaintext.
private final class FramePump {
    private let wire: FilesWire
    private let stream: FilesByteStream
    private var reader = RecordReader()

    init(wire: FilesWire, stream: FilesByteStream) { self.wire = wire; self.stream = stream }

    func writeFrame(_ type: UInt8, _ payload: Data) throws {
        try stream.write(wire.sealFrame(type, payload).encoded())
    }

    func writeJSON<B: Encodable>(_ type: UInt8, _ body: B) throws {
        try writeFrame(type, JSONEncoder().encode(body))
    }

    /// The next frame, or nil when the peer closed. Throws on anything that does not open (§7.10: `protocol`).
    func readFrame() throws -> (UInt8, Data)? {
        while true {
            if let record = try reader.next() { return try wire.openFrame(record) }
            guard let chunk = try stream.readSome() else { return nil }
            reader.append(chunk)
        }
    }
}

/// Thrown when the peer answers with an ERR frame; the engines report it as `io`, like the Kotlin ones.
private struct FilesPeerError: Error { var code: String }

/// The sending engine of §7.10: sends each file from its resume offset, waits for its ACK, then BYE.
public final class FilesSenderEngine {
    private let keys: FilesKeys.TransferKeys
    private let tid: String
    private let source: FilesDataSource
    private let resume: [String: Int64]
    private let onProgress: ((String, Int64) -> Void)?
    private weak var stream: FilesByteStream?

    /// `resume` is the accept's `have` map: bytes the receiver already holds of each file.
    /// `onProgress` reports (file, position in that file) as bytes go out, on this thread.
    public init(keys: FilesKeys.TransferKeys, tid: String, source: FilesDataSource, resume: [String: Int64],
                onProgress: ((String, Int64) -> Void)? = nil) {
        self.keys = keys; self.tid = tid; self.source = source; self.resume = resume; self.onProgress = onProgress
    }

    public func run(_ stream: FilesByteStream) -> FilesSendOutcome {
        self.stream = stream
        var outcomes: [FilesFileOutcome] = []
        do {
            let pump = FramePump(wire: FilesWire(sendKey: keys.offererToAcceptor, recvKey: keys.acceptorToOfferer), stream: stream)
            try pump.writeJSON(FilesWire.hello, FilesWire.HelloBody(tid: tid))
            for (i, e) in source.entries.enumerated() {
                let off = min(max(resume[e.name] ?? 0, 0), e.size)
                try pump.writeJSON(FilesWire.file, FilesWire.FileBody(i: i, name: e.name, size: e.size, off: off))
                var left = e.size - off
                var pos = off
                let reader = try source.open(e.name, at: off)
                defer { reader.close() }
                while left > 0 {
                    let chunk = try reader.readChunk(max: Int(min(Int64(FilesWire.chunk), left)))
                    if chunk.isEmpty { return FilesSendOutcome(files: outcomes, error: "io") }   // source ended early
                    try pump.writeFrame(FilesWire.data, chunk)
                    left -= Int64(chunk.count)
                    pos += Int64(chunk.count)
                    onProgress?(e.name, pos)
                }
                guard let ack = try awaitAck(pump, i) else { return FilesSendOutcome(files: outcomes, error: "io") }
                outcomes.append(FilesFileOutcome(name: e.name, ok: ack.ok, have: ack.have))
            }
            try pump.writeFrame(FilesWire.bye, Data())
            return FilesSendOutcome(files: outcomes)
        } catch {
            return FilesSendOutcome(files: outcomes, error: filesErrorCode(error))
        }
    }

    /// Reads until file `i`'s ACK. Nil when the connection died; throws on an ERR frame.
    private func awaitAck(_ pump: FramePump, _ i: Int) throws -> FilesWire.AckBody? {
        while true {
            guard let (type, payload) = try pump.readFrame() else { return nil }
            switch type {
            case FilesWire.ack:
                let ack = try JSONDecoder().decode(FilesWire.AckBody.self, from: payload)
                if ack.i == i { return ack }
            case FilesWire.err:
                throw FilesPeerError(code: (try? JSONDecoder().decode(FilesWire.ErrBody.self, from: payload))?.code ?? "io")
            default:
                throw FilesPeerError(code: "protocol")
            }
        }
    }

    public func cancel() { stream?.close() }
}

/// The receiving engine of §7.10: files written at their offsets, digests checked per file, ACK per file.
public final class FilesReceiverEngine {
    private let keys: FilesKeys.TransferKeys
    private let tid: String
    private let store: FilesDataStore
    private let expected: [FilesExtension.FileEntry]
    private let onProgress: ((String, Int64) -> Void)?
    private weak var stream: FilesByteStream?

    /// `onProgress` reports (file, bytes held of it) as data lands, on this thread.
    public init(keys: FilesKeys.TransferKeys, tid: String, store: FilesDataStore,
                expected: [FilesExtension.FileEntry], onProgress: ((String, Int64) -> Void)? = nil) {
        self.keys = keys; self.tid = tid; self.store = store; self.expected = expected; self.onProgress = onProgress
    }

    public func run(_ stream: FilesByteStream) -> FilesReceiveOutcome {
        self.stream = stream
        var outcomes: [FilesFileOutcome] = []
        do {
            let pump = FramePump(wire: FilesWire(sendKey: keys.acceptorToOfferer, recvKey: keys.offererToAcceptor), stream: stream)
            guard let hello = try pump.readFrame() else { return FilesReceiveOutcome(files: outcomes, error: "io") }
            guard hello.0 == FilesWire.hello,
                  (try? JSONDecoder().decode(FilesWire.HelloBody.self, from: hello.1))?.tid == tid
            else { return FilesReceiveOutcome(files: outcomes, error: "protocol") }
            var current: FilesExtension.FileEntry?
            var digest: SHA256?
            var pos: Int64 = 0
            var left: Int64 = 0
            while true {
                guard let (type, payload) = try pump.readFrame() else {
                    return FilesReceiveOutcome(files: outcomes, error: outcomes.count == expected.count ? nil : "io")
                }
                switch type {
                case FilesWire.file:
                    let b = try JSONDecoder().decode(FilesWire.FileBody.self, from: payload)
                    guard let e = expected.indices.contains(b.i) ? expected[b.i] : nil,
                          b.name == e.name, b.size == e.size
                    else { return FilesReceiveOutcome(files: outcomes, error: "protocol") }
                    let off = b.off
                    guard off == store.length(e.name) else {
                        try pump.writeJSON(FilesWire.err, FilesWire.ErrBody(code: "offset"))
                        return FilesReceiveOutcome(files: outcomes, error: "offset")
                    }
                    current = e
                    digest = seedDigest(e.name, upTo: off)
                    pos = off
                    left = e.size - off
                    if left == 0 {
                        guard try finishFile(pump, &outcomes, e, &digest, have: pos) else { return FilesReceiveOutcome(files: outcomes) }
                        current = nil; digest = nil
                    }
                case FilesWire.data:
                    guard let e = current, digest != nil else { return FilesReceiveOutcome(files: outcomes, error: "protocol") }
                    guard Int64(payload.count) <= left else { return FilesReceiveOutcome(files: outcomes, error: "protocol") }
                    store.write(e.name, at: pos, data: payload)
                    digest!.update(data: payload)
                    pos += Int64(payload.count)
                    left -= Int64(payload.count)
                    onProgress?(e.name, pos)
                    if left == 0 {
                        guard try finishFile(pump, &outcomes, e, &digest, have: pos) else { return FilesReceiveOutcome(files: outcomes) }
                        current = nil; digest = nil
                    }
                case FilesWire.bye:
                    return FilesReceiveOutcome(files: outcomes)
                case FilesWire.err:
                    return FilesReceiveOutcome(files: outcomes, error: "io")
                default:
                    return FilesReceiveOutcome(files: outcomes, error: "protocol")
                }
            }
        } catch {
            return FilesReceiveOutcome(files: outcomes, error: filesErrorCode(error))
        }
    }

    /// The digest of what is already stored, so a resumed file is checked whole.
    private func seedDigest(_ name: String, upTo: Int64) -> SHA256 {
        var d = SHA256()
        var at: Int64 = 0
        while at < upTo {
            let chunk = store.read(name, at: at, max: FilesWire.chunk)
            if chunk.isEmpty { break }
            d.update(data: chunk)
            at += Int64(chunk.count)
        }
        return d
    }

    /// Checks the finished file against the offer and ACKs it; false when the digest differed (the sender stops).
    private func finishFile(_ pump: FramePump, _ outcomes: inout [FilesFileOutcome],
                            _ e: FilesExtension.FileEntry, _ digest: inout SHA256?, have: Int64) throws -> Bool {
        let ok = Data(digest!.finalize()).base64URL() == e.sha256
        outcomes.append(FilesFileOutcome(name: e.name, ok: ok, have: have))
        let index = expected.firstIndex(of: e) ?? 0
        try pump.writeJSON(FilesWire.ack, FilesWire.AckBody(i: index, ok: ok, have: have))
        return ok
    }

    public func cancel() { stream?.close() }
}
