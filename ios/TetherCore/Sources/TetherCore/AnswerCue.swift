import Foundation

/// The short vibration when the other side answers a call this device placed (as the system Phone app does).
@_spi(TetherApp) public enum AnswerCue {
    /// True once per call, on the change from dialing / connecting to active, and only for a call placed on this device
    /// (not one dialed or answered on the Android phone, moved here, or already live at a reconnect).
    public static func shouldFire(placedHere: Bool, previous: String?, state: String, alreadyFired: Bool) -> Bool {
        guard placedHere, !alreadyFired, state == "active", let previous else { return false }
        return previous == "dialing" || previous == "connecting"
    }
}
