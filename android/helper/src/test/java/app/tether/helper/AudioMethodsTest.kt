package app.tether.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The call-audio method registry (docs/CALL-AUDIO.md): the ladders are what the helper has always tried. */
class AudioMethodsTest {
    @Test fun laddersBySdk() {
        assertEquals(listOf("intercept", "voice_downlink", "telephony_rx", "voice_call"),
            AudioMethods.captureLadder(33).map { it.id })
        assertEquals(listOf("voice_downlink", "voice_call", "telephony_rx"),
            AudioMethods.captureLadder(32).map { it.id })
        assertEquals(listOf("intercept", "telephony_tx", "policy_mix"),
            AudioMethods.injectLadder(35).map { it.id })
        assertEquals(listOf("telephony_tx"), AudioMethods.injectLadder(30).map { it.id })
        assertTrue(AudioMethods.captureLadder(29).isEmpty())
        assertTrue(AudioMethods.injectLadder(29).isEmpty())
    }

    @Test fun availableIsTheTwoLadders() {
        assertEquals(AudioMethods.captureLadder(33) + AudioMethods.injectLadder(33), AudioMethods.available(33))
        assertEquals(AudioMethods.captureLadder(30) + AudioMethods.injectLadder(30), AudioMethods.available(30))
    }

    @Test fun idsRepeatAcrossDirectionsOnlyForIntercept() {
        assertEquals(AudioMethods.INTERCEPT_CAPTURE, AudioMethods.byId(AudioMethods.Dir.CAPTURE, "intercept"))
        assertEquals(AudioMethods.INTERCEPT_INJECT, AudioMethods.byId(AudioMethods.Dir.INJECT, "intercept"))
        assertNull(AudioMethods.byId(AudioMethods.Dir.CAPTURE, "telephony_tx"))
        assertNull(AudioMethods.byId(AudioMethods.Dir.INJECT, "voice_call"))
        assertNull(AudioMethods.byId(AudioMethods.Dir.INJECT, AudioMethods.FALLBACK))
        assertNull(AudioMethods.byId(AudioMethods.Dir.INJECT, "nonsense"))
    }

    @Test fun interceptNeedsTheNewApi() {
        assertTrue(AudioMethods.INTERCEPT_CAPTURE.interceptApi && AudioMethods.INTERCEPT_CAPTURE.minSdk == 33)
        assertTrue(AudioMethods.available(32).none { it.interceptApi })
    }
}
