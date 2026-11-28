import XCTest
@testable import TetherCore

final class CallGuardTests: XCTestCase {
    func testNewCallAfterAnEndedOneIsNeverBlocked() {
        var g = HangUpGuard()
        g.hungUp("aaaa1111", at: Date())
        XCTAssertEqual(g.check(cid: "bbbb2222", ended: false), .pass)     // the next call rings / dials normally
        XCTAssertEqual(g.check(cid: "bbbb2222", ended: true), .pass)
    }

    func testLateStatesForAHungUpCallAreDropped() {
        var g = HangUpGuard()
        let t = Date()
        g.hungUp("c1", at: t)
        XCTAssertEqual(g.check(cid: "c1", ended: false, now: t.addingTimeInterval(0.5)), .drop)
        XCTAssertEqual(g.check(cid: "c1", ended: false, now: t.addingTimeInterval(2.5)), .dropAndResend)
        XCTAssertEqual(g.check(cid: "c1", ended: false, now: t.addingTimeInterval(3)), .drop)   // re-armed
        XCTAssertEqual(g.check(cid: "c1", ended: true, now: t.addingTimeInterval(4)), .pass)
        XCTAssertFalse(g.contains("c1"))
        XCTAssertEqual(g.check(cid: "c1", ended: false, now: t.addingTimeInterval(5)), .pass)
    }

    func testLinkDropResetsEverything() {
        var g = HangUpGuard()
        g.hungUp("c1"); g.reset()
        XCTAssertEqual(g.check(cid: "c1", ended: false), .pass)
    }

    func testNeverGivesUpWhileTheCallIsStillLive() {
        // A hang-up lost to a dead link must keep being re-asked however long ago it was:
        // the real call (and its audio) is still running on the Android phone.
        var g = HangUpGuard()
        let t = Date()
        g.hungUp("c1", at: t)
        XCTAssertEqual(g.check(cid: "c1", ended: false, now: t.addingTimeInterval(45)), .dropAndResend)
        XCTAssertEqual(g.check(cid: "c1", ended: false, now: t.addingTimeInterval(600)), .dropAndResend)
        XCTAssertTrue(g.contains("c1"))
        g.forget("c1")   // a call list without it: over there too, stop guarding
        XCTAssertEqual(g.check(cid: "c1", ended: false, now: t.addingTimeInterval(601)), .pass)
    }

    func testHungUpNumberMatchesTheSameCallUnderANewId() {
        var g = HangUpGuard()
        let t = Date()
        g.hungUp("old-id", number: "+98 912 345 6789", at: t)
        XCTAssertTrue(g.matchesHungUpNumber("09123456789"))
        XCTAssertTrue(g.matchesHungUpNumber("+989123456789"))
        XCTAssertFalse(g.matchesHungUpNumber("09120000000"))
        g.forget("old-id")
        XCTAssertFalse(g.matchesHungUpNumber("09123456789"))
    }
}
