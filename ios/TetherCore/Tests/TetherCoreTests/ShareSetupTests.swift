import XCTest
@testable import TetherCore

/// Share VPN setup payloads (§5.3): the iPhone builds the same strings the Android phone builds.
final class ShareSetupTests: XCTestCase {
    func testTelegramLinkIsASocksDeepLink() {
        XCTAssertEqual(ShareSetup.telegramLink(ip: "192.168.44.1", port: 8118),
                       "tg://socks?server=192.168.44.1&port=8118")
    }

    func testBypassListIsSanitizedLikeOnAndroid() {
        let clean = ShareSetup.sanitizedBypass([
            " Bank.Example ", "https://www.bank.example/login", "*.shop.local", "bank.example",
            "not a host", "localhost", "a..b.com", "-bad.com", "ok-site.org.", "",
        ])
        XCTAssertEqual(clean, ["bank.example", "www.bank.example", "shop.local", "ok-site.org"])
        XCTAssertEqual(ShareSetup.sanitizedBypass((1...100).map { "h\($0).example" }).count, ShareSetup.maxBypass)
    }

    func testSetupPicksTheHotspotAddress() {
        let p = ShareProxyInfo(port: 8118, addrs: [
            .init(iface: "bt-pan", ip: "192.168.44.1", kind: "bt", pac: "http://192.168.44.1:8118/proxy.pac"),
            .init(iface: "swlan0", ip: "192.168.43.1", kind: "hotspot", pac: "http://192.168.43.1:8118/proxy.pac",
                  tg: "tg://socks?server=192.168.43.1&port=8118", qr: "http://192.168.43.1:8118/proxy.pac"),
        ], bypass: ["bank.example"], telegram: "tg://socks?server=192.168.43.1&port=8118", offIn: nil)
        XCTAssertEqual(p.setupAddr?.kind, "hotspot")
        XCTAssertEqual(p.qrPayload, "http://192.168.43.1:8118/proxy.pac")
        XCTAssertEqual(p.telegramLink, "tg://socks?server=192.168.43.1&port=8118")
        XCTAssertEqual(p.bypass, ["bank.example"])
    }

    func testSetupFallsBackForOlderAndroidBuilds() {
        // No tg/qr/telegram from an older build: the payloads are rebuilt from the PAC address and port.
        let p = ShareProxyInfo(port: 8899, addrs: [
            .init(iface: "bt-pan", ip: "192.168.44.1", kind: "bt", pac: "http://192.168.44.1:8899/proxy.pac"),
        ])
        XCTAssertEqual(p.qrPayload, "http://192.168.44.1:8899/proxy.pac")
        XCTAssertEqual(p.telegramLink, "tg://socks?server=192.168.44.1&port=8899")
        XCTAssertNil(p.bypass); XCTAssertNil(p.offIn)
    }
}
