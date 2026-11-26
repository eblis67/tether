#if DEBUG
import AVFoundation
import Foundation
@_spi(TetherApp) import TetherCore

/// Debug builds only: `-TetherDemo` fills the UI with made-up data and `-TetherDemoCall` opens the call
/// screen, so the interface can be checked in the Simulator (which has no Bluetooth).
extension AppModel {
    func loadDemoIfRequested() {
        let args = ProcessInfo.processInfo.arguments
        guard args.contains("-TetherDemo") || args.contains("-TetherDemoCall") else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        // The sample phone itself lives in TetherCore (`DemoSample`), shared with the
        // user-facing demo mode; what follows is only this debug tool’s staging extras.
        seedDemo(DemoSample.make(now: now))
        // -TetherDemoMessages opens the Messages tab, -TetherDemoThread the first conversation,
        // -TetherDemoNotify posts a sample SMS notification.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {   // after the root view has picked its first tab
            if args.contains("-TetherDemoRich") { Router.shared.open(thread: 5) }
            else if args.contains("-TetherDemoThread") { Router.shared.open(thread: 1) }
            else if args.contains("-TetherDemoMessages") { Router.shared.tab = .messages }
            else if args.contains("-TetherDemoContacts") { Router.shared.tab = .contacts }
            else if args.contains("-TetherDemoKeypad") { Router.shared.tab = .keypad }
            else if args.contains("-TetherDemoRecents") { Router.shared.tab = .recents }
            else if args.contains("-TetherDemoPair") || args.contains("-TetherDemoPairManual") || args.contains("-TetherDemoPhoneTools") { Router.shared.tab = .link }
        }
        // -TetherDemoOpen <url> opens a link as if another app had (tel:, tether://dial?number=…); with
        // -TetherDemoOpenCancel the question is cancelled after 3 s, which leaves the number in the Keypad.
        if let i = args.firstIndex(of: "-TetherDemoOpen"), i + 1 < args.count, let url = URL(string: args[i + 1]) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                Router.shared.open(url: url)
                if args.contains("-TetherDemoOpenCancel") {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                        Router.shared.keypadNumber = Router.shared.dialRequest?.number
                        Router.shared.dialRequest = nil
                    }
                }
            }
        }
        if args.contains("-TetherDemoNotify") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                Notifications.shared.showSms(Sms(id: 9001, thread: 1, addr: "+15550101", body: "Running five minutes late, order for me?",
                                                 date: now, box: 1, read: false, sub: 3), from: "Sara Ahmadi")
                Notifications.shared.showSms(Sms(id: 9002, thread: 4, addr: "7000", body: "Your verification code is 482913",
                                                 date: now, box: 1, read: false, sub: 3), from: "Bank")
            }
        }
        if args.contains("-TetherDemoPhoneTools") {
            ussdResult = UssdResult(ok: true, text: "Your balance is 12.40. Valid until 30 Dec.", err: nil)
        }
        if args.contains("-TetherDemoCall") {
            let dialing = args.contains("-TetherDemoDialing")
            callScreen = CallScreen(uuid: UUID(), number: "+15550101", name: "Sara Ahmadi", state: dialing ? "dialing" : "active",
                                    incoming: !dialing, connectedAt: dialing ? nil : Date().addingTimeInterval(-42), sub: 3)
            showCallScreen = !args.contains("-TetherDemoBanner")
            if args.contains("-TetherDemoRoutes") {   // the audio menu with a headset and a car (no Bluetooth in the simulator)
                callScreen?.routes = AudioRoutes.options(inputs: [
                    RoutePort(type: "BluetoothHFP", uid: "00:11:22:33:44:55-tsco", name: "Headset"),
                    RoutePort(type: "CarAudio", uid: "00:11:22:33:44:66-car", name: "Car")])
                callScreen?.route = .bluetooth
                callScreen?.selectedRoute = "00:11:22:33:44:55-tsco"
            }
        }
    }
}
#endif

#if DEBUG
/// Debug launch options for on-device tests (never in release builds):
///   -TetherPairCode tether1:…  pair at launch (unless already paired with that phone)
///   -TetherAutoAnswer          answer ringing calls after a second (test calls from the Android phone's fake line)
///   -TetherEcho                send received call audio straight back instead of the mic (round-trip timing)
extension AppModel {
    func applyDebugLaunchOptions() {
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-TetherPairCode"), i + 1 < args.count, let code = PairingCode.parse(args[i + 1]),
           identity.peerKey != code.peripheralKey {
            pair(code)
        }
    }

    func debugAutoAnswer(_ c: CallInfo) {
        let args = ProcessInfo.processInfo.arguments
        // -TetherAutoHangup N: an active call is ended from this iPhone after N seconds (tests without a tap).
        if c.state == "active", let i = args.firstIndex(of: "-TetherAutoHangup"), i + 1 < args.count, let n = Double(args[i + 1]),
           !autoHangupArmed.contains(c.cid) {
            autoHangupArmed.insert(c.cid)
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(n))
                self.calls.end(cid: c.cid)
            }
        }
        guard args.contains("-TetherAutoAnswer"), c.state == "ringing", c.isIncoming else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1))
            self.calls.answer(cid: c.cid)
        }
    }
}
#endif


#if DEBUG
extension AppModel {
    /// -TetherAudioTest: runs the call-audio start path (session, engine, voice processing) without a call, twice.
    func debugAudioTest() {
        guard ProcessInfo.processInfo.arguments.contains("-TetherAudioTest") else { return }
        Task { @MainActor in
            for round in 1...2 {
                try? await Task.sleep(for: .seconds(3))
                NSLog("[audiotest] round %d", round)
                CallAudio.configureSession()
                try? AVAudioSession.sharedInstance().setActive(true)
                audio.configure(AudioStart(codec: "opus", rate: 16000, ms: 20, up: true, bitrate: 32000))
                audio.sessionActivated()
                try? await Task.sleep(for: .seconds(3))
                audio.stop()
                try? AVAudioSession.sharedInstance().setActive(false)
            }
        }
    }
}
#endif
