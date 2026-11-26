import Foundation
import XCTest
@testable import TetherCore

/// The Live Activity's display state, mapping and lifecycle policy (docs/LIVE-ACTIVITY.md).
/// The state is the contract between the app process and the widget extension process, and the
/// policy decides the whole lifecycle — both are pinned here, without ActivityKit.
final class LiveActivityTests: XCTestCase {
    private func status(battery: Int = 82, signal: Int = 4) -> StatusBody {
        StatusBody(device: "Pixel 8", battery: battery, charging: true,
                   sims: [SimInfo(slot: 0, sub: 3, name: "Personal", carrier: "Carrier A", signal: signal, net: "LTE", service: "in", country: "us"),
                          SimInfo(slot: 1, sub: 2, name: "Work", carrier: "Carrier B", signal: 2, net: "5G", service: "in", roaming: true, country: "us")],
                   defaultSub: 3, dialer: true, helper: HelperInfo(state: "running"),
                   wifi: true, adbWifi: false)
    }

    // MARK: mapping

    func testMakeMapsStatus() {
        let s = PhoneLiveState.make(status: status(), deviceName: "My Android", linked: true)
        XCTAssertEqual(s.device, "My Android")
        XCTAssertEqual(s.battery, 82)
        XCTAssertTrue(s.charging)
        XCTAssertTrue(s.linked)
        XCTAssertNil(s.call)
        XCTAssertEqual(s.sims.count, 2)
        XCTAssertEqual(s.sims[0].name, "Personal")
        XCTAssertEqual(s.sims[0].carrier, "Carrier A")
        XCTAssertEqual(s.sims[0].signal, 4)
        XCTAssertEqual(s.sims[0].net, "LTE")
        XCTAssertTrue(s.sims[0].hasService)
        XCTAssertTrue(s.sims[0].isDefault)
        XCTAssertFalse(s.sims[1].isDefault)
        XCTAssertTrue(s.sims[1].roaming)
        // The featured SIM is the default one.
        XCTAssertEqual(s.featuredSim?.name, "Personal")
    }

    func testMakeClampsBatteryAndSignal() {
        let s = PhoneLiveState.make(status: status(battery: 130, signal: 7), deviceName: "A", linked: true)
        XCTAssertEqual(s.battery, 100)
        XCTAssertEqual(s.sims[0].signal, 4)
        let low = PhoneLiveState.make(status: status(battery: -5, signal: -1), deviceName: "A", linked: true)
        XCTAssertEqual(low.battery, 0)
        XCTAssertEqual(low.sims[0].signal, 0)
    }

    func testMakeCarriesNoService() {
        var st = status()
        st.sims[0].service = "none"
        let s = PhoneLiveState.make(status: st, deviceName: "A", linked: true)
        XCTAssertFalse(s.sims[0].hasService)
        XCTAssertEqual(s.sims[0].service, "none")
    }

    func testFeaturedSimFallsBackToFirst() {
        var st = status()
        st.defaultSub = nil
        let s = PhoneLiveState.make(status: st, deviceName: "A", linked: true)
        XCTAssertEqual(s.featuredSim?.slot, 0)
        XCTAssertTrue(s.sims.allSatisfy { !$0.isDefault })
    }

    func testMakeWithCall() {
        // `since` is on the Android phone's clock, 60 s ago; the peer runs 5 s ahead of us.
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let call = CallInfo(cid: "c1", num: "+15550101", name: nil, state: "active", dir: "in",
                            since: nowMs + 5_000 - 60_000)
        let s = PhoneLiveState.make(status: status(), deviceName: "A", linked: true,
                                    call: call, callLabel: "Sara Ahmadi", peerOffsetMs: 5_000)
        let c = try! XCTUnwrap(s.call)
        XCTAssertEqual(c.label, "Sara Ahmadi")
        XCTAssertTrue(c.incoming)
        XCTAssertTrue(c.connected)
        XCTAssertFalse(c.held)
        XCTAssertFalse(c.elsewhere)
        let since = try! XCTUnwrap(c.since)
        XCTAssertEqual(since.timeIntervalSince1970, Double(nowMs - 60_000) / 1000, accuracy: 1.5)
    }

    func testMakeCallLabelFallsBackToNameThenNumber() {
        let named = CallInfo(cid: "c1", num: "+15550101", name: "From Phone", state: "ringing", dir: "in")
        XCTAssertEqual(PhoneLiveState.make(status: status(), deviceName: "A", linked: true, call: named).call?.label, "From Phone")
        let bare = CallInfo(cid: "c2", num: "+15550109", state: "dialing", dir: "out")
        let s = PhoneLiveState.make(status: status(), deviceName: "A", linked: true, call: bare)
        XCTAssertEqual(s.call?.label, "+15550109")
        XCTAssertEqual(s.call?.connected, false)
        XCTAssertEqual(s.call?.incoming, false)
    }

    func testMakeDropsEndedCall() {
        let ended = CallInfo(cid: "c1", num: "+1", state: "ended", dir: "in")
        XCTAssertNil(PhoneLiveState.make(status: status(), deviceName: "A", linked: true, call: ended).call)
    }

    func testWithLinkedKeepsTheFacts() {
        let s = PhoneLiveState.make(status: status(), deviceName: "A", linked: true)
        let down = s.withLinked(false)
        XCTAssertFalse(down.linked)
        XCTAssertEqual(down.battery, s.battery)
        XCTAssertEqual(down.sims, s.sims)
        XCTAssertEqual(down.device, s.device)
    }

    func testStateCodableRoundTrip() throws {
        let call = CallInfo(cid: "c1", num: "+15550101", state: "holding", dir: "out",
                            since: 1_800_000_000_000, hold: true)
        let s = PhoneLiveState.make(status: status(), deviceName: "A", linked: true,
                                    call: call, peerOffsetMs: 0)
        let data = try JSONEncoder().encode(s)
        XCTAssertEqual(try JSONDecoder().decode(PhoneLiveState.self, from: data), s)
    }

    // MARK: pickCall

    func testPickCallPrefersActiveHere() {
        let ringing = CallInfo(cid: "a", num: "+1", state: "ringing", dir: "in")
        let active = CallInfo(cid: "b", num: "+2", state: "active", dir: "out")
        XCTAssertEqual(PhoneLiveState.pickCall(from: [ringing, active])?.cid, "b")
    }

    func testPickCallPrefersHereOverElsewhere() {
        let elsewhereActive = CallInfo(cid: "a", num: "+1", state: "active", dir: "in", elsewhere: true)
        let hereRinging = CallInfo(cid: "b", num: "+2", state: "ringing", dir: "in")
        XCTAssertEqual(PhoneLiveState.pickCall(from: [elsewhereActive, hereRinging])?.cid, "b")
        // …but an elsewhere call still features when it is the only one.
        XCTAssertEqual(PhoneLiveState.pickCall(from: [elsewhereActive])?.cid, "a")
    }

    func testPickCallSkipsEndedAndEmpty() {
        let ended = CallInfo(cid: "a", num: "+1", state: "ended", dir: "in")
        XCTAssertNil(PhoneLiveState.pickCall(from: [ended]))
        XCTAssertNil(PhoneLiveState.pickCall(from: []))
    }

    func testPickCallIsDeterministic() {
        let a = CallInfo(cid: "a", num: "+1", state: "ringing", dir: "in")
        let b = CallInfo(cid: "b", num: "+2", state: "ringing", dir: "in")
        XCTAssertEqual(PhoneLiveState.pickCall(from: [b, a])?.cid, "a")
        XCTAssertEqual(PhoneLiveState.pickCall(from: [a, b])?.cid, "a")
    }

    // MARK: policy

    private var state: PhoneLiveState { PhoneLiveState.make(status: status(), deviceName: "A", linked: true) }

    private func decide(enabled: Bool = true, paired: Bool = true, demo: Bool = false,
                        connected: Bool = true, current: PhoneLiveState? = nil,
                        next: PhoneLiveState? = nil) -> LiveActivityPolicy.Action {
        LiveActivityPolicy.decide(enabled: enabled, paired: paired, demo: demo,
                                  connected: connected, current: current, next: next ?? state)
    }

    func testPolicyStartsWhenLinkedWithStatus() {
        XCTAssertEqual(decide(), .start(state))
    }

    func testPolicyWaitsForFirstStatus() {
        XCTAssertEqual(LiveActivityPolicy.decide(enabled: true, paired: true, demo: false,
                                                 connected: true, current: nil, next: nil), .none)
    }

    func testPolicyUpdatesOnlyOnChange() {
        XCTAssertEqual(decide(current: state), .none)
        var changed = state
        changed.battery = 41
        XCTAssertEqual(decide(current: state, next: changed), .update(changed))
    }

    func testPolicyEndsOnLinkDownWithLastFacts() {
        XCTAssertEqual(decide(connected: false, current: state),
                       .end(final: state.withLinked(false), immediate: false))
        // Already down (or never up): nothing more to do.
        XCTAssertEqual(decide(connected: false, current: state.withLinked(false)), .none)
        XCTAssertEqual(decide(connected: false, current: nil), .none)
    }

    func testPolicyEndsAtOnceWhenOffUnpairedOrDemo() {
        XCTAssertEqual(decide(enabled: false, current: state), .end(final: nil, immediate: true))
        XCTAssertEqual(decide(paired: false, current: state), .end(final: nil, immediate: true))
        XCTAssertEqual(decide(demo: true, current: state), .end(final: nil, immediate: true))
        XCTAssertEqual(decide(enabled: false, current: nil), .none)
        XCTAssertEqual(decide(demo: true, current: nil), .none)
    }

    func testPolicyDemoNeverStarts() {
        XCTAssertEqual(decide(demo: true, current: nil), .none)
    }

    // MARK: the demo sample populates the activity state too

    func testDemoSampleMapsToAPopulatedState() {
        let sample = DemoSample.make(now: 1_800_000_000_000)
        let s = PhoneLiveState.make(status: sample.status, deviceName: "Sample phone", linked: true)
        XCTAssertEqual(s.sims.count, 2)
        XCTAssertEqual(s.battery, 82)
        XCTAssertEqual(s.featuredSim?.name, "Personal")
    }
}
