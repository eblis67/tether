package app.tether.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a call's audio goes (protocol §5.1), issue #13: a call taken on the Android phone stays on it. */
class AudioOwnerTest {
    private class Client(val key: String, val name: String)
    private val iphone = Client("aa", "iPhone")
    private val second = Client("bb", "Pixel")
    private val live = listOf(iphone, second)

    @Test fun aCallNobodyClaimedStaysOnTheAndroidPhone() {
        // Answered on the phone's own screen, a headset or a car connected to it: before 1.2.2 the iPhone got the
        // audio and the phone's earpiece and mic were muted, so the call had no audio where it was taken.
        assertNull(AudioOwner.pick(live, { it.key }, claimedKey = null))
        assertTrue(AudioOwner.elsewhere("active", owner = null, client = iphone, blocked = false))
        assertEquals("AMIR's A73", AudioOwner.where(null, "AMIR's A73"))
    }

    @Test fun aShortLinkDropKeepsTheCallBridged() {
        // Issue #13 (Galaxy A73): the link dropped mid-call and the iPhone was back 6.3 s later; the audio stopped at
        // once, so the call jumped to the phone's loud earpiece meanwhile.
        assertTrue(AudioOwner.holdForReconnect(running = true, targetUp = false, wantsAudio = true, paused = false, blocked = false, lostForMs = 6300))
        assertFalse("not for ever", AudioOwner.holdForReconnect(true, false, true, false, false, AudioOwner.HOLD_MS))
        assertFalse("the call ended", AudioOwner.holdForReconnect(true, false, false, false, false, 1000))
        assertFalse("the link is up: nothing to wait for", AudioOwner.holdForReconnect(true, true, true, false, false, 1000))
        assertFalse("a self-test paused it", AudioOwner.holdForReconnect(true, false, true, true, false, 1000))
        assertFalse("this phone gives no call audio", AudioOwner.holdForReconnect(true, false, true, false, true, 1000))
        assertFalse("nothing bridged", AudioOwner.holdForReconnect(false, false, true, false, false, 1000))
        assertFalse("not lost", AudioOwner.holdForReconnect(true, false, true, false, false, -1))
    }

    @Test fun theClientThatClaimedGetsItBackAfterReconnecting() {
        val again = Client("aa", "iPhone")   // a new link from the same iPhone (same static key)
        assertSame(again, AudioOwner.pick(listOf(second, again), { it.key }, claimedKey = "aa"))
        assertNull("not while it is away", AudioOwner.pick(listOf(second), { it.key }, claimedKey = "aa"))
    }

    @Test fun aClaimForOneCallSaysNothingAboutAnother() {
        // Call waiting: the iPhone answered call A; call B is answered on the Android phone itself. With the
        // old single claim the iPhone was told B was its call too (not "elsewhere"), performed an answer for
        // it and took its audio — a call nobody had given it.
        var now = 0L
        val claims = CallClaims { now }
        claims.claim("aa", "call-a")
        assertEquals("aa", claims.keyFor("call-a", outgoing = false))
        assertNull("answered on the phone itself: no claim, no owner", claims.keyFor("call-b", outgoing = false))
        claims.claim("bb", "call-b")
        assertEquals("bb", claims.keyFor("call-b", outgoing = false))
        assertEquals("the first call keeps its own claim", "aa", claims.keyFor("call-a", outgoing = false))
        claims.prune(setOf("call-b"))
        assertNull("a claim is forgotten with its call", claims.keyFor("call-a", outgoing = false))
        assertEquals("bb", claims.keyFor("call-b", outgoing = false))
    }

    @Test fun aDialClaimBindsToTheOutgoingCallThatShowsUp() {
        var now = 1_000L
        val claims = CallClaims { now }
        claims.claim("aa", cid = null)   // dialed: no call id yet
        assertEquals("aa", claims.keyFor("call-9", outgoing = true))
        assertEquals("bound now, not just remembered", "aa", claims.keyFor("call-9", outgoing = true))
        assertNull("an incoming call never takes a dial claim", claims.keyFor("call-10", outgoing = false))
        claims.claim("aa", cid = null)
        now += CallClaims.DIAL_CLAIM_MS + 1
        assertNull("a dial nothing came of expires", claims.keyFor("call-11", outgoing = true))
    }

    @Test fun elsewhereFromEachClientsView() {
        assertFalse("the owner itself", AudioOwner.elsewhere("active", iphone, iphone, blocked = false))
        assertTrue("another client", AudioOwner.elsewhere("active", iphone, second, blocked = false))
        assertEquals("iPhone", AudioOwner.where(iphone.name, "A73"))
        for (s in listOf("ringing", "ended")) assertFalse("every client rings; an ended call is nowhere", AudioOwner.elsewhere(s, null, iphone, blocked = false))
        assertTrue("dialed on the phone itself", AudioOwner.elsewhere("dialing", null, iphone, blocked = false))
    }

    @Test fun aPhoneThatGivesNoCallAudioKeepsEveryCall() {
        assertTrue("even for the client that answered", AudioOwner.elsewhere("active", iphone, iphone, blocked = true))
        assertFalse(AudioOwner.elsewhere("ringing", iphone, iphone, blocked = true))
    }

    @Test fun blockedIsRememberedPerFirmware() {
        assertTrue(AudioOwner.blocked("samsung/a73xq/a73xq:16/X/1:user/release-keys", "samsung/a73xq/a73xq:16/X/1:user/release-keys"))
        assertFalse("a system update clears it", AudioOwner.blocked("samsung/a73xq/a73xq:16/X/1:user/release-keys", "samsung/a73xq/a73xq:16/Y/2:user/release-keys"))
        assertFalse(AudioOwner.blocked(null, "x"))
    }

    @Test fun elsewhereFromEachClientsView() {
        assertFalse("the owner itself", AudioOwner.elsewhere("active", iphone, iphone, blocked = false))
        assertTrue("another client", AudioOwner.elsewhere("active", iphone, second, blocked = false))
        assertEquals("iPhone", AudioOwner.where(iphone.name, "A73"))
        for (s in listOf("ringing", "ended")) assertFalse("every client rings; an ended call is nowhere", AudioOwner.elsewhere(s, null, iphone, blocked = false))
        assertTrue("dialed on the phone itself", AudioOwner.elsewhere("dialing", null, iphone, blocked = false))
    }

    @Test fun aPhoneThatGivesNoCallAudioKeepsEveryCall() {
        assertTrue("even for the client that answered", AudioOwner.elsewhere("active", iphone, iphone, blocked = true))
        assertFalse(AudioOwner.elsewhere("ringing", iphone, iphone, blocked = true))
    }

    @Test fun theCallScreenCanBeHiddenOnlyForBridgedCalls() {
        assertTrue("default: shown", AudioOwner.showsPhoneScreen(true, owner = iphone, blocked = false))
        assertFalse(AudioOwner.showsPhoneScreen(false, owner = iphone, blocked = false))
        assertTrue("answered on the phone itself", AudioOwner.showsPhoneScreen(false, owner = null as Client?, blocked = false))
        assertTrue("the phone gives no call audio", AudioOwner.showsPhoneScreen(false, owner = iphone, blocked = true))
    }

    @Test fun blockedIsRememberedPerFirmware() {
        assertTrue(AudioOwner.blocked("samsung/a73xq/a73xq:16/X/1:user/release-keys", "samsung/a73xq/a73xq:16/X/1:user/release-keys"))
        assertFalse("a system update clears it", AudioOwner.blocked("samsung/a73xq/a73xq:16/X/1:user/release-keys", "samsung/a73xq/a73xq:16/Y/2:user/release-keys"))
        assertFalse(AudioOwner.blocked(null, "x"))
        assertFalse("a new Tether version tries again", AudioOwner.blocked("fp|1020299", "fp|1020499"))
        assertTrue(AudioOwner.blocked("fp|1020499", "fp|1020499"))
    }
}
