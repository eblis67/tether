package app.tether.client.calls

/**
 * The short vibration when the other side answers a call this phone placed: the rule of the iPhone's
 * `AnswerCue.shouldFire` (TetherCore). Once per call, on the change from dialing / connecting to active, and only for a
 * call placed here (not one dialed or answered on the Android phone, moved here, or already live at a reconnect).
 */
object AnswerCue {
    fun shouldFire(placedHere: Boolean, previous: String?, state: String, alreadyFired: Boolean): Boolean =
        placedHere && !alreadyFired && state == "active" && (previous == "dialing" || previous == "connecting")
}
