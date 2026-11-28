package app.tether.helper

/**
 * When the helper opens a call's audio paths. Pure: the helper polls, this decides.
 *
 * A call's audio exists only once the phone routes it: right at dialing or answering the audio mode is still NORMAL
 * (on the reference phone for the first seconds of an outgoing call, 2026-11-24) and the interception API refuses
 * ("not available in mode MODE_NORMAL"). Falling back to the older methods then gave a capture that delivered nothing
 * (4 frames in a call) and no way back to the interception API except a restart. So:
 * - [ready]: open nothing until the phone is in a call's audio mode ([MicPath.Mode.isCall]), or the call has been
 *   active for [activeMs] (a phone that never reports one still gets its audio).
 * - [retry]: the interception API refusing for the audio mode is tried again quietly while the phone is not in a
 *   call's mode, and for [graceMs] once it is (the mode and the API's own state change a moment apart); any other
 *   error, or a refusal that lasts, moves on to the next method (the older ones are what phones without the API use).
 */
class OpenGate(private val graceMs: Long = 2000, private val activeMs: Long = 3000) {
    private var inCallSince = -1L

    /** [activeForMs]: how long the app has reported the call active (-1: not active, or not reported). */
    fun ready(mode: Int, activeForMs: Long): Boolean = MicPath.Mode.isCall(mode) || activeForMs >= activeMs

    /** After [method] failed with [error] at [nowMs] in audio [mode]: try the same method again? */
    fun retry(method: String, error: String, mode: Int, nowMs: Long, activeForMs: Long): Boolean {
        if (method != "intercept" || !transient(error)) return false
        if (!ready(mode, activeForMs)) { inCallSince = -1; return true }
        if (inCallSince < 0) inCallSince = nowMs
        return nowMs - inCallSince < graceMs
    }

    companion object {
        /** The interception API's refusal for the audio mode (it works once the call's audio is routed). */
        fun transient(error: String): Boolean = error.contains("not available in mode")
    }
}

/**
 * Which uplink injection a call keeps (issue #14). On a Redmi Note 10 5G (MediaTek, 2026-11-26) 1.2.5 opened none at
 * all (helper "method -", 1076 of 1126 uplink frames dropped) where 1.1.0 had worked. Since 1.1.0 the choice had
 * narrowed three ways: the self-test's saved method was the only one tried, `telephony_tx` was refused whenever Android
 * had not reported its route within 300 ms (1.1.0 took the track as it came), and one grace for the interception API
 * served capture and injection together, so a slow capture left the injection none. Now every method is tried in turn
 * once the call has its audio mode ([PathOpener], each direction with its own [OpenGate]), and each opened track is
 * judged by where Android routed it.
 */
object InjectChoice {
    /** AudioDeviceInfo.TYPE_TELEPHONY (the call's uplink) and TYPE_REMOTE_SUBMIX (the VoIP test line's). */
    const val TYPE_TELEPHONY = 18
    const val TYPE_REMOTE_SUBMIX = 25

    enum class Route { CALL, ELSEWHERE, UNKNOWN }

    fun route(type: Int, testLine: Boolean): Route = when {
        type < 0 -> Route.UNKNOWN
        type == TYPE_TELEPHONY || (testLine && type == TYPE_REMOTE_SUBMIX) -> Route.CALL
        else -> Route.ELSEWHERE
    }

    enum class Verdict {
        /** Use it. */
        KEEP,
        /** Routed away from the call (the iPhone's voice would play on this phone): close it, try the next method. */
        NEXT,
        /** No route reported: used only when no other method gets one, as 1.1.0 used it. */
        LAST_RESORT
    }

    /**
     * A just-opened track of [method]: routed to the call → kept; routed elsewhere → the next method; no route reported
     * → the interception API's own track is kept (it has been since 1.0 and works on the reference phone), any other
     * one only as the last resort.
     */
    fun judge(method: String, route: Route): Verdict = when (route) {
        Route.CALL -> Verdict.KEEP
        Route.ELSEWHERE -> Verdict.NEXT
        Route.UNKNOWN -> if (method == "intercept") Verdict.KEEP else Verdict.LAST_RESORT
    }
}

/**
 * Opens one direction of a call's audio: the methods of an order in turn, each until it opens or fails for good (the
 * interception API's refusal for the audio mode is retried within this opener's own [gate]); [judge] keeps an opened
 * path, moves on ([InjectChoice.Verdict.NEXT]) or holds it as the last resort. Every failure goes into the errors
 * (`<kind>:<method> <why>`, as the app's link log shows them) and into [Opened.why]. Pure apart from [open] / [close].
 */
class PathOpener<T : Any>(
    private val kind: String,
    private val gate: OpenGate,
    private val env: Env,
    private val open: (String) -> T,
    private val close: (T) -> Unit,
    private val judge: (String, T) -> Pair<InjectChoice.Verdict, String> = { _, _ -> InjectChoice.Verdict.KEEP to "" },
    private val describe: (Throwable) -> String = { "${it.javaClass.simpleName}: ${it.message}" },
    /**
     * Methods tried only while no earlier one opened at all: once a track without a reported route is held as the last
     * resort they are skipped. `policy_mix`: 1.1.0 never reached it once `telephony_tx` opened, and on a Galaxy A73 it
     * was routed to the call yet carried nothing (self-test tone 0), so a reported route alone does not rank it higher.
     */
    private val onlyIfNothingElse: Set<String> = emptySet(),
) {
    /** What the opener needs of the call: still this call, its audio mode, the clock, how long it has been active. */
    interface Env {
        fun alive(): Boolean
        fun mode(): Int
        fun nowMs(): Long
        fun activeForMs(): Long
        fun sleep(ms: Long)
    }

    /** The path kept: its method, the path, and in one line what was tried before it and why it was chosen. */
    data class Opened<T>(val method: String, val path: T, val why: String)

    /** The methods tried by the last [run] (also those that failed), in order. */
    val tried = ArrayList<String>()

    fun run(order: List<String>, errors: MutableList<String>): Opened<T>? {
        val notes = ArrayList<String>()
        var lastResort: Pair<String, String>? = null
        for (m in order) {
            if (lastResort != null && m in onlyIfNothingElse) { notes += "$m: not tried (${lastResort?.first} opened)"; continue }
            val p = attempt(m, errors, notes) ?: continue
            val (v, note) = runCatching { judge(m, p) }.getOrElse { InjectChoice.Verdict.KEEP to "" }
            when (v) {
                InjectChoice.Verdict.KEEP -> return Opened(m, p, why(notes, "$m${if (note.isEmpty()) "" else " ($note)"}"))
                InjectChoice.Verdict.NEXT -> {
                    runCatching { close(p) }
                    errors += "$kind:$m $note, not the call's uplink"; notes += "$m $note, not the call's uplink"
                }
                InjectChoice.Verdict.LAST_RESORT -> {
                    runCatching { close(p) }
                    if (lastResort == null) lastResort = m to note
                    notes += "$m: no route reported"
                }
            }
        }
        val (m, _) = lastResort ?: return null
        val p = attempt(m, errors, notes) ?: return null
        return Opened(m, p, why(notes, "$m (no other method was routed to the call; Android reported no route for it: used anyway, as 1.1.0 did)"))
    }

    private fun attempt(m: String, errors: MutableList<String>, notes: MutableList<String>): T? {
        if (m !in tried) tried += m
        while (env.alive()) {
            try { return open(m) } catch (t: Throwable) {
                val msg = describe(t)
                // Polled often: every ms here delays both directions' first audio.
                if (gate.retry(m, msg, env.mode(), env.nowMs(), env.activeForMs())) { env.sleep(30); continue }
                errors += "$kind:$m $msg"; notes += "$m: $msg"
                return null
            }
        }
        return null
    }

    private fun why(notes: List<String>, chosen: String): String =
        if (notes.isEmpty()) chosen else notes.joinToString("; ") + " → $chosen"
}

/**
 * Watches one call's audio paths once a second and says, once each, what a person reporting "no audio" needs to know
 * (issues #13, #14): the downlink capture delivering nothing or only zeros although the call is active, the injected
 * uplink not being taken by the phone (its play position standing still), the track not routed to the call. Pure.
 */
class PathWatch(private val testLine: Boolean, private val deadSeconds: Int = 6, private val stuckSeconds: Int = 3) {
    /**
     * One second: whether the call was active, downlink frames read and their non-zero samples, whether uplink audio
     * was waiting for the track (written this second, or queued: a track nobody takes from blocks its writer), and how
     * far the track's play position moved (samples; -1 unknown).
     */
    data class Second(val active: Boolean, val downFrames: Int, val downNonZero: Int, val upWaiting: Boolean, val upPlayed: Long)

    enum class Event(val wire: String) { DOWN_NOTHING("down-nothing"), DOWN_ZEROS("down-zeros"), UP_STUCK("up-stuck") }

    private var activeSeconds = 0
    private var downAlive = false
    private var noFrames = 0
    private var stuck = 0
    private val said = HashSet<Event>()

    /** What this second showed that was not said before (null: nothing new). */
    fun second(s: Second): Event? {
        if (!s.active) { noFrames = 0; stuck = 0; return null }
        activeSeconds++
        if (s.downNonZero >= RedirectGuard.ALIVE_SAMPLES) downAlive = true
        noFrames = if (s.downFrames == 0) noFrames + 1 else 0
        stuck = if (s.upWaiting && s.upPlayed == 0L) stuck + 1 else 0
        return when {
            noFrames >= stuckSeconds && say(Event.DOWN_NOTHING) -> Event.DOWN_NOTHING
            // The test line's far end plays nothing: zeros there are right.
            !testLine && !downAlive && activeSeconds >= deadSeconds && s.downFrames > 0 && say(Event.DOWN_ZEROS) -> Event.DOWN_ZEROS
            stuck >= stuckSeconds && say(Event.UP_STUCK) -> Event.UP_STUCK
            else -> null
        }
    }

    private fun say(e: Event) = said.add(e)

    /** The uplink moved to another injection track: its play position is watched afresh. */
    fun rearmUplink() { stuck = 0; said.remove(Event.UP_STUCK) }

    companion object {
        /**
         * The injection track is taken clearly slower than real time ([hz] measured over the active call, nominal
         * [rate]): no call's uplink runs off its own clock. A Poco X3 Pro (issue #14, Qualcomm, custom Android 16) took
         * `telephony_tx` at 14768 Hz and the far end heard nothing; working phones measure within 0.1 % (Galaxy A73
         * 15993 Hz, the reference phone 16000 Hz). Only said, never acted on: the same phone's silent calls also showed
         * 16000 Hz at times, so it is a hint for the report, not proof.
         */
        fun slowInject(hz: Double, rate: Int): Boolean = hz > 0 && hz < rate * 0.95

        fun slowText(inject: String?, routed: String?, hz: Double, rate: Int): String =
            "the uplink injection ($inject, routed $routed) is taken at about ${Math.round(hz)} Hz instead of $rate Hz: this phone " +
                "is probably not sending it into the call, so the far end may not hear the iPhone. Send a problem report from the Android phone"

        /** The link-log text for [e] with the methods in use. */
        fun text(e: Event, capture: String?, inject: String?, routed: String?): String = when (e) {
            Event.DOWN_NOTHING -> "the downlink capture ($capture) delivered nothing for 3 s of the active call: the iPhone hears nothing"
            Event.DOWN_ZEROS -> "the downlink capture ($capture) reads only zeros in the active call: this phone does not give it the call's audio (the iPhone hears nothing)"
            Event.UP_STUCK -> "the uplink injection ($inject, routed $routed) is not being taken by the phone: the far end cannot hear the iPhone"
        }
    }
}
