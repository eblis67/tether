import AVFoundation
import CallKit
import XCTest
@_spi(TetherApp) import TetherCore
@testable import Tether

/// CallKit's provider callbacks (#15): they arrive on the main queue in CallKit's order and must take effect right
/// there. Wrapped in a Task each, a didDeactivate could run before the didActivate that came first.
@MainActor
final class CallControllerTests: XCTestCase {
    var m: AppModel { AppModel.shared }
    private let provider = CXProvider(configuration: CXProviderConfiguration())
    private var session: AVAudioSession { AVAudioSession.sharedInstance() }

    override func setUp() async throws {
        await MainActor.run {
            AppModel.shared.resetForTests()
            AppModel.shared.calls.providerDidReset(provider)
        }
    }

    func testSessionCallbacksTakeEffectAtOnceAndInOrder() {
        let c = m.calls
        XCTAssertFalse(c.audioSessionActive)
        c.provider(provider, didActivate: session)
        XCTAssertTrue(c.audioSessionActive, "applied before the callback returns, not in a later Task")
        c.provider(provider, didDeactivate: session)
        XCTAssertFalse(c.audioSessionActive)
        // A quick deactivate / activate pair (a second call taking over the session) ends active.
        c.provider(provider, didDeactivate: session)
        c.provider(provider, didActivate: session)
        XCTAssertTrue(c.audioSessionActive)
        c.providerDidReset(provider)
        XCTAssertFalse(c.audioSessionActive, "a reset forgets the session")
        XCTAssertNil(m.callScreen)
    }
}
