#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation
import XCTest
@testable import TetherCore

private func sha256B64(_ d: Data) -> String { Data(SHA256.hash(data: d)).base64URL() }

/// `x.tether.files` (§7.10): the Swift TCP engines over an in-memory pipe — the counterpart of the Kotlin
/// engine tests. Whole transfers, resume from the store's truth, and a digest mismatch that must not pass.
final class FilesTransferEngineTests: XCTestCase {
    private let th = Data((0..<32).map { UInt8($0 * 7 + 1) })
    private let tid = Data((0..<16).map { UInt8($0 + 40) }).base64URL()

    private func keys() -> FilesKeys.TransferKeys {
        FilesKeys.derive(th: th, tid: tid, key1: Data(repeating: 3, count: 16), key2: Data(repeating: 9, count: 16))
    }

    private func digest(_ d: Data) -> String { sha256B64(d) }

    // MARK: fixtures

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

        private func deliver(_ data: Data) {
            cond.lock(); inbox.append(data); cond.signal(); cond.unlock()
        }

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

        private func peerClosed() {
            cond.lock(); closed = true; cond.signal(); cond.unlock()
        }
    }

    private final class MemorySource: FilesDataSource {
        let files: [String: Data]
        let digests: [String: String]
        private(set) var openedAt: [String: Int64] = [:]
        init(_ files: [String: Data], digests: [String: String] = [:]) { self.files = files; self.digests = digests }
        var entries: [FilesExtension.FileEntry] {
            files.map { FilesExtension.FileEntry(name: $0.key, size: Int64($0.value.count), sha256: digests[$0.key] ?? sha256B64($0.value)) }
                .sorted { $0.name < $1.name }
        }
        func open(_ name: String, at offset: Int64) throws -> FilesDataReader {
            openedAt[name] = offset
            return Reader(data: files[name]!, at: offset)
        }
        private final class Reader: FilesDataReader {
            private let data: Data; private var pos: Int
            init(data: Data, at offset: Int64) { self.data = data; pos = Int(offset) }
            func readChunk(max: Int) throws -> Data {
                guard pos < data.count else { return Data() }
                let n = min(max, data.count - pos)
                defer { pos += n }
                return data.subdata(in: pos..<(pos + n))
            }
            func close() {}
        }
    }

    private final class MemoryStore: FilesDataStore {
        var data: [String: Data] = [:]
        func length(_ name: String) -> Int64 { Int64(data[name]?.count ?? 0) }
        func read(_ name: String, at offset: Int64, max: Int) -> Data {
            guard let d = data[name], offset < d.count else { return Data() }
            return d.subdata(in: Int(offset)..<min(d.count, Int(offset) + max))
        }
        func write(_ name: String, at offset: Int64, data chunk: Data) {
            var cur = data[name] ?? Data()
            let end = Int(offset) + chunk.count
            if cur.count < end { cur.append(Data(repeating: 0, count: end - cur.count)) }
            cur.replaceSubrange(Int(offset)..<end, with: chunk)
            data[name] = cur
        }
    }

    /// Runs a transfer to completion: the receiver on a background queue, the sender here.
    private func transfer(source: MemorySource, store: MemoryStore, resume: [String: Int64]) -> (FilesSendOutcome, FilesReceiveOutcome) {
        let a = PipeEnd(); let b = PipeEnd(); a.peer = b; b.peer = a
        let receiver = FilesReceiverEngine(keys: keys(), tid: tid, store: store, expected: source.entries)
        let sender = FilesSenderEngine(keys: keys(), tid: tid, source: source, resume: resume)
        let done = DispatchSemaphore(value: 0)
        var received: FilesReceiveOutcome?
        DispatchQueue.global().async { received = receiver.run(b); done.signal() }
        let sent = sender.run(a)
        XCTAssertEqual(done.wait(timeout: .now() + 30), .success, "receiver did not finish")
        return (sent, received!)
    }

    // MARK: tests

    func testWholeTransfer() {
        let big = Data((0..<40_000).map { UInt8($0 % 251) })
        let small = Data("hello tether".utf8)
        let source = MemorySource(["big.bin": big, "small.txt": small, "empty": Data()])
        let store = MemoryStore()
        let (sent, received) = transfer(source: source, store: store, resume: [:])
        XCTAssertNil(sent.error)
        XCTAssertNil(received.error)
        XCTAssertTrue(sent.allOk)
        XCTAssertTrue(received.allOk)
        XCTAssertEqual(store.data["big.bin"], big)
        XCTAssertEqual(store.data["small.txt"], small)
        // A zero-length file verifies and ACKs without a store write (the Kotlin engines behave the same).
        XCTAssertEqual(received.files.first { $0.name == "empty" }?.ok, true)
        XCTAssertEqual(received.files.first { $0.name == "empty" }?.have, 0)
        XCTAssertEqual(received.bytes, Int64(big.count + small.count))
    }

    func testResumeSkipsWhatTheStoreHolds() {
        let big = Data((0..<40_000).map { UInt8($0 % 251) })
        let source = MemorySource(["big.bin": big])
        let store = MemoryStore()
        store.data["big.bin"] = big.prefix(10_000)   // an earlier, interrupted transfer
        let (sent, received) = transfer(source: source, store: store, resume: ["big.bin": 10_000])
        XCTAssertTrue(sent.allOk)
        XCTAssertTrue(received.allOk)
        XCTAssertEqual(source.openedAt["big.bin"], 10_000, "the sender must start where the receiver stopped")
        XCTAssertEqual(store.data["big.bin"], big)
    }

    func testChecksumMismatchIsNotReceived() {
        let bytes = Data("the real bytes".utf8)
        let source = MemorySource(["f.bin": bytes], digests: ["f.bin": digest(Data("other bytes".utf8))])
        let store = MemoryStore()
        let (sent, received) = transfer(source: source, store: store, resume: [:])
        XCTAssertEqual(received.files.first?.ok, false)
        XCTAssertFalse(received.allOk)
        XCTAssertEqual(sent.files.first?.ok, false, "the ACK tells the sender the file did not verify")
    }

    func testWrongTidIsProtocolError() {
        let a = PipeEnd(); let b = PipeEnd(); a.peer = b; b.peer = a
        let otherTid = Data((0..<16).map { UInt8($0 + 90) }).base64URL()
        let receiver = FilesReceiverEngine(keys: keys(), tid: tid, store: MemoryStore(),
                                           expected: [FilesExtension.FileEntry(name: "f", size: 0, sha256: digest(Data()))])
        let sender = FilesSenderEngine(keys: keys(), tid: otherTid, source: MemorySource(["f": Data()]), resume: [:])
        let done = DispatchSemaphore(value: 0)
        var received: FilesReceiveOutcome?
        DispatchQueue.global().async { received = receiver.run(b); done.signal() }
        let sentDone = DispatchSemaphore(value: 0)
        DispatchQueue.global().async { _ = sender.run(a); sentDone.signal() }
        XCTAssertEqual(done.wait(timeout: .now() + 30), .success)
        XCTAssertEqual(received?.error, "protocol")
        a.close(); b.close()   // the receiver walked away: the connection going down unblocks the sender
        XCTAssertEqual(sentDone.wait(timeout: .now() + 30), .success)
    }
}
