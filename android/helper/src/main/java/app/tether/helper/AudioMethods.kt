package app.tether.helper

/**
 * The call-audio method registry (docs/CALL-AUDIO.md): every route the helper can really open to hear a call
 * (capture) or to speak into it (inject), what it needs, and the order [CallAudio] tries them in.
 *
 * A "method" in Tether is one direction's route: capture and inject fail independently on real phones, are
 * opened from separate ladders and are scored separately by the self-test, so each direction's routes are
 * registered, remembered ([MethodChoice]), voted on and picked separately. iTell's one codename ≈ Tether's
 * (capture, inject) pair.
 *
 * Not registered, on purpose: `voice_uplink` (the check capture the self-test and the uplink check listen on,
 * not a call route), `voice_communication` (the phone's own mic path, in no ladder), and the [MicPath] redirect
 * modes (they decide what the phone's own mic and earpiece do, not how the client's voice travels; they keep
 * their own per-model verdicts).
 */
object AudioMethods {
    enum class Dir(val wire: String) {
        CAPTURE("capture"), INJECT("inject");

        companion object {
            fun of(wire: String?): Dir? = entries.firstOrNull { it.wire == wire }
        }
    }

    /**
     * One route. [minSdk]: the first Android version whose ladder offers it (the helper itself needs 30+ for
     * any call audio). [interceptApi]: the PSTN call-audio interception API (hidden, shell-only, 13+).
     */
    data class Method(val id: String, val dir: Dir, val minSdk: Int, val interceptApi: Boolean, val note: String)

    val INTERCEPT_CAPTURE = Method("intercept", Dir.CAPTURE, 33, true,
        "the PSTN interception API's downlink extraction record: the call's downlink, extracted by the system")
    val VOICE_DOWNLINK = Method("voice_downlink", Dir.CAPTURE, 30, false,
        "an AudioRecord on VOICE_DOWNLINK")
    val TELEPHONY_RX = Method("telephony_rx", Dir.CAPTURE, 30, false,
        "the default source, pinned to the telephony input device")
    val VOICE_CALL = Method("voice_call", Dir.CAPTURE, 30, false,
        "an AudioRecord on VOICE_CALL (both sides mixed on many HALs)")
    val INTERCEPT_INJECT = Method("intercept", Dir.INJECT, 33, true,
        "the PSTN interception API's uplink injection track: straight into the call's uplink")
    val TELEPHONY_TX = Method("telephony_tx", Dir.INJECT, 30, false,
        "a media track pinned to the Telephony Tx device; the route is verified after opening (some HALs quietly play it on the phone instead)")
    val POLICY_MIX = Method("policy_mix", Dir.INJECT, 33, false,
        "a dynamic audio-policy mix rendering a signalling-usage track to Telephony Tx")

    /** Every registered call method, capture routes first. */
    val all: List<Method> = listOf(INTERCEPT_CAPTURE, VOICE_DOWNLINK, TELEPHONY_RX, VOICE_CALL,
        INTERCEPT_INJECT, TELEPHONY_TX, POLICY_MIX)

    /**
     * The speaker/microphone fallback's id in picks and status: not a helper method — no route is opened and
     * the call stays on the Android phone itself (iTell's "Pig" mode). Always available, no helper needed.
     */
    const val FALLBACK = "phone"

    fun byId(dir: Dir, id: String?): Method? = all.firstOrNull { it.dir == dir && it.id == id }

    /** Capture routes to try on [sdk], best first (empty: this Android gives the helper no call audio). */
    fun captureLadder(sdk: Int): List<Method> = when {
        sdk >= 33 -> listOf(INTERCEPT_CAPTURE, VOICE_DOWNLINK, TELEPHONY_RX, VOICE_CALL)
        sdk >= 30 -> listOf(VOICE_DOWNLINK, VOICE_CALL, TELEPHONY_RX)
        else -> emptyList()
    }

    /** Inject routes to try on [sdk], best first (empty: this Android gives the helper no call audio). */
    fun injectLadder(sdk: Int): List<Method> = when {
        sdk >= 33 -> listOf(INTERCEPT_INJECT, TELEPHONY_TX, POLICY_MIX)
        sdk >= 30 -> listOf(TELEPHONY_TX)
        else -> emptyList()
    }

    fun ladder(dir: Dir, sdk: Int): List<Method> = if (dir == Dir.CAPTURE) captureLadder(sdk) else injectLadder(sdk)

    /** The routes this phone can offer (the picker and status list): each direction's ladder on [sdk]. */
    fun available(sdk: Int): List<Method> = captureLadder(sdk) + injectLadder(sdk)
}
