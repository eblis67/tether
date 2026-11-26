import Foundation

/// The sample phone of the demo mode ("Try with a Sample Phone", docs/DEMO-MODE.md): one believable
/// fake Android phone as pure data — two SIMs, a two-way conversation, bank texts, call history,
/// received files, Share VPN on, call-audio methods present — so the apps can fill every screen
/// without a real phone. Pure and deterministic in `now` so tests can pin it; nothing here is real
/// data (the numbers are the reserved +1 555 range and test numbers, as in the Simulator demo before).
public struct DemoSampleData: Equatable, Sendable {
    public var sms: [Sms]
    public var contacts: [Contact]
    public var recents: [CallEntry]
    public var status: StatusBody
    public var lists: BlockLists
    public var net: NetState
    /// Names shown in the Files section, as if the sample phone had sent them earlier.
    public var receivedFileNames: [String]

    public init(sms: [Sms], contacts: [Contact], recents: [CallEntry], status: StatusBody,
                lists: BlockLists, net: NetState, receivedFileNames: [String]) {
        self.sms = sms; self.contacts = contacts; self.recents = recents; self.status = status
        self.lists = lists; self.net = net; self.receivedFileNames = receivedFileNames
    }
}

public enum DemoSample {
    /// The whole sample phone, with message and call dates relative to `now` (epoch milliseconds).
    public static func make(now: Int64) -> DemoSampleData {
        let min: Int64 = 60_000
        var sms: [Sms] = []
        var id: Int64 = 1
        func add(thread: Int64, addr: String, body: String, agoMin: Int64, incoming: Bool, read: Bool = true, sub: Int? = 3) {
            sms.append(Sms(id: id, thread: thread, addr: addr, body: body, date: now - agoMin * min,
                           box: incoming ? 1 : 2, read: read, sub: sub))
            id += 1
        }

        // Thread 1 — Sara: a two-way conversation.
        add(thread: 1, addr: "+15550101", body: "Hey, are you still coming tonight?", agoMin: 190, incoming: true)
        add(thread: 1, addr: "+15550101", body: "Yes! Leaving work around six.", agoMin: 188, incoming: false)
        add(thread: 1, addr: "+15550101", body: "Want me to bring anything?", agoMin: 187, incoming: false)
        add(thread: 1, addr: "+15550101", body: "Just yourself 😄", agoMin: 60, incoming: true)
        add(thread: 1, addr: "+15550101", body: "We booked the table for 7:30", agoMin: 59, incoming: true)
        add(thread: 1, addr: "+15550101", body: "Perfect, see you there", agoMin: 2, incoming: false)

        // Thread 2 — Daniel, on the Work SIM; the last text is unread, so the Messages badge shows.
        add(thread: 2, addr: "+15550102", body: "Did the update fix your call problem?", agoMin: 300, incoming: true, sub: 2)
        add(thread: 2, addr: "+15550102", body: "It did — the audio is clear now.", agoMin: 295, incoming: false, sub: 2)
        add(thread: 2, addr: "+15550102", body: "Nice. Send me the link later?", agoMin: 290, incoming: true, read: false, sub: 2)

        // Thread 3 — Nima (the muted conversation in the sample block lists).
        add(thread: 3, addr: "+15550103", body: "سلام! برای فردا برنامه چیه؟", agoMin: 1400, incoming: true)
        add(thread: 3, addr: "+15550103", body: "فردا زنگ می‌زنم، صحبت می‌کنیم", agoMin: 1390, incoming: false)

        // Thread 4 — the bank's short code.
        add(thread: 4, addr: "7000", body: "Payment of 25.00 received. New balance: 212.40", agoMin: 1500, incoming: true)
        add(thread: 4, addr: "7000", body: "Your verification code is 482913", agoMin: 180, incoming: true)

        // Thread 5 — Bank Mellat: rich text cases (links, codes, numbers, formatting, Persian and
        // mixed direction), as the Simulator demo had them.
        let rich: [(Bool, String, Int64)] = [   // (incoming, text, minutes ago)
            (true, "رمز پویا: ۸۲۷۱۶۳\nمبلغ: ۱,۲۵۰,۰۰۰ ریال\nکارت: ۶۰۳۷**۱۲۳۴\nزمان اعتبار: ۱۲۰ ثانیه", 7 * 60),
            (true, "G-7741 is your Google verification code.", 6 * 60),
            (true, "سلام! لینک پرداخت قبض شما: https://shaparak.ir/pay?id=88213 تا ساعت ۲۰ معتبر است.", 5 * 60),
            (false, "Thanks, see www.example.com and mail support@example.com", 4 * 60),
            (true, "*Sale:* 50% off today only, _online_ at digikala.com. Code `SAVE10`. Dial *140*1# for balance.", 3 * 60),
            (true, "برای پشتیبانی با ۰۲۱-۸۸۷۷۶۶۵۵ یا ۰۹۱۲۱۲۳۴۵۶۷ تماس بگیرید.", 2 * 60),
            (false, "Call me at +1 (555) 010-0123 تا ساعت ۵ — ok?", 60),
        ]
        for r in rich { add(thread: 5, addr: "+15550105", body: r.1, agoMin: r.2, incoming: r.0, sub: 2) }

        // Thread 6 — a number that is in no contact.
        add(thread: 6, addr: "+10000000003", body: "Hi, is the bike still available?", agoMin: 45, incoming: true)

        let contacts = [
            Contact(id: 50, name: "Bank Mellat", phones: [Phone(n: "+15550105")]),
            Contact(id: 0, name: "Sara Ahmadi", phones: [Phone(n: "+15550101")], starred: true),
            Contact(id: 1, name: "Daniel Brooks", phones: [Phone(n: "+15550102")], starred: true),
            Contact(id: 2, name: "Nima", phones: [Phone(n: "+15550103")]),
            Contact(id: 3, name: "Bank", phones: [Phone(n: "7000")]),
            Contact(id: 4, name: "Ali Rezaei", phones: [Phone(n: "+15550104"), Phone(n: "+15550106", type: "work")],
                    emails: ["ali@example.com"]),
        ]

        // Call history: incoming (1), outgoing (2) and missed (3, duration 0) on both SIMs, newest
        // first. The newest entry is the missed call the Recents badge counts (the app seeds its
        // "seen" marker just below the highest missed id).
        let hour: Int64 = 60 * min
        let recents = [
            CallEntry(id: 30, num: "+15550102", date: now - 25 * min, dur: 0, type: 3, sub: 3),
            CallEntry(id: 29, num: "+15550101", date: now - 2 * hour, dur: 245, type: 1, sub: 3),
            CallEntry(id: 28, num: "+15550103", date: now - 5 * hour, dur: 62, type: 2, sub: 2),
            CallEntry(id: 27, num: "+15550105", date: now - 8 * hour, dur: 118, type: 1, sub: 3),
            CallEntry(id: 26, num: "+10000000001", date: now - 26 * hour, dur: 30, type: 2, sub: 3),
            CallEntry(id: 25, num: "+15550101", date: now - 28 * hour, dur: 512, type: 2, sub: 2),
            CallEntry(id: 24, num: "+15550102", date: now - 50 * hour, dur: 0, type: 3, sub: 2),
            CallEntry(id: 23, num: "*140#", date: now - 51 * hour, dur: 0, type: 2, sub: 3),
            CallEntry(id: 22, num: "+10000000002", date: now - 74 * hour, dur: 0, type: 3, sub: 3),
        ]

        // The sample phone itself: two SIMs, the audio helper running with the feature-3 method
        // ladders and automatic picks, so the Call audio section shows its populated state.
        let helper = HelperInfo(state: "running", method: "intercept", capture: "intercept", up: true, down: true,
                                silent: "full", micMute: false, uplink: "ok", mic: "auto", micPath: "off",
                                audio: "full",
                                methods: [AudioMethod(id: "intercept", dir: "inject"), AudioMethod(id: "telephony_tx", dir: "inject"),
                                          AudioMethod(id: "policy_mix", dir: "inject"),
                                          AudioMethod(id: "intercept", dir: "capture"), AudioMethod(id: "voice_downlink", dir: "capture"),
                                          AudioMethod(id: "telephony_rx", dir: "capture"), AudioMethod(id: "voice_call", dir: "capture")],
                                pick: AudioPick(inject: "auto", capture: "auto"), fallback: false)
        let status = StatusBody(device: "Android phone", battery: 82, charging: false,
                                sims: [SimInfo(slot: 0, sub: 3, name: "Personal", carrier: "Carrier A", signal: 4, net: "LTE", service: "in", country: "us"),
                                       SimInfo(slot: 1, sub: 2, name: "Work", carrier: "Carrier B", signal: 2, net: "LTE+", service: "in", country: "us")],
                                defaultSub: 3, dialer: true, helper: helper,
                                wifi: true, adbWifi: true,
                                tools: .init(ussd: true, find: true, ringer: true), ringer: "vibrate", dnd: false)

        // Share VPN on, with the setup payloads (PAC, Telegram link, QR) and a bypass list.
        let proxy = ShareProxyInfo(port: 8118, addrs: [.init(iface: "swlan0", ip: "10.123.181.251", kind: "hotspot",
                                                             pac: "http://10.123.181.251:8118/proxy.pac",
                                                             tg: "tg://socks?server=10.123.181.251&port=8118",
                                                             qr: "http://10.123.181.251:8118/proxy.pac")],
                                   bypass: ["bank.example"], telegram: "tg://socks?server=10.123.181.251&port=8118")
        let net = NetState(data: true, hotspot: true, bt: false, dataSub: 3, vpn: true, share: true,
                           proxy: proxy, airplane: false, rat: [.init(slot: 0, mode: "5G"), .init(slot: 1, mode: "4G")])

        return DemoSampleData(sms: sms, contacts: contacts, recents: recents, status: status,
                              lists: BlockLists(blocked: ["7000", "+15550199"], muted: [3], system: true),
                              net: net, receivedFileNames: ["Family dinner.jpg", "Boarding pass.pdf"])
    }
}
