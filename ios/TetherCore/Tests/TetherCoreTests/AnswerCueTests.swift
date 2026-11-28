import XCTest
@_spi(TetherApp) @testable import TetherCore

final class AnswerCueTests: XCTestCase {
    func testFiresWhenAnOutgoingCallPlacedHereGoesActive() {
        XCTAssertTrue(AnswerCue.shouldFire(placedHere: true, previous: "dialing", state: "active", alreadyFired: false))
        XCTAssertTrue(AnswerCue.shouldFire(placedHere: true, previous: "connecting", state: "active", alreadyFired: false))
    }

    func testOnlyOncePerCall() {
        XCTAssertFalse(AnswerCue.shouldFire(placedHere: true, previous: "dialing", state: "active", alreadyFired: true))
    }

    func testNotForCallsPlacedElsewhere() {
        XCTAssertFalse(AnswerCue.shouldFire(placedHere: false, previous: "dialing", state: "active", alreadyFired: false))
    }

    func testOtherTransitionsStaySilent() {
        XCTAssertFalse(AnswerCue.shouldFire(placedHere: true, previous: nil, state: "active", alreadyFired: false))
        XCTAssertFalse(AnswerCue.shouldFire(placedHere: true, previous: "holding", state: "active", alreadyFired: false))
        XCTAssertFalse(AnswerCue.shouldFire(placedHere: true, previous: "active", state: "active", alreadyFired: false))
        XCTAssertFalse(AnswerCue.shouldFire(placedHere: true, previous: "dialing", state: "connecting", alreadyFired: false))
        XCTAssertFalse(AnswerCue.shouldFire(placedHere: true, previous: "ringing", state: "active", alreadyFired: false))
    }
}
