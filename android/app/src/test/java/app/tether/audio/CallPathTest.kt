package app.tether.audio

import app.tether.helper.InjectChoice
import app.tether.helper.InjectChoice.Route
import app.tether.helper.InjectChoice.Verdict
import app.tether.helper.MicPath.Mode
import app.tether.helper.OpenGate
import app.tether.helper.PathOpener
import app.tether.helper.UplinkRoutes
import app.tether.helper.PathWatch
import app.tether.helper.PathWatch.Event
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the helper opens a call's audio (OpenGate), what it reports about dead paths (PathWatch), when the app restarts it. */
class CallPathTest {
    private val refused = "IllegalStateException:  not available in mode MODE_NORMAL"

    @Test fun nothingOpensWhileTheCallHasNoAudioMode() {
        // 2026-11-24, reference phone: an outgoing call stayed in MODE_NORMAL for seconds of dialing; the old 4 s wait
        // ran out and the fallbacks gave a capture with 4 frames in the whole call.
        val g = OpenGate()
        assertFalse(g.ready(Mode.NORMAL, activeForMs = -1))
        assertFalse(g.ready(Mode.RINGTONE, activeForMs = -1))
        assertTrue(g.ready(Mode.IN_CALL, activeForMs = -1))
        assertTrue("the VoIP test line", g.ready(Mode.IN_COMMUNICATION, activeForMs = -1))
        assertTrue("a phone that never reports a call's mode still gets its audio once the call is up", g.ready(Mode.NORMAL, activeForMs = 3000))
        assertFalse(g.ready(Mode.NORMAL, activeForMs = 2000))
    }

    @Test fun theInterceptionApiIsRetriedQuietlyNotGivenUpForAnOlderMethod() {
        val g = OpenGate(graceMs = 2000)
        // Dialing for 30 s in NORMAL: retried all along.
        for (t in 0L..30_000L step 500) assertTrue(g.retry("intercept", refused, Mode.NORMAL, t, -1))
        // In the call's mode the API gets 2 s to follow, then the next method.
        assertTrue(g.retry("intercept", refused.replace("NORMAL", "IN_CALL"), Mode.IN_CALL, 31_000, 500))
        assertTrue(g.retry("intercept", refused, Mode.IN_CALL, 32_900, 2400))
        assertFalse(g.retry("intercept", refused, Mode.IN_CALL, 33_100, 2600))
    }

    @Test fun otherErrorsAndOtherMethodsMoveOnAtOnce() {
        val g = OpenGate()
        assertFalse("not supported at all: the older methods are what this phone has",
            g.retry("intercept", "UnsupportedOperationException: PSTN Call audio not accessible", Mode.NORMAL, 0, -1))
        assertFalse(g.retry("voice_downlink", refused, Mode.NORMAL, 0, -1))
        assertTrue(OpenGate.transient(refused))
    }

    // ------------------------------------------------------------------ path watch

    private fun sec(active: Boolean = true, down: Int = 50, nz: Int = 8000, waiting: Boolean = true, played: Long = 16000) =
        PathWatch.Second(active, down, nz, waiting, played)

    @Test fun aHealthyCallSaysNothing() {
        val w = PathWatch(testLine = false)
        assertTrue(List(30) { w.second(sec()) }.all { it == null })
    }

    @Test fun dialingIsNotWatched() {
        // While dialing the VoLTE downlink is silent and the modem takes no uplink: nothing to report.
        val w = PathWatch(testLine = false)
        assertTrue(List(30) { w.second(sec(active = false, nz = 0, played = 0)) }.all { it == null })
    }

    @Test fun aDownlinkOfZerosIsReportedOnce() {
        // Issue #13 ("no audio either way"): a capture that opens but reads only zeros.
        val w = PathWatch(testLine = false)
        val events = List(10) { w.second(sec(nz = 0)) }
        assertEquals(listOf(Event.DOWN_ZEROS), events.filterNotNull())
        assertEquals(5, events.indexOf(Event.DOWN_ZEROS))
        assertNull("not on the test line: its far end plays nothing", PathWatch(testLine = true).let { t -> List(10) { t.second(sec(nz = 0)) }.firstOrNull { it != null } })
        val alive = PathWatch(testLine = false)
        alive.second(sec()); assertTrue("had audio once: a quiet far end is no dead path", List(10) { alive.second(sec(nz = 0)) }.all { it == null })
    }

    @Test fun aCaptureThatDeliversNothing() {
        val w = PathWatch(testLine = true)
        assertEquals(listOf(Event.DOWN_NOTHING), List(5) { w.second(sec(down = 0, nz = 0)) }.filterNotNull())
    }

    @Test fun anInjectionNobodyTakes() {
        // Issue #14 (the far end does not hear the iPhone): the track's play position stands still while audio waits.
        val w = PathWatch(testLine = false)
        assertEquals(listOf(Event.UP_STUCK), List(6) { w.second(sec(played = 0)) }.filterNotNull())
        val unknown = PathWatch(testLine = false)
        assertTrue("no timestamp: nothing claimed", List(6) { unknown.second(sec(played = -1)) }.all { it == null })
        val idle = PathWatch(testLine = false)
        assertTrue("nothing to play (the iPhone sends nothing): not stuck", List(6) { idle.second(sec(waiting = false, played = 0)) }.all { it == null })
        assertTrue(PathWatch.text(Event.UP_STUCK, "intercept", "intercept", "18:Telephony").contains("the far end cannot hear the iPhone"))
    }

    // ------------------------------------------------------------------ opening: every method, each judged by its route

    /** A call in IN_CALL whose clock moves only when the opener sleeps. */
    private class Env(var now: Long = 0, private val until: Long = 60_000) : PathOpener.Env {
        override fun alive() = now < until
        override fun mode() = Mode.IN_CALL
        override fun nowMs() = now
        override fun activeForMs() = -1L
        override fun sleep(ms: Long) { now += ms }
    }

    /** A fake track: its method and the device type Android routed it to (-1: none reported). */
    private data class Track(val method: String, val routed: Int)

    private val inCall = " not available in mode MODE_IN_CALL"

    /** [behaviour]: per method, the route of the opened track or an error message; refused for the mode until [refusedUntil] ms. */
    private fun opener(env: Env, gate: OpenGate, behaviour: Map<String, Any>, refusedUntil: Map<String, Long> = emptyMap(),
                       closed: MutableList<String> = ArrayList(), onlyIfNothingElse: Set<String> = emptySet()) =
        PathOpener<Track>("inject", gate, env,
            open = { m ->
                if (env.now < (refusedUntil[m] ?: -1)) throw IllegalStateException(inCall)
                when (val b = behaviour[m]) { is Int -> Track(m, b); is String -> throw IllegalStateException(b); else -> error("no $m") }
            },
            close = { closed += it.method },
            judge = { m, t -> InjectChoice.judge(m, InjectChoice.route(t.routed, testLine = false)) to "routed ${t.routed}" },
            onlyIfNothingElse = onlyIfNothingElse)

    private fun capture(env: Env, gate: OpenGate, refusedUntil: Long) = PathOpener<Track>("capture", gate, env,
        open = { if (env.now < refusedUntil) throw IllegalStateException(inCall) else Track(it, 18) }, close = {})

    private val all = listOf("intercept", "telephony_tx", "policy_mix")

    @Test fun theInjectionGetsItsOwnGraceNotWhatTheCaptureLeft() {
        // Issue #14 (Redmi Note 10 5G, MediaTek: "helper paths open after 4791 ms", no injection at all): the capture
        // waited 1.9 s for the interception API; with one grace for both, the injection's refusal right after had 0.1 s.
        val env = Env()
        assertEquals("intercept", capture(env, OpenGate(graceMs = 2000), refusedUntil = 1900).run(listOf("intercept"), ArrayList())?.method)
        val inject = opener(env, OpenGate(graceMs = 2000), mapOf("intercept" to 18, "telephony_tx" to 18), refusedUntil = mapOf("intercept" to 3500))
        assertEquals("its own 2 s", "intercept", inject.run(all, ArrayList())?.method)
        // The 1.2.5 behaviour, for the record: one gate for both leaves the injection on the older method.
        val shared = OpenGate(graceMs = 2000)
        val env2 = Env()
        capture(env2, shared, refusedUntil = 1900).run(listOf("intercept"), ArrayList())
        assertEquals("telephony_tx", opener(env2, shared, mapOf("intercept" to 18, "telephony_tx" to 18), refusedUntil = mapOf("intercept" to 3500)).run(all, ArrayList())?.method)
    }

    @Test fun everyMethodIsTriedAndTheOneWithoutARouteIsTheLastResort() {
        // The MediaTek case as far as the evidence goes: the interception track refused, telephony_tx playing but no
        // route reported (1.2.0-1.2.5 refused it: "routed to nothing"), policy_mix refused. 1.1.0 took telephony_tx as
        // it came, and the reporter says 1.1.0 worked.
        val errors = ArrayList<String>(); val closed = ArrayList<String>()
        val o = opener(Env(), OpenGate(), mapOf("intercept" to "UnsupportedOperationException: no", "telephony_tx" to -1, "policy_mix" to "registerAudioPolicy returned -1"), closed = closed)
        val got = o.run(all, errors)
        assertEquals("telephony_tx", got?.method)
        assertEquals("closed once while the others were tried", listOf("telephony_tx"), closed)
        assertTrue(got!!.why, got.why.contains("intercept: IllegalStateException: UnsupportedOperationException: no") && got.why.contains("used anyway"))
        assertEquals(listOf("inject:intercept IllegalStateException: UnsupportedOperationException: no", "inject:policy_mix IllegalStateException: registerAudioPolicy returned -1"), errors)
        assertEquals(all, o.tried)
        // As in 1.1.0, policy_mix is not reached once telephony_tx opened (on a Galaxy A73 it was routed to the call and
        // carried nothing): the helper passes it as "only if nothing else".
        val o2 = opener(Env(), OpenGate(), mapOf("intercept" to "x", "telephony_tx" to -1, "policy_mix" to 18), onlyIfNothingElse = setOf("policy_mix"))
        assertEquals("telephony_tx", o2.run(all, ArrayList())?.method)
        assertEquals(listOf("intercept", "telephony_tx"), o2.tried)
        assertEquals("policy_mix when nothing else opened", "policy_mix",
            opener(Env(), OpenGate(), mapOf("intercept" to "x", "telephony_tx" to "no telephony output device", "policy_mix" to 18), onlyIfNothingElse = setOf("policy_mix")).run(all, ArrayList())?.method)
    }

    @Test fun aTrackRoutedElsewhereMovesOnARoutedOneIsKept() {
        val errors = ArrayList<String>()
        // The interception track on the speaker (2): the iPhone's voice would play on the phone.
        val got = opener(Env(), OpenGate(), mapOf("intercept" to 2, "telephony_tx" to 18)).run(all, errors)
        assertEquals("telephony_tx", got?.method)
        assertEquals(listOf("inject:intercept routed 2, not the call's uplink"), errors)
        assertTrue(got!!.why, got.why.endsWith("→ telephony_tx (routed 18)"))
        assertEquals("intercept without a reported route is kept, as always", "intercept", opener(Env(), OpenGate(), mapOf("intercept" to -1)).run(all, ArrayList())?.method)
        assertNull("nothing routed to the call: none", opener(Env(), OpenGate(), mapOf("intercept" to 2, "telephony_tx" to "routed to 2:Speaker", "policy_mix" to 3)).run(all, ArrayList()))
        assertEquals(Verdict.KEEP, InjectChoice.judge("telephony_tx", InjectChoice.route(25, testLine = true)))
        assertEquals(Route.ELSEWHERE, InjectChoice.route(25, testLine = false))
    }

    @Test fun theSelfTestsChoiceComesFirstButNeverAlone() {
        // A saved choice that fails in this call used to leave the call without any injection.
        assertEquals(listOf("telephony_tx", "intercept", "policy_mix"), UplinkRoutes.fixedOrAuto("telephony_tx", all))
        val got = opener(Env(), OpenGate(), mapOf("telephony_tx" to "no telephony output device", "intercept" to 18)).run(UplinkRoutes.fixedOrAuto("telephony_tx", all), ArrayList())
        assertEquals("intercept", got?.method)
    }

    @Test fun aTrackTakenSlowerThanRealTimeIsSaid() {
        // Poco X3 Pro (issue #14): telephony_tx at 14768 Hz, the far end heard nothing; working phones within 0.1 %.
        assertTrue(PathWatch.slowInject(14768.4, 16000))
        assertTrue(PathWatch.slowInject(12903.5, 16000))
        assertFalse("Galaxy A73, two-way audio", PathWatch.slowInject(15993.1, 16000))
        assertFalse("1.8 % is within what hold-ups do", PathWatch.slowInject(15707.0, 16000))
        assertFalse("not measured", PathWatch.slowInject(-1.0, 16000))
        assertTrue(PathWatch.slowText("telephony_tx", "18:Telephony", 14768.4, 16000).contains("about 14768 Hz instead of 16000 Hz"))
    }

    @Test fun aSwitchedInjectionIsWatchedAfresh() {
        val w = PathWatch(testLine = false)
        assertEquals(listOf(Event.UP_STUCK), List(6) { w.second(sec(played = 0)) }.filterNotNull())
        w.rearmUplink()
        assertEquals("the next track stuck too: said again", listOf(Event.UP_STUCK), List(6) { w.second(sec(played = 0)) }.filterNotNull())
    }

    // ------------------------------------------------------------------ app: restart at most once

    private fun info(capture: String, inject: String, vararg errors: String) =
        JSONObject().put("capture", capture).put("inject", inject).put("errors", JSONArray(errors.toList()))

    @Test fun workingAudioIsNeverRestarted() {
        assertFalse(AudioRestart.wanted(34, info("intercept", "intercept"), "auto", 0, 10_000, true))
        // Issue #15: a phone without the interception API runs on the older methods: restarting only cut working audio.
        assertFalse(AudioRestart.wanted(34, info("voice_downlink", "telephony_tx", "inject:intercept UnsupportedOperationException: PSTN Call audio not accessible"), "auto", 0, 10_000, true))
        assertFalse("Android 11-12 have no interception API", AudioRestart.wanted(31, info("voice_downlink", "telephony_tx"), "auto", 0, 10_000, true))
    }

    @Test fun oneRestartForARefusalInTheCallsOwnMode() {
        val fell = info("voice_downlink", "telephony_tx", "capture:intercept $refused", "inject:intercept $refused")
        assertFalse("not before 3 s", AudioRestart.wanted(34, fell, "auto", 0, 2000, true))
        assertFalse("only for an active call", AudioRestart.wanted(34, fell, "auto", 0, 5000, false))
        assertTrue(AudioRestart.wanted(34, fell, "auto", 0, 5000, true))
        assertFalse("once", AudioRestart.wanted(34, fell, "auto", 1, 60_000, true))
        assertFalse("the self-test chose an older method: never overridden", AudioRestart.wanted(34, fell, "telephony_tx", 0, 5000, true))
        assertFalse("no INFO of this call's start yet", AudioRestart.wanted(34, null, "auto", 0, 5000, true))
    }
}
