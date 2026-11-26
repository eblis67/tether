import XCTest
@testable import TetherCore

/// The demo mode's sample phone (docs/DEMO-MODE.md): the data every demo screen is filled with.
/// It must stay believable and complete — a screen that shows its empty state in the demo is a bug —
/// and deterministic, so the apps and the screenshots built on it do not drift.
final class DemoSampleTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000
    private var sample: DemoSampleData { DemoSample.make(now: now) }

    func testDeterministic() {
        XCTAssertEqual(sample, DemoSample.make(now: now))
        XCTAssertNotEqual(sample, DemoSample.make(now: now + 60_000))
    }

    func testTwoWayConversationAndUnread() {
        let sara = sample.sms.filter { $0.thread == 1 }
        XCTAssertFalse(sara.isEmpty)
        XCTAssertTrue(sara.contains { $0.incoming })
        XCTAssertTrue(sara.contains { !$0.incoming })
        // One unread text somewhere, so the Messages badge shows in the demo.
        XCTAssertTrue(sample.sms.contains { $0.incoming && !$0.read })
        // Ids unique, dates in the recent past, never in the future.
        XCTAssertEqual(Set(sample.sms.map(\.id)).count, sample.sms.count)
        XCTAssertTrue(sample.sms.allSatisfy { $0.date <= now && $0.date > now - 7 * 24 * 3600_000 })
    }

    func testEveryThreadHasAContactOrIsUnknown() {
        let known = Set(sample.contacts.flatMap { $0.phones.map(\.n) })
        let threads = Set(sample.sms.map(\.addr))
        // All but the one unknown-number thread resolve to a contact.
        XCTAssertEqual(threads.subtracting(known), ["+10000000003"])
    }

    func testContactsHaveFavouritesAndAMultiNumberContact() {
        XCTAssertTrue(sample.contacts.contains { $0.starred })
        XCTAssertTrue(sample.contacts.contains { $0.phones.count > 1 })
        XCTAssertTrue(sample.contacts.allSatisfy { !$0.phones.isEmpty })
    }

    func testRecentsCoverTypesAndBothSims() {
        let types = Set(sample.recents.map(\.type))
        XCTAssertTrue(types.contains(1), "incoming")
        XCTAssertTrue(types.contains(2), "outgoing")
        XCTAssertTrue(types.contains(3), "missed")
        XCTAssertTrue(sample.recents.filter { $0.type == 3 }.allSatisfy { $0.dur == 0 })
        XCTAssertEqual(Set(sample.recents.compactMap(\.sub)), [2, 3])
        XCTAssertTrue(sample.recents.allSatisfy { $0.date <= now })
        // Newest first, and the newest is a missed call: the app seeds its seen marker below the
        // highest missed id, so exactly that one call badges in Recents.
        XCTAssertEqual(sample.recents.map(\.date), sample.recents.map(\.date).sorted(by: >))
        XCTAssertEqual(sample.recents.first?.type, 3)
        XCTAssertEqual(sample.recents.filter { $0.type == 3 }.map(\.id).max(), sample.recents.first?.id)
    }

    func testStatusIsPopulated() {
        let s = sample.status
        XCTAssertEqual(s.sims.count, 2)
        XCTAssertEqual(Set(s.sims.map(\.sub)), [2, 3])
        XCTAssertEqual(s.defaultSub, 3)
        XCTAssertEqual(s.helper.state, "running")
        // Feature 3: the method pickers show their populated state.
        let methods = s.helper.methods ?? []
        XCTAssertTrue(methods.contains { $0.dir == "inject" })
        XCTAssertTrue(methods.contains { $0.dir == "capture" })
        XCTAssertNotNil(s.helper.pick)
        XCTAssertEqual(s.helper.fallback, false)
        XCTAssertEqual(s.tools?.ussd, true)
        XCTAssertEqual(s.tools?.find, true)
        XCTAssertEqual(s.tools?.ringer, true)
    }

    func testShareAndFilesArePopulated() {
        // Feature 2: Share VPN on with the setup payloads and a bypass list.
        XCTAssertEqual(sample.net.share, true)
        XCTAssertEqual(sample.net.vpn, true)
        XCTAssertNotNil(sample.net.proxy?.qrPayload)
        XCTAssertNotNil(sample.net.proxy?.telegramLink)
        XCTAssertFalse(sample.net.proxy?.bypass?.isEmpty ?? true)
        // Feature 1: files the sample phone "sent" earlier.
        XCTAssertGreaterThanOrEqual(sample.receivedFileNames.count, 2)
    }

    func testListsPresent() {
        XCTAssertFalse(sample.lists.blocked.isEmpty)
        XCTAssertFalse(sample.lists.muted.isEmpty)
    }
}
