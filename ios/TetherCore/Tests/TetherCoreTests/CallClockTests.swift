import XCTest
@testable import TetherCore

final class CallClockTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)   // fixed "now" for every case
    private var nowMs: Int64 { Int64(now.timeIntervalSince1970 * 1000) }

    func testNoSinceMeansNoConnectedDate() {
        XCTAssertNil(CallClock.connectedAt(sinceMs: nil, peerOffsetMs: 0, now: now))
    }

    func testSameClocksPassTheInstantThrough() {
        let since = nowMs - 90_000   // connected 90 s ago
        XCTAssertEqual(CallClock.connectedAt(sinceMs: since, peerOffsetMs: 0, now: now),
                       Date(timeIntervalSince1970: Double(since) / 1000))
        XCTAssertEqual(CallClock.connectedAt(sinceMs: since, peerOffsetMs: nil, now: now),
                       Date(timeIntervalSince1970: Double(since) / 1000))
    }

    func testAPeerClockAnHourAheadDoesNotPutTheCallInTheFuture() {
        // The Android phone's clock is an hour ahead: its `since` for "connected just now" reads an
        // hour in our future. Shifted by the offset it lands on now, not an hour ahead (which used to
        // give CallKit a connected date in the future and a timer counting from below zero).
        let offset: Int64 = 3_600_000
        let since = nowMs + offset
        XCTAssertEqual(CallClock.connectedAt(sinceMs: since, peerOffsetMs: offset, now: now), now)
    }

    func testAPeerClockBehindShiftsTheInstantLater() {
        // Peer 5 min behind: a call connected 60 s ago by its clock reads 6 min old unshifted.
        let offset: Int64 = -300_000
        let since = nowMs + offset - 60_000
        XCTAssertEqual(CallClock.connectedAt(sinceMs: since, peerOffsetMs: offset, now: now),
                       now.addingTimeInterval(-60))
    }

    func testAFutureSinceIsClampedToNowEvenWithoutAnOffset() {
        XCTAssertEqual(CallClock.connectedAt(sinceMs: nowMs + 5_000, peerOffsetMs: nil, now: now), now)
    }
}
