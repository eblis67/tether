import XCTest
@testable import TetherCore

/// Call-audio methods over the wire (docs/CALL-AUDIO.md): status fields, picker options, feedback.
final class AudioMethodTests: XCTestCase {
    private func helper(_ json: String) throws -> HelperInfo {
        try JSONDecoder().decode(HelperInfo.self, from: Data(json.utf8))
    }

    func testStatusDecodesMethodsPickFallback() throws {
        let h = try helper("""
            {"state":"running","up":true,"down":true,
             "methods":[{"id":"intercept","dir":"capture"},{"id":"voice_call","dir":"capture"},
                        {"id":"intercept","dir":"inject"},{"id":"telephony_tx","dir":"inject"}],
             "pick":{"inject":"telephony_tx","capture":"auto"},"fallback":true}
            """)
        XCTAssertEqual(h.methods?.count, 4)
        XCTAssertEqual(h.pick?.inject, "telephony_tx")
        XCTAssertEqual(h.fallback, true)
        XCTAssertEqual(AudioMethodOptions.options(h, dir: "inject"), ["auto", "intercept", "telephony_tx"])
        XCTAssertEqual(AudioMethodOptions.options(h, dir: "capture"), ["auto", "intercept", "voice_call"])
        XCTAssertEqual(AudioMethodOptions.selection(h, dir: "inject"), "telephony_tx")
        XCTAssertEqual(AudioMethodOptions.selection(h, dir: "capture"), "auto")
    }

    func testOlderStatusHasNoPicker() throws {
        let h = try helper(#"{"state":"running","up":false,"down":false}"#)
        XCTAssertNil(h.methods)
        XCTAssertNil(h.pick)
        XCTAssertNil(h.fallback)
        XCTAssertEqual(AudioMethodOptions.options(h, dir: "inject"), ["auto"])
        XCTAssertEqual(AudioMethodOptions.selection(h, dir: "capture"), "auto")
    }

    func testSelectionDropsAPickThePhoneNoLongerOffers() throws {
        let h = try helper("""
            {"state":"running","up":true,"down":true,
             "methods":[{"id":"telephony_tx","dir":"inject"}],
             "pick":{"inject":"policy_mix","capture":"auto"}}
            """)
        XCTAssertEqual(AudioMethodOptions.selection(h, dir: "inject"), "auto")
    }

    func testSelftestLiveCallDetection() {
        XCTAssertTrue(SelftestResult(methods: [SelftestMethod(name: "inject:intercept", ok: true)]).testedLiveCall)
        XCTAssertTrue(SelftestResult(methods: [SelftestMethod(name: "inject:telephony_tx, phone mic muted", ok: false)]).testedLiveCall)
        XCTAssertFalse(SelftestResult(methods: [SelftestMethod(name: "live-call", ok: false, note: "needs an active call")]).testedLiveCall)
        XCTAssertFalse(SelftestResult(methods: []).testedLiveCall)
    }

    func testAudioSetEncodesOnlyWhatWasSet() throws {
        let pick = try JSONEncoder().encode(AudioSet(inject: "telephony_tx"))
        XCTAssertEqual(String(decoding: pick, as: UTF8.self), #"{"inject":"telephony_tx"}"#)
        let fb = try JSONEncoder().encode(AudioSet(feedback: AudioFeedback(you: false, them: true)))
        let o = try JSONSerialization.jsonObject(with: fb) as? [String: Any]
        XCTAssertEqual((o?["feedback"] as? [String: Any])?["you"] as? Bool, false)
        XCTAssertEqual((o?["feedback"] as? [String: Any])?["them"] as? Bool, true)
        XCTAssertNil(o?["inject"])
    }
}
