package app.tether.core.ext

import app.tether.core.Bytes
import app.tether.core.Ext
import app.tether.core.Message
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** SHA-256 of [b], base64url — the digest form §7.10 offers carry. */
private fun sha256Of(b: ByteArray) = Bytes.b64url(MessageDigest.getInstance("SHA-256").digest(b))

/** `x.tether.files` (protocol §7.10): codecs, key derivation, and the whole negotiation over two linked registries. */
class FilesExtensionTest {
    private val th = ByteArray(32) { (it * 7 + 1).toByte() }

    // ------------------------------------------------------------------ fixtures of our own

    class MemoryStore : FilesStore {
        val data = HashMap<String, ByteArray>()
        override fun length(name: String) = data[name]?.size?.toLong() ?: 0L
        override fun read(name: String, offset: Long, buf: ByteArray, len: Int): Int {
            val d = data[name] ?: return -1
            if (offset >= d.size) return -1
            val n = minOf(len, d.size - offset.toInt())
            System.arraycopy(d, offset.toInt(), buf, 0, n)
            return n
        }
        override fun write(name: String, offset: Long, bytes: ByteArray, off: Int, len: Int) {
            val cur = data[name] ?: ByteArray(0)
            val need = offset.toInt() + len
            val grown = if (cur.size >= need) cur else cur.copyOf(need)
            System.arraycopy(bytes, off, grown, offset.toInt(), len)
            data[name] = grown
        }
    }

    class TestSource(val files: Map<String, ByteArray>, private val digests: Map<String, String> = emptyMap()) : FilesSource {
        val openedAt = HashMap<String, Long>()
        override fun files() = files.map { (name, bytes) ->
            FilesCodec.Entry(name, bytes.size.toLong(), digests[name] ?: sha256Of(bytes))
        }
        override fun open(name: String, offset: Long): InputStream {
            openedAt[name] = offset
            val bytes = files.getValue(name)
            return ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
        }
    }

    class PipeLink(override val peerName: String, override val peerKind: String) : ExtensionLink {
        override val transport = "memory"
        lateinit var deliver: (Message) -> Unit
        override fun send(m: Message): Boolean { deliver(m); return true }
    }

    /** Two registries wired to each other, both speaking only `x.tether.files`. */
    private class Pair(th: ByteArray) {
        val aExt = FilesExtension(keyMaterial = { th })
        val bExt = FilesExtension(keyMaterial = { th })
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

    private fun content(size: Int, seed: Int = 31) = ByteArray(size) { (it * seed % 251).toByte() }
    private fun sha256(b: ByteArray) = sha256Of(b)

    // ------------------------------------------------------------------ codecs

    @Test fun codecRoundTrips() {
        val page = FilesCodec.OfferPage("AAECAwQFBgcICQoLDA0ODw", 0, 2, "MTExMTExMTExMTExMTExMQ",
            listOf(FilesCodec.Entry("IMG_1.jpg", 123456, sha256(content(10)))))
        assertEquals(page, FilesCodec.parseOfferPage(FilesCodec.offerPage(page)))

        val req = FilesCodec.ListRequest("Photos", 3)
        assertEquals(req, FilesCodec.parseListRequest(FilesCodec.listRequest(req)))
        assertEquals(FilesCodec.ListRequest(null, 0), FilesCodec.parseListRequest(FilesCodec.listRequest(FilesCodec.ListRequest(null, 0))))

        val lp = FilesCodec.ListPage(1, 4, listOf(FilesCodec.DirEntry("a.jpg", 5, "file"), FilesCodec.DirEntry("sub", 0, "dir")))
        assertEquals(lp, FilesCodec.parseListPage(FilesCodec.listPage(lp)))

        val acc = FilesCodec.Accept("AAECAwQFBgcICQoLDA0ODw", "192.168.0.102", 41001, "MjIyMjIyMjIyMjIyMjIyMg",
            mapOf("IMG_1.jpg" to 999L))
        assertEquals(acc, FilesCodec.parseAccept(FilesCodec.accept(acc)))

        assertEquals("tid1" to "no space", FilesCodec.parseTid(FilesCodec.decline("tid1", "no space")))
        assertEquals("tid1" to null, FilesCodec.parseTid(FilesCodec.cancel("tid1", null)))
        assertEquals("tid1" to "checksum", FilesCodec.parseError(FilesCodec.error("tid1", "checksum")))
        val done = FilesCodec.done("tid1", 2, 3000)
        assertEquals(2, done.getInt("files")); assertEquals(3000L, done.getLong("bytes"))
    }

    @Test fun entryValidationRejectsPathsAndDuplicates() {
        val good = listOf(FilesCodec.Entry("a.jpg", 1, sha256(content(3))))
        FilesCodec.checkEntries(good)
        for (bad in listOf("../evil", "a/b.jpg", "a\\b.jpg", ".", "..", "")) {
            try { FilesCodec.checkEntries(listOf(FilesCodec.Entry(bad, 1, sha256(content(3))))); fail("accepted '$bad'") }
            catch (_: IllegalArgumentException) {}
        }
        try { FilesCodec.checkEntries(good + good); fail("accepted duplicates") } catch (_: IllegalArgumentException) {}
        try { FilesCodec.checkEntries(listOf(FilesCodec.Entry("a.jpg", 1, "not-a-digest"))); fail("accepted bad sha") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun keyDerivationIsBoundToTheSessionAndTheTransfer() {
        val tid = Bytes.b64url(ByteArray(16) { it.toByte() })
        val k1 = ByteArray(16) { 1 }; val k2 = ByteArray(16) { 2 }
        val a = FilesKeys.derive(th, tid, k1, k2)
        assertArrayEquals(a.first, FilesKeys.derive(th, tid, k1, k2).first)
        assertFalse("the two directions never share a key", a.first.contentEquals(a.second))
        val otherTid = Bytes.b64url(ByteArray(16) { (it + 9).toByte() })
        assertNotEquals(Bytes.hex(a.first), Bytes.hex(FilesKeys.derive(th, otherTid, k1, k2).first))
        val otherTh = ByteArray(32) { (it * 3).toByte() }
        assertNotEquals(Bytes.hex(a.first), Bytes.hex(FilesKeys.derive(otherTh, tid, k1, k2).first))
    }

    // ------------------------------------------------------------------ over two registries

    @Test fun registeredAndAnnounced() {
        val p = Pair(th)
        assertEquals(listOf("x.tether.files/1"), p.aReg.caps())
        assertEquals(Ext.FILES, p.aExt.id)
        assertEquals(setOf(Ext.FILES, FilesExtension.LIST, FilesExtension.ACCEPT, FilesExtension.DECLINE,
            FilesExtension.DONE, FilesExtension.CANCEL, FilesExtension.ERROR), p.aExt.types)
    }

    @Test fun offerAcceptTransferAndDoneOverLoopback() {
        val p = Pair(th)
        val store = MemoryStore()
        val files = mapOf("IMG_1.jpg" to content(70_000), "notes.txt" to content(123, 17))
        val source = TestSource(files)
        val received = CountDownLatch(1); val done = CountDownLatch(1); val sent = CountDownLatch(1)
        var receiveOutcome: FilesReceiveOutcome? = null
        p.bExt.onOffer = { session, offer, message ->
            assertTrue(p.bExt.accept(session, offer, message, "127.0.0.1", store))
        }
        p.bExt.onReceived = { _, _, outcome -> receiveOutcome = outcome; received.countDown() }
        p.aExt.onSent = { _, _, _ -> sent.countDown() }
        p.aExt.onDone = { _, _ -> done.countDown() }

        val tid = p.aExt.offer(p.aSession(), source)
        assertTrue("offer sent", tid != null)
        assertTrue("sender finished", sent.await(15, TimeUnit.SECONDS))
        assertTrue("receiver finished", received.await(15, TimeUnit.SECONDS))
        assertTrue("done arrived", done.await(15, TimeUnit.SECONDS))
        assertTrue(receiveOutcome!!.allOk)
        for ((name, bytes) in files) assertArrayEquals("$name arrived whole", bytes, store.data[name])
    }

    @Test fun resumeContinuesFromTheStoredOffset() {
        val p = Pair(th)
        val store = MemoryStore()
        val whole = content(50_000)
        store.data["IMG_1.jpg"] = whole.copyOf(1000)   // a first try died after 1 000 bytes
        val source = TestSource(mapOf("IMG_1.jpg" to whole))
        val done = CountDownLatch(1)
        p.bExt.onOffer = { session, offer, message -> p.bExt.accept(session, offer, message, "127.0.0.1", store) }
        p.aExt.onDone = { _, _ -> done.countDown() }
        assertTrue(p.aExt.offer(p.aSession(), source) != null)
        assertTrue(done.await(15, TimeUnit.SECONDS))
        assertEquals("the sender resumed at the stored offset", 1000L, source.openedAt["IMG_1.jpg"])
        assertArrayEquals(whole, store.data["IMG_1.jpg"])
    }

    @Test fun checksumMismatchIsRejectedAndReported() {
        val p = Pair(th)
        val store = MemoryStore()
        val bytes = content(40_000)
        // The offer lies about the digest: what arrives can never match it.
        val source = TestSource(mapOf("IMG_1.jpg" to bytes), digests = mapOf("IMG_1.jpg" to sha256(content(9))))
        val error = CountDownLatch(1); val received = CountDownLatch(1)
        var code: String? = null; var outcome: FilesReceiveOutcome? = null; var doneFired = false
        p.bExt.onOffer = { session, offer, message -> p.bExt.accept(session, offer, message, "127.0.0.1", store) }
        p.bExt.onReceived = { _, _, o -> outcome = o; received.countDown() }
        p.aExt.onError = { _, _, c -> code = c; error.countDown() }
        p.aExt.onDone = { _, _ -> doneFired = true }
        assertTrue(p.aExt.offer(p.aSession(), source) != null)
        assertTrue("receiver finished", received.await(15, TimeUnit.SECONDS))
        assertTrue("the sender was told", error.await(15, TimeUnit.SECONDS))
        assertEquals("checksum", code)
        assertFalse("no file is complete", outcome!!.files.single().ok)
        assertFalse("done never fires for a bad file", doneFired)
    }

    @Test fun listingIsAnsweredInPages() {
        val p = Pair(th)
        p.bExt.lister = { path ->
            assertEquals("Photos", path)
            listOf(FilesCodec.DirEntry("IMG_1.jpg", 123, "file"), FilesCodec.DirEntry("2026-11", 0, "dir"))
        }
        val got = CountDownLatch(1); var page: FilesCodec.ListPage? = null
        p.aExt.onListResult = { _, pg -> page = pg; got.countDown() }
        assertTrue(p.aExt.requestList(p.aSession(), "Photos", 0) != null)
        assertTrue(got.await(15, TimeUnit.SECONDS))
        assertEquals(0, page!!.page); assertEquals(1, page!!.pages)
        assertEquals(listOf("IMG_1.jpg", "2026-11"), page!!.entries.map { it.name })
        assertEquals("dir", page!!.entries[1].kind)
    }

    @Test fun declineEndsTheOffer() {
        val p = Pair(th)
        val declined = CountDownLatch(1); var why: String? = null
        p.bExt.onOffer = { session, offer, message -> p.bExt.decline(session, offer, message, "no space") }
        p.aExt.onDeclined = { _, _, w -> why = w; declined.countDown() }
        assertTrue(p.aExt.offer(p.aSession(), TestSource(mapOf("a.jpg" to content(10)))) != null)
        assertTrue(declined.await(15, TimeUnit.SECONDS))
        assertEquals("no space", why)
    }
}
