package app.tether.helper

import app.tether.helper.AudioMethods.Dir
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-model call-audio method memory (docs/CALL-AUDIO.md): pick order, skip lists, feedback, self-test. */
class MethodChoiceTest {
    private val sdk = 33

    @Test fun emptyStateIsAutoWithoutSkips() {
        val s = MethodChoice.State()
        assertEquals("auto", MethodChoice.pick(sdk, s, Dir.INJECT))
        assertEquals("auto", MethodChoice.pick(sdk, s, Dir.CAPTURE))
        assertTrue(MethodChoice.skip(sdk, s, Dir.INJECT).isEmpty())
    }

    @Test fun manualPickWinsAndClears() {
        var s = MethodChoice.State(chosenInject = "intercept")
        s = MethodChoice.withManual(s, sdk, Dir.INJECT, "telephony_tx")
        assertEquals("telephony_tx", MethodChoice.pick(sdk, s, Dir.INJECT))
        assertTrue(MethodChoice.skip(sdk, s, Dir.INJECT).isEmpty())
        s = MethodChoice.withManual(s, sdk, Dir.INJECT, "auto")
        assertNull(s.manualInject)
        assertEquals("intercept", MethodChoice.pick(sdk, s, Dir.INJECT))
        // An id this Android does not offer is not a pick.
        s = MethodChoice.withManual(s, 30, Dir.INJECT, "policy_mix")
        assertNull(s.manualInject)
    }

    @Test fun chosenSurvivesOnlyWhileOffered() {
        val s = MethodChoice.State(chosenInject = "policy_mix", chosenCapture = "intercept")
        assertEquals("policy_mix", MethodChoice.pick(33, s, Dir.INJECT))
        assertEquals("auto", MethodChoice.pick(30, s, Dir.INJECT))
        assertEquals("auto", MethodChoice.pick(30, s, Dir.CAPTURE))
    }

    @Test fun feedbackVotesAMethodDownAndDropsItsChosenPlace() {
        var s = MethodChoice.State(chosenInject = "intercept", chosenCapture = "voice_downlink")
        s = MethodChoice.recordFeedback(s, injectId = "intercept", captureId = "voice_downlink", heardYou = false, heardThem = true)
        assertTrue(MethodChoice.failed(s, Dir.INJECT, "intercept"))
        assertFalse(MethodChoice.failed(s, Dir.CAPTURE, "voice_downlink"))
        assertNull(s.chosenInject)   // re-picks at once
        assertEquals("voice_downlink", s.chosenCapture)
        assertEquals("auto", MethodChoice.pick(sdk, s, Dir.INJECT))
        assertEquals(listOf("intercept"), MethodChoice.skip(sdk, s, Dir.INJECT))
        // A later "heard" balances the vote again.
        s = MethodChoice.recordFeedback(s, injectId = "intercept", captureId = null, heardYou = true, heardThem = true)
        assertFalse(MethodChoice.failed(s, Dir.INJECT, "intercept"))
        assertTrue(MethodChoice.skip(sdk, s, Dir.INJECT).isEmpty())
    }

    @Test fun skipNeverEmptiesTheLadder() {
        var s = MethodChoice.State()
        for (id in listOf("intercept", "telephony_tx", "policy_mix"))
            s = MethodChoice.recordFeedback(s, injectId = id, captureId = null, heardYou = false, heardThem = true)
        assertTrue(MethodChoice.skip(sdk, s, Dir.INJECT).isEmpty())
        assertEquals("auto", MethodChoice.pick(sdk, s, Dir.INJECT))
    }

    @Test fun selftestRecordsVerdictsWinnersAndClearsVotes() {
        var s = MethodChoice.State()
        s = MethodChoice.recordFeedback(s, injectId = "telephony_tx", captureId = null, heardYou = false, heardThem = true)
        assertTrue(MethodChoice.failed(s, Dir.INJECT, "telephony_tx"))
        s = MethodChoice.recordSelftest(s,
            injectOk = mapOf("intercept" to false, "telephony_tx" to true),
            captureOk = mapOf("intercept" to true, "voice_downlink" to false),
            chosenInject = "telephony_tx", chosenCapture = "intercept")
        assertFalse(MethodChoice.failed(s, Dir.INJECT, "telephony_tx"))   // the tone outweighs one bad call
        assertEquals(false, s.tested["inject:intercept"])
        assertEquals(true, s.tested["inject:telephony_tx"])
        assertEquals(true, s.tested["capture:intercept"])
        assertEquals("telephony_tx", MethodChoice.pick(sdk, s, Dir.INJECT))
        assertEquals("intercept", MethodChoice.pick(sdk, s, Dir.CAPTURE))
    }

    @Test fun jsonRoundTrip() {
        val s = MethodChoice.State(manualInject = "telephony_tx", chosenCapture = "intercept",
            tested = mapOf("inject:intercept" to true), heard = mapOf("capture:intercept" to 2), notHeard = mapOf("inject:intercept" to 1))
        assertEquals(s, MethodChoice.parse(MethodChoice.toJson(s)))
        assertEquals(MethodChoice.State(), MethodChoice.parse(null))
        assertEquals(MethodChoice.State(), MethodChoice.parse(JSONObject("{}")))
    }

    @Test fun feedbackForUnknownMethodsIsIgnored() {
        val s = MethodChoice.recordFeedback(MethodChoice.State(), injectId = "phone", captureId = "nope", heardYou = false, heardThem = false)
        assertEquals(MethodChoice.State(), s)
    }
}
