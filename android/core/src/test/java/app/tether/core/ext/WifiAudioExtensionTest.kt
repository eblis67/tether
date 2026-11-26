package app.tether.core.ext

import app.tether.core.Bytes
import app.tether.core.Message
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `x.tether.wifiaudio` (protocol §7.11): the policy matrix, the key derivation pinned against the Swift side's,
 * the channel over a loopback socket, and the negotiation through two linked registries — the counterpart of
 * the Swift `WifiAudioTests`.
 */
class WifiAudioExtensionTest {
    private val th = ByteArray(32) { (it * 7 + 1).toByte() }
    private val cid = "AAECAwQFBgcICQoLDA0ODw"   // raw bytes 0..15
    private val key1 = ByteArray(16) { 0x11 }
    private val key2 = ByteArray(16) { 0x22 }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun await(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    // ------------------------------------------------------------------ keys (pinned: the Swift test asserts the same bytes)

    @Test fun keyDerivationIsPinned() {
        val keys = WifiAudioKeys.derive(th, cid, key1, key2)
        assertEquals("aab8850e8726d66344cb974b9d3d96a7d8d6683944b67a5ca996273ab1fedc99", hex(keys.first))
        assertEquals("fa57d79d9b113f72ee7500e61cae8599f971f34124bd48fd684200aebdd808eb", hex(keys.second))
        // A different channel id derives different keys, and the files derivation never coincides.
        val other = WifiAudioKeys.derive(th, Bytes.b64url(ByteArray(16) { (it + 9).toByte() }), key1, key2)
        assertFalse(keys.first.contentEquals(other.first))
        val files = FilesKeys.derive(th, cid, key1, key2)
        assertFalse(keys.first.contentEquals(files.first))
    }

    @Test fun codecRoundTrips() {
        val offer = WifiAudioCodec.parseOffer(WifiAudioCodec.offer(WifiAudioCodec.Offer(cid, key1)))
        assertEquals(cid, offer.cid)
        assertArrayEquals(key1, offer.key1)
        val accept = WifiAudioCodec.parseAccept(WifiAudioCodec.accept(WifiAudioCodec.Accept(cid, "192.168.43.1", 41002, key2)))
        assertEquals(cid, accept.cid)
        assertEquals("192.168.43.1", accept.host)
        assertEquals(41002, accept.port)
        assertArrayEquals(key2, accept.key2)
        assertEquals(cid to "busy", WifiAudioCodec.parseNote(WifiAudioCodec.note(cid, "why", "busy"), "why"))
        assertEquals(cid to null, WifiAudioCodec.parseNote(WifiAudioCodec.note(cid, "why", null), "why"))
    }

    // ------------------------------------------------------------------ policy

    @Test fun offerPolicy() {
        val t = 100_000L
        assertTrue(WifiAudioPolicy.shouldOffer(true, true, true, false, 0, null, t))
        assertFalse(WifiAudioPolicy.shouldOffer(false, true, true, false, 0, null, t))
        assertFalse(WifiAudioPolicy.shouldOffer(true, false, true, false, 0, null, t))
        assertFalse(WifiAudioPolicy.shouldOffer(true, true, false, false, 0, null, t))
        assertFalse(WifiAudioPolicy.shouldOffer(true, true, true, true, 0, null, t))
        assertFalse(WifiAudioPolicy.shouldOffer(true, true, true, false, WifiAudioPolicy.MAX_ATTEMPTS_PER_CALL, null, t))
        assertFalse(WifiAudioPolicy.shouldOffer(true, true, true, false, 1, t - 1_000, t))
        assertTrue(WifiAudioPolicy.shouldOffer(true, true, true, false, 1, t - WifiAudioPolicy.RETRY_DELAY_MS, t))
    }

    @Test fun sendPathAndDeath() {
        val t = 100_000L
        assertEquals(WifiAudioPolicy.Path.WIFI, WifiAudioPolicy.sendPath(true, t - 1_000, t))
        assertEquals(WifiAudioPolicy.Path.WIFI, WifiAudioPolicy.sendPath(true, t - WifiAudioPolicy.HEARD_TIMEOUT_MS, t))
        assertEquals(WifiAudioPolicy.Path.BLUETOOTH, WifiAudioPolicy.sendPath(true, t - WifiAudioPolicy.HEARD_TIMEOUT_MS - 1, t))
        assertEquals(WifiAudioPolicy.Path.BLUETOOTH, WifiAudioPolicy.sendPath(true, null, t))
        assertEquals(WifiAudioPolicy.Path.BLUETOOTH, WifiAudioPolicy.sendPath(false, t, t))
        assertFalse(WifiAudioPolicy.channelDead(t - WifiAudioPolicy.DEAD_TIMEOUT_MS, t))
        assertTrue(WifiAudioPolicy.channelDead(t - WifiAudioPolicy.DEAD_TIMEOUT_MS - 1, t))
        assertTrue(WifiAudioPolicy.channelDead(null, t))
        assertTrue(WifiAudioPolicy.pingDue(null, t))
        assertFalse(WifiAudioPolicy.pingDue(t - 1_000, t))
        assertTrue(WifiAudioPolicy.pingDue(t - WifiAudioPolicy.PING_INTERVAL_MS, t))
    }

    @Test fun declineReasons() {
        assertEquals("dry", WifiAudioPolicy.declineReason(dry = true, channelOpen = false, bridging = true, hasAddress = true))
        assertEquals("busy", WifiAudioPolicy.declineReason(dry = false, channelOpen = true, bridging = true, hasAddress = true))
        assertEquals("unavailable", WifiAudioPolicy.declineReason(dry = false, channelOpen = false, bridging = false, hasAddress = true))
        assertEquals("unavailable", WifiAudioPolicy.declineReason(dry = false, channelOpen = false, bridging = true, hasAddress = false))
        assertNull(WifiAudioPolicy.declineReason(dry = false, channelOpen = false, bridging = true, hasAddress = true))
        assertEquals("dry", WifiAudioPolicy.declineReason(dry = true, channelOpen = true, bridging = false, hasAddress = false))
    }

    // ------------------------------------------------------------------ the channel over loopback

    @Test fun channelCarriesAudioBothWaysAndAnswersPings() {
        val keys = WifiAudioKeys.derive(th, cid, key1, key2)
        val clockMs = java.util.concurrent.atomic.AtomicLong(1_000_000)
        val clock = { clockMs.get() }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val client = WifiAudioChannel(keys, cid, initiator = true, now = clock)
        val phone = WifiAudioChannel(keys, cid, initiator = false, now = clock)
        val phoneFrames = Collections.synchronizedList(ArrayList<ByteArray>())
        val clientFrames = Collections.synchronizedList(ArrayList<ByteArray>())
        val phoneEnd = AtomicReference<WifiAudioEnd>()
        val phoneLatch = CountDownLatch(1)
        Thread {
            phone.run(server.accept(), { phoneFrames.add(it) }, { e -> phoneEnd.set(e); phoneLatch.countDown() })
        }.apply { isDaemon = true }.start()
        Thread {
            client.run(Socket(InetAddress.getByName("127.0.0.1"), server.localPort), { clientFrames.add(it) }, { })
        }.apply { isDaemon = true }.start()

        // Sends gate on the stored wire, set only after HELLO is written: a ping that goes out proves
        // the greeting is on the wire, and its PONG proves the phone read it first.
        clockMs.set(1_000_500)
        await("greeting on the wire") { client.sendPing() }
        await("pong measured") { client.lastPongRttMs() != null }
        assertEquals(0L, client.lastPongRttMs())
        val frame = byteArrayOf(9, 8, 7)
        val back = byteArrayOf(1, 2)
        assertTrue(client.sendAudio(frame))
        assertTrue(phone.sendAudio(back))
        await("frames both ways") { phoneFrames.size == 1 && clientFrames.size == 1 }
        assertArrayEquals(frame, phoneFrames[0])
        assertArrayEquals(back, clientFrames[0])
        assertTrue(client.sendPing())
        await("pong measured") { client.lastPongRttMs() != null }
        assertEquals(0L, client.lastPongRttMs())
        client.close()
        assertTrue(phoneLatch.await(5, TimeUnit.SECONDS))
        assertTrue(phoneEnd.get() is WifiAudioEnd.Bye)
        phone.close()
        server.close()
    }

    @Test fun channelWithWrongKeysDiesAsProtocol() {
        val keys = WifiAudioKeys.derive(th, cid, key1, key2)
        val wrong = WifiAudioKeys.derive(th, Bytes.b64url(ByteArray(16) { (it + 9).toByte() }), key1, key2)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val client = WifiAudioChannel(keys, cid, initiator = true, now = { 0L })
        val phone = WifiAudioChannel(wrong, cid, initiator = false, now = { 0L })
        val phoneEnd = AtomicReference<WifiAudioEnd>()
        val phoneLatch = CountDownLatch(1)
        Thread {
            phone.run(server.accept(), { throw AssertionError("no frame may pass") }, { e -> phoneEnd.set(e); phoneLatch.countDown() })
        }.apply { isDaemon = true }.start()
        Thread {
            client.run(Socket(InetAddress.getByName("127.0.0.1"), server.localPort), { }, { })
        }.apply { isDaemon = true }.start()
        assertTrue(phoneLatch.await(5, TimeUnit.SECONDS))
        val end = phoneEnd.get()
        assertTrue(end is WifiAudioEnd.Failed && end.code == "protocol")
        client.close()
        server.close()
    }

    // ------------------------------------------------------------------ negotiation through two registries

    class PipeLink(override val peerName: String, override val peerKind: String) : ExtensionLink {
        override val transport = "memory"
        lateinit var deliver: (Message) -> Unit
        override fun send(m: Message): Boolean { deliver(m); return true }
    }

    /** Two registries wired to each other, both speaking only `x.tether.wifiaudio`. */
    private inner class Linked {
        val aExt = WifiAudioExtension(keyMaterial = { th })
        val bExt = WifiAudioExtension(keyMaterial = { th })
        val aReg = ExtensionRegistry()
        val bReg = ExtensionRegistry()
        val aLink = PipeLink("Phone", "phone")
        val bLink = PipeLink("iPhone", "iphone")
        init {
            aReg.register(aExt); aReg.start(); aReg.linkUp(aLink)
            bReg.register(bExt); bReg.start(); bReg.linkUp(bLink)
            aLink.deliver = { m -> bReg.route(bLink, m) }
            bLink.deliver = { m -> aReg.route(aLink, m) }
            aReg.peerCaps(aLink, JSONArray(bReg.caps()))
            bReg.peerCaps(bLink, JSONArray(aReg.caps()))
        }
        fun aSession() = aReg.sessions(aExt).single()
        fun bSession() = bReg.sessions(bExt).single()
    }

    @Test fun offerAcceptDerivesTheSameKeysOnBothSides() {
        val p = Linked()
        var phoneKeys: Pair<ByteArray, ByteArray>? = null
        var clientKeys: Pair<ByteArray, ByteArray>? = null
        var acceptedHost: String? = null
        var acceptedPort = -1
        p.bExt.onOffer = { session, offer, message ->
            phoneKeys = p.bExt.accept(session, offer, message, "127.0.0.1", InetAddress.getByName("127.0.0.1"))
        }
        p.aExt.onAccepted = { _, accept, keys ->
            clientKeys = keys; acceptedHost = accept.host; acceptedPort = accept.port
        }
        assertNotNull(p.aExt.offer(p.aSession()))
        assertEquals("127.0.0.1", acceptedHost)
        assertTrue(acceptedPort > 0)
        assertNotNull(phoneKeys)
        assertArrayEquals(phoneKeys!!.first, clientKeys!!.first)
        assertArrayEquals(phoneKeys!!.second, clientKeys!!.second)
        // A second offer while the channel is open is refused by the extension itself.
        assertNull(p.aExt.offer(p.aSession()))
        p.bExt.stop(); p.aExt.stop()
    }

    @Test fun offerDeclineReachesTheClient() {
        val p = Linked()
        var declined: Pair<String, String?>? = null
        p.bExt.onOffer = { session, offer, message -> p.bExt.decline(session, offer, message, "unavailable") }
        p.aExt.onDeclined = { _, c, why -> declined = c to why }
        val offered = p.aExt.offer(p.aSession())
        assertNotNull(offered)
        assertEquals(offered to "unavailable", declined)
        // The decline cleared the client's state: it may offer again.
        p.bExt.onOffer = { _, _, _ -> }
        assertNotNull(p.aExt.offer(p.aSession()))
        p.bExt.stop(); p.aExt.stop()
    }

    @Test fun offerWithoutKeyMaterialIsNotSent() {
        val reg = ExtensionRegistry()
        val ext = WifiAudioExtension(keyMaterial = { null })
        val link = PipeLink("Phone", "phone")
        reg.register(ext); reg.start(); reg.linkUp(link)
        reg.peerCaps(link, JSONArray().put("x.tether.wifiaudio/1"))
        assertNull(ext.offer(reg.sessions(ext).single()))
        ext.stop()
    }

    @Test fun connectedChannelCarriesFramesAndDownClearsIt() {
        val p = Linked()
        val aFrames = Collections.synchronizedList(ArrayList<ByteArray>())
        val bFrames = Collections.synchronizedList(ArrayList<ByteArray>())
        var downWhy: String? = null
        val downLatch = CountDownLatch(1)
        p.aExt.onFrame = { _, payload -> aFrames.add(payload) }
        p.bExt.onFrame = { _, payload -> bFrames.add(payload) }
        p.bExt.onOffer = { session, offer, message ->
            p.bExt.accept(session, offer, message, "127.0.0.1", InetAddress.getByName("127.0.0.1"))
        }
        p.aExt.onAccepted = { session, accept, keys -> p.aExt.connect(session, accept, keys) }
        p.aExt.onDown = { _, _, why -> downWhy = why; downLatch.countDown() }
        assertNotNull(p.aExt.offer(p.aSession()))
        await("channels up") { p.aExt.channelUp(p.aLink) && p.bExt.channelUp(p.bLink) }
        assertEquals("wifi", p.bExt.status()?.getString("path"))
        val up = byteArrayOf(5, 6, 7)
        val down = byteArrayOf(8, 9)
        assertTrue(p.aExt.sendFrame(p.aLink, up))
        assertTrue(p.bExt.sendFrame(p.bLink, down))
        await("frames both ways") { bFrames.size == 1 && aFrames.size == 1 }
        assertArrayEquals(up, bFrames[0])
        assertArrayEquals(down, aFrames[0])
        p.bExt.sendDown(p.bSession(), "ended")
        assertTrue(downLatch.await(5, TimeUnit.SECONDS))
        assertEquals("ended", downWhy)
        await("channels down") { !p.aExt.channelUp(p.aLink) && !p.bExt.channelUp(p.bLink) }
        assertNull(p.bExt.status())
        p.bExt.stop(); p.aExt.stop()
    }
}
