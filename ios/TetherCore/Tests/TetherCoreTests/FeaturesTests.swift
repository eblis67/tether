import XCTest
@testable import TetherCore

/// The control panel's shared state (protocol §5.4): defaults, flipping, adopting. Mirrors the Kotlin
/// FeaturesTest in android/core — both sides must agree on the merge rules or the panels diverge.
final class FeaturesTests: XCTestCase {

    func testEverythingIsOffByDefault() {
        let s = FeatureSettings.default
        XCTAssertEqual(s.rev, 0)
        for id in Feature.all { XCTAssertFalse(s.isOn(id), "\(id) must start off") }
    }

    func testFlippingMovesTheRevisionAndKeepsTheRest() {
        let s = FeatureSettings.default.with(Feature.internet, true).with(Feature.find, true).with(Feature.internet, false)
        XCTAssertFalse(s.isOn(Feature.internet)); XCTAssertTrue(s.isOn(Feature.find)); XCTAssertFalse(s.isOn(Feature.ussd))
        XCTAssertEqual(s.rev, 3)
    }

    func testANewerRevisionWinsWhole() {
        let mine = FeatureSettings.default.with(Feature.internet, true)                       // rev 1
        let theirs = FeatureSettings.default.with(Feature.ussd, true).with(Feature.find, true) // rev 2
        XCTAssertEqual(mine.adopt(theirs), theirs)
    }

    func testAnOlderRevisionLoses() {
        let mine = FeatureSettings.default.with(Feature.ussd, true).with(Feature.find, true)   // rev 2
        let theirs = FeatureSettings.default.with(Feature.internet, true)                      // rev 1
        XCTAssertEqual(mine.adopt(theirs), mine)
        XCTAssertEqual(mine.adopt(theirs, changedId: Feature.internet), mine)
    }

    func testTwoPhonesFlippingDifferentSwitchesAtOnceKeepBothFlips() {
        let base = FeatureSettings.default.with(Feature.ringer, true)                          // rev 1, shared
        let mine = base.with(Feature.internet, true)                                           // rev 2
        let theirs = base.with(Feature.find, true)                                             // also rev 2
        let merged = mine.adopt(theirs, changedId: Feature.find)
        XCTAssertTrue(merged.isOn(Feature.internet)); XCTAssertTrue(merged.isOn(Feature.find)); XCTAssertTrue(merged.isOn(Feature.ringer))
        XCTAssertTrue(merged.rev > mine.rev && merged.rev > theirs.rev)
        XCTAssertEqual(theirs.adopt(merged), merged)
    }

    func testBodyRoundTripsIncludingUnknownIds() throws {
        let s = FeatureSettings(values: [Feature.internet: true, "futureFeature": true, Feature.demo: false], rev: 7)
        let data = try JSONEncoder().encode(s.body)
        let back = FeatureSettings(try JSONDecoder().decode(FeaturesBody.self, from: data))
        XCTAssertEqual(back, s)
        XCTAssertTrue(back.isOn("futureFeature"), "an unknown id survives the round trip")
    }

    func testTheCatalogueCoversTheSpec() {
        XCTAssertTrue(Feature.available.isSuperset(of: [Feature.internet, Feature.ussd, Feature.find, Feature.ringer]))
        XCTAssertTrue(Set(Feature.all).isSuperset(of: Feature.available))
        XCTAssertTrue(Set(Feature.all).isSuperset(of: [Feature.files, Feature.liveActivity, Feature.demo]))
    }
}
