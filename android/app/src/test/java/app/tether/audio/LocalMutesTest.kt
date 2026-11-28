package app.tether.audio

import app.tether.helper.CallAudio.Silence
import app.tether.helper.LocalMutes
import app.tether.helper.LocalMutes.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the phone's own mutes a bridged call uses (issue #2: a muted uplink on a Galaxy Z Fold 6). */
class LocalMutesTest {
    private fun plan(s: Silence, setting: Boolean, memory: Memory?) =
        LocalMutes.plan(s, LocalMutes.wantMicMute(setting, memory), LocalMutes.wantCheck(memory))

    @Test fun byDefaultNoVendorMutesTheMicrophone() {
        for (s in Silence.entries) {
            val p = plan(s, setting = false, memory = null)
            assertFalse("$s", p.micMuted)
            assertFalse("$s: nothing to check", p.check)
            assertEquals("$s: earpiece only", "earpiece", LocalMutes.silent(p, ok = true))
        }
    }

    @Test fun onRequestEachVendorUsesExactlyOneMicMute() {
        val samsung = plan(Silence.SAMSUNG, true, null)
        assertTrue(samsung.vendorMic); assertFalse("never also Android's mute", samsung.telecomMic)
        val mediatek = plan(Silence.MEDIATEK, true, null)
        assertTrue(mediatek.vendorMic); assertFalse("Android's mute kills MediaTek's whole uplink", mediatek.telecomMic)
        val other = plan(Silence.OTHER, true, null)
        assertFalse("no key of its own", other.vendorMic); assertTrue(other.telecomMic)
        for (s in Silence.entries.filter { LocalMutes.canMute(it) }) {
            assertTrue("$s: the uplink is checked", plan(s, true, null).check)
            assertEquals("full", LocalMutes.silent(plan(s, true, null), ok = true))
        }
    }

    @Test fun aRememberedBreakKeepsTheMicOnEvenWithTheSetting() {
        for (s in Silence.entries) assertEquals(LocalMutes.Plan.NONE, plan(s, setting = true, memory = Memory.BREAKS_UPLINK))
    }

    @Test fun qualcommIsNeverMutedThroughAndroid() {
        // Redmi Note 8 (trinket): VOICE_UPLINK heard the iPhone with Android's mic mute on, the far end heard nothing.
        // Its HAL mutes the whole Tx path, injected voice included: no check result may switch that mute on.
        assertFalse(LocalMutes.canMute(Silence.QUALCOMM))
        for (m in listOf(null, Memory.OK, Memory.UNVERIFIED)) {
            assertEquals("$m", LocalMutes.Plan.NONE, plan(Silence.QUALCOMM, setting = true, memory = m))
            assertEquals("$m", LocalMutes.Plan.NONE, LocalMutes.manualPlan(Silence.QUALCOMM, m))
        }
        assertEquals("earpiece", LocalMutes.silent(LocalMutes.manualPlan(Silence.QUALCOMM, Memory.OK), ok = true))
        // Samsung's Qualcomm models: no Samsung keys since 1.2.4 (#13), and the same Qualcomm Tx mute underneath.
        assertFalse(LocalMutes.canMute(Silence.SAMSUNG_QUALCOMM))
        assertEquals(LocalMutes.Plan.NONE, LocalMutes.manualPlan(Silence.SAMSUNG_QUALCOMM, null))
        for (s in listOf(Silence.SAMSUNG, Silence.MEDIATEK, Silence.OTHER)) assertTrue("$s", LocalMutes.canMute(s))
    }

    @Test fun qualcommsInCallMusicKeepsTheInjectedVoiceUnderAndroidsMute() {
        // Issue #13 / #25 (Galaxy A73 / A52s, SM7325): the far end heard itself through the open microphone. With the
        // in-call music injection Qualcomm's HAL mutes only the mic device; the A73's self-test heard telephony_tx at the
        // same level with the mute on. Only with the user's "Muted", and checked until it held once.
        for (s in listOf(Silence.QUALCOMM, Silence.SAMSUNG_QUALCOMM)) {
            assertTrue("$s", LocalMutes.canMute(s, "telephony_tx"))
            assertFalse("$s: not with the interception track (Redmi Note 8)", LocalMutes.canMute(s, "intercept"))
            assertFalse("$s", LocalMutes.canMute(s, "policy_mix"))
            val p = LocalMutes.manualPlan(s, null, "telephony_tx")
            assertTrue("$s: Android's mute", p.telecomMic); assertFalse(p.vendorMic); assertTrue("$s: checked", p.check)
            assertFalse("$s: no check once it held", LocalMutes.manualPlan(s, Memory.OK, "telephony_tx").check)
            assertEquals("$s: a remembered break wins", LocalMutes.Plan.NONE, LocalMutes.manualPlan(s, Memory.BREAKS_UPLINK, "telephony_tx"))
            assertEquals("$s: never by default", LocalMutes.Plan.NONE, LocalMutes.autoPlan(s, null))
        }
        assertFalse("MediaTek: Android's mute is the whole uplink", LocalMutes.telecomKeepsInCallMusic(Silence.MEDIATEK, "telephony_tx"))
    }

    @Test fun aRememberedSuccessSkipsTheCheck() {
        val p = plan(Silence.OTHER, setting = true, memory = Memory.OK)
        assertTrue(p.telecomMic)
        assertFalse(p.check)
    }

    @Test fun undoingClearsEveryMicMute() {
        for (s in Silence.entries) {
            val u = LocalMutes.undone(plan(s, true, null))
            assertFalse("$s", u.micMuted)
            assertEquals("earpiece", LocalMutes.silent(u, ok = true))
        }
        assertEquals("none", LocalMutes.silent(LocalMutes.Plan.NONE, ok = false))
    }

    @Test fun theAutomaticLayerUsesOnlyTheVendorsMicSourceKey() {
        // Default setting, no redirect mode: the vendor's key, checked while untested; never Android's mic mute.
        for (s in listOf(Silence.SAMSUNG, Silence.MEDIATEK)) {
            val p = LocalMutes.autoPlan(s, null)
            assertTrue("$s", p.vendorMic); assertFalse("$s", p.telecomMic); assertTrue("$s: checked", p.check)
            assertFalse("$s: seen working", LocalMutes.autoPlan(s, Memory.OK).check)
            assertEquals("$s: silenced the uplink before", LocalMutes.Plan.NONE, LocalMutes.autoPlan(s, Memory.BREAKS_UPLINK))
        }
        for (s in listOf(Silence.SAMSUNG_QUALCOMM, Silence.QUALCOMM, Silence.OTHER)) assertEquals("$s: no key used, open", LocalMutes.Plan.NONE, LocalMutes.autoPlan(s, null))
    }

    @Test fun aBlindUplinkKeepsOnlyTheTrustedKey() {
        // The A54 (Samsung, Exynos): VOICE_UPLINK cannot show the injection, the tx key is known to keep it: muted,
        // no check (which would only undo it every call). Others stay open rather than risk a silent far end.
        val a54 = LocalMutes.autoPlan(Silence.SAMSUNG, Memory.UNVERIFIED)
        assertTrue(a54.vendorMic); assertFalse(a54.check)
        for (s in listOf(Silence.SAMSUNG_QUALCOMM, Silence.MEDIATEK)) assertEquals("$s", LocalMutes.Plan.NONE, LocalMutes.autoPlan(s, Memory.UNVERIFIED))
        assertTrue(LocalMutes.keepWhenBlind(LocalMutes.autoPlan(Silence.SAMSUNG, null), Silence.SAMSUNG))
        assertFalse(LocalMutes.keepWhenBlind(LocalMutes.autoPlan(Silence.MEDIATEK, null), Silence.MEDIATEK))
        assertFalse("Android's mute is never trusted", LocalMutes.keepWhenBlind(LocalMutes.plan(Silence.OTHER, true, true), Silence.OTHER))
    }

    @Test fun theUsersMuteSetting() {
        val samsung = LocalMutes.manualPlan(Silence.SAMSUNG, null)
        assertTrue(samsung.vendorMic && samsung.check)
        assertFalse("blind, trusted key: no more checks", LocalMutes.manualPlan(Silence.SAMSUNG, Memory.UNVERIFIED).check)
        val other = LocalMutes.manualPlan(Silence.OTHER, Memory.UNVERIFIED)
        assertTrue("Android's mute on request, still watched", other.telecomMic && other.check)
        assertEquals(LocalMutes.Plan.NONE, LocalMutes.manualPlan(Silence.SAMSUNG, Memory.BREAKS_UPLINK))
    }

    @Test fun memoryWireNames() {
        assertEquals(Memory.UNVERIFIED, Memory.of("unverified"))
        assertEquals(Memory.BREAKS_UPLINK, Memory.of("muted-locally-fixed"))
        assertEquals(Memory.OK, Memory.of("ok"))
        assertNull(Memory.of(null))
        assertNull(Memory.of("checking"))
    }
}
