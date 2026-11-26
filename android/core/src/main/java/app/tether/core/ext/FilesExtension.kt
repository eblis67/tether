package app.tether.core.ext

import app.tether.core.Bytes
import app.tether.core.Ext
import app.tether.core.Message
import app.tether.core.P256
import app.tether.core.Proto
import app.tether.core.RecordReader
import app.tether.core.SecureSession
import app.tether.core.T
import app.tether.core.paginate
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * `x.tether.files` (protocol §7.10): file and photo transfer between the two paired devices. The sealed session
 * carries only the negotiation (offer, listing, accept, done, cancel, error); the bytes move over a short-lived
 * TCP connection on the local network, sealed with a one-time key derived from the session's transcript hash and
 * two fresh salts, so the link keys are never reused and nothing leaves the two devices.
 *
 * The core library knows no files: the host hands it a [FilesSource] to send from and a [FilesStore] to receive
 * into, and each link's transcript hash through [keyMaterial] (the host owns the sessions; the core library
 * never sees their traffic keys). For a `dry` (test) link the host must pass in-memory stores, as for every
 * extension (docs/EXTENDING.md rule 5).
 */
class FilesExtension(
    private val keyMaterial: (link: ExtensionLink) -> ByteArray?,
    private val io: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "tether-files").apply { isDaemon = true } },
    private val random: SecureRandom = SecureRandom(),
) : TetherExtension {
    override val id = Ext.FILES
    override val types = setOf(id, LIST, ACCEPT, DECLINE, DONE, CANCEL, ERROR)

    /** Answers `x.tether.files.list` requests: the shared entries under [path] (null: the shared roots). */
    var lister: ((path: String?) -> List<FilesCodec.DirEntry>)? = null
    /** A complete offer arrived; answer it with [accept] or [decline] (the [Message] is the offer's last page). */
    var onOffer: ((session: ExtensionSession, offer: FilesCodec.Offer, message: Message) -> Unit)? = null
    /** The answer to one of our [requestList] calls. */
    var onListResult: ((session: ExtensionSession, page: FilesCodec.ListPage) -> Unit)? = null
    /** The receiver verified every file of a transfer we sent. */
    var onDone: ((session: ExtensionSession, tid: String) -> Unit)? = null
    /** The peer declined our offer (or refused it with an `error`, §7.4). */
    var onDeclined: ((session: ExtensionSession, tid: String, why: String?) -> Unit)? = null
    /** The peer cancelled a transfer, or we did and it was acknowledged by the state going away. */
    var onCancelled: ((session: ExtensionSession, tid: String) -> Unit)? = null
    /** A transfer failed: [code] is one of §7.10's (`busy`, `checksum`, `io`, `key`, `offset`, `protocol`, `timeout`). */
    var onError: ((session: ExtensionSession, tid: String?, code: String) -> Unit)? = null
    /** Our sending side finished (whatever the outcome); the receiver still sends `done` or `error` itself. */
    var onSent: ((session: ExtensionSession, tid: String, outcome: FilesSendOutcome) -> Unit)? = null
    /** Our receiving side finished; `done` (or a `checksum` error) has been sent to the peer. */
    var onReceived: ((session: ExtensionSession, tid: String, outcome: FilesReceiveOutcome) -> Unit)? = null

    private class Outgoing(val tid: String, val source: FilesSource, val key1: ByteArray) {
        var requestId: Int? = null
        var sender: FilesSender? = null
    }

    private class Incoming(val tid: String, val entries: List<FilesCodec.Entry>, val key1: ByteArray) {
        var receiver: FilesReceiver? = null
    }

    private class PendingOffer(val tid: String, val pages: Int) {
        val got = HashMap<Int, List<FilesCodec.Entry>>()
        var key1: ByteArray? = null
        var lastMessage: Message? = null
    }

    private val lock = Any()
    private val outgoing = ConcurrentHashMap<ExtensionLink, Outgoing>()
    private val incoming = ConcurrentHashMap<ExtensionLink, Incoming>()
    private val pending = ConcurrentHashMap<ExtensionLink, PendingOffer>()
    private val listRequests = ConcurrentHashMap<Int, ExtensionLink>()

    // ------------------------------------------------------------------ sending side

    /**
     * Offers [source]'s files to the peer; returns the transfer id, or null when it could not be sent (a transfer
     * is already open on this link, the session's key material is missing, or the registry refused a page).
     */
    fun offer(session: ExtensionSession, source: FilesSource): String? {
        val entries = source.files()
        FilesCodec.checkEntries(entries)
        if (keyMaterial(session.link) == null) return null
        val link = session.link
        synchronized(lock) {
            if (outgoing.containsKey(link) || incoming.containsKey(link)) return null
        }
        val tid = Bytes.b64url(ByteArray(16).also { random.nextBytes(it) })
        val key1 = ByteArray(16).also { random.nextBytes(it) }
        val pages = paginate(entries, PAGE_WEIGHT) { 96 + it.name.toByteArray().size * 2 + it.sha256.length }
        val out = Outgoing(tid, source, key1)
        synchronized(lock) { outgoing[link] = out }
        pages.forEachIndexed { i, page ->
            val body = FilesCodec.offerPage(FilesCodec.OfferPage(tid, i, pages.size, Bytes.b64url(key1), page))
            if (i == pages.lastIndex) {
                val reqId = session.request(id, body)
                if (reqId == null) {
                    synchronized(lock) { if (outgoing[link] === out) outgoing.remove(link) }
                    return null
                }
                out.requestId = reqId
            } else if (!session.send(id, body)) {
                synchronized(lock) { if (outgoing[link] === out) outgoing.remove(link) }
                return null
            }
        }
        return tid
    }

    /** Asks the peer for one page of a listing; the answer arrives in [onListResult]. Null when not sent. */
    fun requestList(session: ExtensionSession, path: String? = null, page: Int = 0): Int? {
        val reqId = session.request(LIST, FilesCodec.listRequest(FilesCodec.ListRequest(path, page))) ?: return null
        listRequests[reqId] = session.link
        return reqId
    }

    // ------------------------------------------------------------------ receiving side

    /**
     * Accepts [offer]: starts listening on the local network (an ephemeral port on [bind], or every interface when
     * null — the host passes the Wi-Fi interface's address) and answers with where. [host] is the address the peer
     * should dial, as written in the accept. False when the session's key material is missing (the peer is told
     * `key`) or the offer is no longer pending.
     */
    fun accept(session: ExtensionSession, offer: FilesCodec.Offer, replyTo: Message, host: String,
               store: FilesStore, bind: InetAddress? = null): Boolean {
        val link = session.link
        val th = keyMaterial(link)
        if (th == null) {
            session.send(ERROR, FilesCodec.error(offer.tid, "key"))
            return false
        }
        val inc = Incoming(offer.tid, offer.entries, offer.key1)
        synchronized(lock) {
            if (incoming.containsKey(link) || outgoing.containsKey(link)) return false
            if (pending[link]?.tid != offer.tid) return false
            pending.remove(link)
            incoming[link] = inc
        }
        val key2 = ByteArray(16).also { random.nextBytes(it) }
        val keys = FilesKeys.derive(th, offer.tid, offer.key1, key2)
        val receiver = FilesReceiver(keys, offer.tid, store, offer.entries)
        inc.receiver = receiver
        val port = try {
            receiver.bind(bind)
        } catch (e: Exception) {
            synchronized(lock) { if (incoming[link] === inc) incoming.remove(link) }
            session.send(ERROR, FilesCodec.error(offer.tid, "io"))
            return false
        }
        val have = offer.entries.associate { it.name to minOf(store.length(it.name), it.size) }
        if (!session.reply(replyTo, FilesCodec.accept(FilesCodec.Accept(offer.tid, host, port, Bytes.b64url(key2), have)), ACCEPT)) {
            receiver.close()
            synchronized(lock) { if (incoming[link] === inc) incoming.remove(link) }
            return false
        }
        io.execute {
            val outcome = receiver.serve()
            synchronized(lock) { if (incoming[link] === inc) incoming.remove(link) }
            when {
                outcome.error != null -> session.send(ERROR, FilesCodec.error(offer.tid, outcome.error))
                outcome.files.all { it.ok } -> session.send(DONE, FilesCodec.done(offer.tid, outcome.files.size, outcome.bytes))
                else -> session.send(ERROR, FilesCodec.error(offer.tid, "checksum"))
            }
            onReceived?.invoke(session, offer.tid, outcome)
        }
        return true
    }

    /** Declines [offer] (the peer's [onDeclined] fires). */
    fun decline(session: ExtensionSession, offer: FilesCodec.Offer, replyTo: Message, why: String? = null) {
        synchronized(lock) { if (pending[session.link]?.tid == offer.tid) pending.remove(session.link) }
        session.reply(replyTo, FilesCodec.decline(offer.tid, why), DECLINE)
    }

    /** Cancels the open transfer [tid] on this link, on whichever side of it we are, and tells the peer. */
    fun cancel(session: ExtensionSession, tid: String) {
        abort(session.link, tid)
        session.send(CANCEL, FilesCodec.cancel(tid, null))
    }

    private fun abort(link: ExtensionLink, tid: String) {
        synchronized(lock) {
            outgoing[link]?.takeIf { it.tid == tid }?.let { it.sender?.close(); outgoing.remove(link) }
            incoming[link]?.takeIf { it.tid == tid }?.let { it.receiver?.close(); incoming.remove(link) }
            if (pending[link]?.tid == tid) pending.remove(link)
        }
    }

    override fun onLink(session: ExtensionSession, up: Boolean) {
        if (up) return
        val link = session.link
        synchronized(lock) {
            outgoing.remove(link)?.sender?.close()
            incoming.remove(link)?.receiver?.close()
            pending.remove(link)
        }
    }

    override fun stop() {
        synchronized(lock) {
            outgoing.values.forEach { it.sender?.close() }
            incoming.values.forEach { it.receiver?.close() }
            outgoing.clear(); incoming.clear(); pending.clear()
        }
    }

    // ------------------------------------------------------------------ messages

    override fun onMessage(session: ExtensionSession, message: Message) {
        val link = session.link
        if (message.t == T.ERROR) {   // §7.4: an answer to one of our requests
            val re = message.re ?: return
            val code = message.b.optString("code", Ext.ERR_FAILED)
            synchronized(lock) {
                val out = outgoing[link]
                if (out != null && out.requestId == re) {
                    outgoing.remove(link)
                    onDeclined?.invoke(session, out.tid, code)
                    return
                }
                if (listRequests.remove(re) != null) onError?.invoke(session, null, code)
            }
            return
        }
        when (message.t) {
            id -> if (message.re == null) onOfferPage(session, message)
            LIST -> {
                if (message.re != null) {
                    listRequests.remove(message.re)
                    onListResult?.invoke(session, FilesCodec.parseListPage(message.b))
                } else if (message.id != null) {
                    val req = FilesCodec.parseListRequest(message.b)
                    val all = lister?.invoke(req.path) ?: emptyList()
                    val pages = paginate(all, PAGE_WEIGHT) { 96 + it.name.toByteArray().size * 2 }
                    val page = req.page.coerceIn(0, pages.lastIndex)
                    session.reply(message, FilesCodec.listPage(FilesCodec.ListPage(page, pages.size, pages[page])), LIST)
                }
            }
            ACCEPT -> {
                val acc = FilesCodec.parseAccept(message.b)
                val out = synchronized(lock) { outgoing[link]?.takeIf { it.tid == acc.tid } } ?: return
                val th = keyMaterial(link)
                if (th == null) {
                    synchronized(lock) { if (outgoing[link] === out) outgoing.remove(link) }
                    session.send(ERROR, FilesCodec.error(acc.tid, "key"))
                    onError?.invoke(session, acc.tid, "key")
                    return
                }
                val keys = FilesKeys.derive(th, acc.tid, out.key1, Bytes.unb64url(acc.key2))
                val sender = FilesSender(keys, acc.tid, out.source, acc.have)
                out.sender = sender
                io.execute {
                    val outcome = sender.send(acc.host, acc.port)
                    synchronized(lock) { if (outgoing[link] === out && outcome.error != null) outgoing.remove(link) }
                    if (outcome.error != null) session.send(ERROR, FilesCodec.error(acc.tid, outcome.error))
                    onSent?.invoke(session, acc.tid, outcome)
                }
            }
            DECLINE -> {
                val b = FilesCodec.parseTid(message.b)
                synchronized(lock) { if (outgoing[link]?.tid == b.first) outgoing.remove(link) }
                onDeclined?.invoke(session, b.first, b.second)
            }
            DONE -> {
                val tid = FilesCodec.parseTid(message.b).first
                synchronized(lock) { if (outgoing[link]?.tid == tid) outgoing.remove(link) }
                onDone?.invoke(session, tid)
            }
            CANCEL -> {
                val tid = FilesCodec.parseTid(message.b).first
                abort(link, tid)
                onCancelled?.invoke(session, tid)
            }
            ERROR -> {
                val (tid, code) = FilesCodec.parseError(message.b)
                if (tid != null) abort(link, tid)
                onError?.invoke(session, tid, code)
            }
        }
    }

    private fun onOfferPage(session: ExtensionSession, message: Message) {
        val page = FilesCodec.parseOfferPage(message.b)
        val link = session.link
        var complete: FilesCodec.Offer? = null
        var busy = false
        synchronized(lock) {
            val active = outgoing[link]?.tid ?: incoming[link]?.tid
            if (active != null && active != page.tid) {
                busy = page.page == page.pages - 1
            } else {
                val p = pending[link]?.takeIf { it.tid == page.tid }
                    ?: PendingOffer(page.tid, page.pages).also { pending[link] = it }
                if (p.pages == page.pages) {
                    p.got[page.page] = page.files
                    if (page.key1.isNotEmpty()) p.key1 = Bytes.unb64url(page.key1)
                    if (page.page == page.pages - 1) p.lastMessage = message
                    if (p.got.size == p.pages && p.key1 != null && p.lastMessage != null) {
                        val entries = (0 until p.pages).flatMap { p.got.getValue(it) }
                        FilesCodec.checkEntries(entries)
                        complete = FilesCodec.Offer(page.tid, p.key1!!, entries)
                    }
                }
            }
        }
        if (busy) session.send(ERROR, FilesCodec.error(page.tid, "busy"))
        val offer = complete ?: return
        onOffer?.invoke(session, offer, synchronized(lock) { pending[link]?.lastMessage } ?: message)
    }

    companion object {
        const val LIST = Ext.FILES + ".list"
        const val ACCEPT = Ext.FILES + ".accept"
        const val DECLINE = Ext.FILES + ".decline"
        const val DONE = Ext.FILES + ".done"
        const val CANCEL = Ext.FILES + ".cancel"
        const val ERROR = Ext.FILES + ".error"
        /** Offer and listing pages aim at this encoded weight, under §7.3's 16 KiB (2 KiB in a call: hosts wait). */
        const val PAGE_WEIGHT = 11_000
    }
}

/** The JSON bodies of `x.tether.files` (§7.10): one builder and one parser per shape, shared by both roles. */
object FilesCodec {
    /** One offered file: a basename, its length and its SHA-256 (base64url). */
    data class Entry(val name: String, val size: Long, val sha256: String)
    /** One listing entry; [kind] is `"file"` or `"dir"`. */
    data class DirEntry(val name: String, val size: Long, val kind: String)
    data class OfferPage(val tid: String, val page: Int, val pages: Int, val key1: String, val files: List<Entry>)
    /** A complete offer, assembled from its pages. [key1] is the sender's raw key salt. */
    class Offer(val tid: String, val key1: ByteArray, val entries: List<Entry>)
    data class ListRequest(val path: String?, val page: Int)
    data class ListPage(val page: Int, val pages: Int, val entries: List<DirEntry>)
    data class Accept(val tid: String, val host: String, val port: Int, val key2: String, val have: Map<String, Long>)

    const val MAX_FILES = 1024
    const val MAX_FILE_SIZE = 1L shl 41
    const val MAX_NAME_BYTES = 255

    /** A transferable name: a basename only, so a receiver can store it without escaping its folder (§7.10). */
    fun validName(name: String): Boolean {
        val bytes = name.toByteArray().size
        return name.isNotEmpty() && bytes <= MAX_NAME_BYTES && name != "." && name != ".." &&
            '/' !in name && '\\' !in name
    }

    fun checkEntries(entries: List<Entry>) {
        require(entries.isNotEmpty() && entries.size <= MAX_FILES) { "a transfer holds 1..$MAX_FILES files" }
        require(entries.map { it.name }.distinct().size == entries.size) { "duplicate file name in one transfer" }
        for (e in entries) {
            require(validName(e.name)) { "bad file name '${e.name.take(40)}'" }
            require(e.size in 0..MAX_FILE_SIZE) { "bad size for '${e.name.take(40)}'" }
            require(runCatching { Bytes.unb64url(e.sha256) }.getOrNull()?.size == 32) { "bad sha256 for '${e.name.take(40)}'" }
        }
    }

    fun offerPage(p: OfferPage): JSONObject = JSONObject()
        .put("tid", p.tid).put("page", p.page).put("pages", p.pages).put("key1", p.key1)
        .put("files", JSONArray(p.files.map { entry(it) }))

    fun parseOfferPage(b: JSONObject): OfferPage {
        val files = b.getJSONArray("files")
        return OfferPage(
            b.getString("tid"), b.getInt("page"), b.getInt("pages"), b.optString("key1"),
            (0 until files.length()).map { parseEntry(files.getJSONObject(it)) },
        )
    }

    fun listRequest(r: ListRequest): JSONObject = JSONObject().put("page", r.page).apply { r.path?.let { put("path", it) } }

    fun parseListRequest(b: JSONObject) = ListRequest(if (b.has("path")) b.getString("path") else null, b.optInt("page", 0))

    fun listPage(p: ListPage): JSONObject = JSONObject()
        .put("page", p.page).put("pages", p.pages)
        .put("entries", JSONArray(p.entries.map {
            JSONObject().put("name", it.name).put("size", it.size).put("kind", it.kind)
        }))

    fun parseListPage(b: JSONObject): ListPage {
        val a = b.getJSONArray("entries")
        return ListPage(b.getInt("page"), b.getInt("pages"), (0 until a.length()).map {
            val o = a.getJSONObject(it)
            DirEntry(o.getString("name"), o.getLong("size"), o.getString("kind"))
        })
    }

    fun accept(a: Accept): JSONObject = JSONObject()
        .put("tid", a.tid).put("host", a.host).put("port", a.port).put("key2", a.key2)
        .put("have", JSONArray(a.have.map { (name, have) -> JSONObject().put("name", name).put("have", have) }))

    fun parseAccept(b: JSONObject): Accept {
        val a = b.optJSONArray("have") ?: JSONArray()
        val have = (0 until a.length()).associate {
            val o = a.getJSONObject(it)
            o.getString("name") to o.getLong("have")
        }
        return Accept(b.getString("tid"), b.getString("host"), b.getInt("port"), b.getString("key2"), have)
    }

    fun decline(tid: String, why: String?): JSONObject = JSONObject().put("tid", tid).apply { why?.let { put("why", it) } }
    fun done(tid: String, files: Int, bytes: Long): JSONObject = JSONObject().put("tid", tid).put("files", files).put("bytes", bytes)
    fun cancel(tid: String, why: String?): JSONObject = JSONObject().put("tid", tid).apply { why?.let { put("why", it) } }
    fun error(tid: String, code: String, msg: String? = null): JSONObject =
        JSONObject().put("tid", tid).put("code", code).apply { msg?.let { put("msg", it) } }

    /** `{tid, why?}` of a decline or a cancel. */
    fun parseTid(b: JSONObject): Pair<String, String?> = b.getString("tid") to if (b.has("why")) b.getString("why") else null

    /** `{tid, code}` of an `x.tether.files.error`. */
    fun parseError(b: JSONObject): Pair<String?, String> =
        (if (b.has("tid")) b.getString("tid") else null) to b.getString("code")

    private fun entry(e: Entry): JSONObject = JSONObject().put("name", e.name).put("size", e.size).put("sha256", e.sha256)
    private fun parseEntry(o: JSONObject) = Entry(o.getString("name"), o.getLong("size"), o.getString("sha256"))
}

/**
 * The one-time transfer keys (§7.10): HKDF-SHA256 with the session's transcript hash as salt — so the keys are
 * bound to this paired session — over both sides' fresh salts, with the transfer id in the info. The link's
 * traffic keys are inputs to nothing here and are never reused.
 */
object FilesKeys {
    private val INFO = "tether/1 x.tether.files".toByteArray()

    /** Returns (sender → receiver, receiver → sender) AES-256 keys. */
    fun derive(th: ByteArray, tid: String, key1: ByteArray, key2: ByteArray): Pair<ByteArray, ByteArray> {
        require(th.size == 32 && key1.size == 16 && key2.size == 16) { "bad key material" }
        val info = INFO + byteArrayOf(0) + Bytes.unb64url(tid)
        val okm = P256.hkdf(th, key1 + key2, info, 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }
}

/** Where offered files are read from (the host's media store, a folder, memory in tests). */
interface FilesSource {
    fun files(): List<FilesCodec.Entry>
    /** A stream of [name]'s bytes starting at [offset]. */
    fun open(name: String, offset: Long): InputStream
}

/** Where received files are kept, addressed by the offer's names; offsets make resume a store question. */
interface FilesStore {
    /** Bytes already held of [name] (0 when none). */
    fun length(name: String): Long
    /** Copies up to [len] bytes of [name] at [offset] into [buf]; -1 at the end. */
    fun read(name: String, offset: Long, buf: ByteArray, len: Int): Int
    /** Writes [len] bytes of [data] (from [off]) at [offset] of [name], growing it as needed. */
    fun write(name: String, offset: Long, data: ByteArray, off: Int, len: Int)
}

/** One file's result in a finished (or failed) transfer. */
data class FilesFileOutcome(val name: String, val ok: Boolean, val have: Long)
data class FilesSendOutcome(val files: List<FilesFileOutcome>, val error: String? = null) {
    val allOk get() = error == null && files.all { it.ok }
}
data class FilesReceiveOutcome(val files: List<FilesFileOutcome>, val error: String? = null) {
    val allOk get() = error == null && files.all { it.ok }
    val bytes get() = files.sumOf { it.have }
}

/** The sealed frame stream of §7.10 over one TCP connection: §2 records, §4 sealing, a frame byte per plaintext. */
internal class FilesWire(sendKey: ByteArray, recvKey: ByteArray, private val input: InputStream, private val output: OutputStream) {
    private val session = SecureSession(sendKey, recvKey)
    private val reader = RecordReader()
    private val buf = ByteArray(64 * 1024)

    fun write(type: Int, payload: ByteArray) {
        output.write(session.seal(Proto.CH_CONTROL, byteArrayOf(type.toByte()) + payload).encoded())
        output.flush()
    }

    fun writeJson(type: Int, body: JSONObject) = write(type, body.toString().toByteArray(Charsets.UTF_8))

    /** The next frame, or null when the peer closed. Throws on anything that does not open (§7.10: `protocol`). */
    fun read(): Pair<Int, ByteArray>? {
        while (true) {
            val rec = reader.next()
            if (rec != null) {
                val (_, plain) = session.open(rec)
                if (plain.isEmpty()) throw IllegalStateException("empty frame")
                return (plain[0].toInt() and 0xff) to plain.copyOfRange(1, plain.size)
            }
            val n = input.read(buf)
            if (n < 0) return null
            reader.append(buf, 0, n)
        }
    }

    companion object {
        const val HELLO = 0x01
        const val FILE = 0x02
        const val DATA = 0x03
        const val ACK = 0x04
        const val BYE = 0x05
        const val ERR = 0x06
        const val CHUNK = 16 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val IDLE_TIMEOUT_MS = 30_000
        const val ACCEPT_TIMEOUT_MS = 120_000
    }
}

/** The sending engine of §7.10: connects to the receiver, sends each file from its resume offset, waits per file. */
class FilesSender(
    private val keys: Pair<ByteArray, ByteArray>,
    private val tid: String,
    private val source: FilesSource,
    private val resume: Map<String, Long>,
) {
    @Volatile private var socket: Socket? = null

    fun send(host: String, port: Int): FilesSendOutcome {
        val outcomes = ArrayList<FilesFileOutcome>()
        return try {
            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(host, port), FilesWire.CONNECT_TIMEOUT_MS)
            s.soTimeout = FilesWire.IDLE_TIMEOUT_MS
            val wire = FilesWire(keys.first, keys.second, s.getInputStream(), s.getOutputStream())
            wire.writeJson(FilesWire.HELLO, JSONObject().put("tid", tid))
            val entries = source.files()
            for ((i, e) in entries.withIndex()) {
                val off = (resume[e.name] ?: 0L).coerceIn(0, e.size)
                wire.writeJson(FilesWire.FILE, JSONObject().put("i", i).put("name", e.name).put("size", e.size).put("off", off))
                var left = e.size - off
                source.open(e.name, off).use { stream ->
                    val chunk = ByteArray(FilesWire.CHUNK)
                    while (left > 0) {
                        val n = stream.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
                        if (n < 0) throw IllegalStateException("source ended early: ${e.name}")
                        wire.write(FilesWire.DATA, chunk.copyOf(n))
                        left -= n
                    }
                }
                val ack = awaitAck(wire, i) ?: return FilesSendOutcome(outcomes, "io")
                outcomes += FilesFileOutcome(e.name, ack.first, ack.second)
            }
            wire.write(FilesWire.BYE, ByteArray(0))
            s.close()
            FilesSendOutcome(outcomes)
        } catch (e: Exception) {
            FilesSendOutcome(outcomes, if (e is IllegalStateException && e.message?.startsWith("source") == true) "io" else errorCode(e))
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** Reads until file [i]'s ACK; returns (ok, have). Null when the connection died; throws on an ERR frame. */
    private fun awaitAck(wire: FilesWire, i: Int): Pair<Boolean, Long>? {
        while (true) {
            val (type, payload) = wire.read() ?: return null
            when (type) {
                FilesWire.ACK -> {
                    val b = JSONObject(String(payload, Charsets.UTF_8))
                    if (b.getInt("i") == i) return b.getBoolean("ok") to b.getLong("have")
                }
                FilesWire.ERR -> throw IllegalStateException("peer error: ${JSONObject(String(payload, Charsets.UTF_8)).optString("code")}")
                else -> throw IllegalStateException("unexpected frame $type")
            }
        }
    }

    fun close() {
        runCatching { socket?.close() }
    }

    private fun errorCode(e: Exception): String = when {
        e is java.net.SocketTimeoutException -> "timeout"
        e.message?.contains("auth failed") == true || e.message?.contains("counter mismatch") == true -> "protocol"
        else -> "io"
    }
}

/** The receiving engine of §7.10: one accepted connection, files written at their offsets, digests checked. */
class FilesReceiver(
    private val keys: Pair<ByteArray, ByteArray>,
    private val tid: String,
    private val store: FilesStore,
    private val expected: List<FilesCodec.Entry>,
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null

    /** Starts listening; returns the port to put in the accept. */
    fun bind(address: InetAddress? = null, port: Int = 0): Int {
        val s = if (address == null) ServerSocket(port) else ServerSocket(port, 1, address)
        s.soTimeout = FilesWire.ACCEPT_TIMEOUT_MS
        server = s
        return s.localPort
    }

    /** Accepts the one connection and runs the transfer to its end (blocking; call on a worker thread). */
    fun serve(): FilesReceiveOutcome {
        val srv = server ?: throw IllegalStateException("bind first")
        val outcomes = ArrayList<FilesFileOutcome>()
        return try {
            val s = srv.accept()
            socket = s
            s.soTimeout = FilesWire.IDLE_TIMEOUT_MS
            val wire = FilesWire(keys.second, keys.first, s.getInputStream(), s.getOutputStream())
            val hello = wire.read() ?: return FilesReceiveOutcome(outcomes, "io")
            if (hello.first != FilesWire.HELLO || JSONObject(String(hello.second, Charsets.UTF_8)).optString("tid") != tid)
                return FilesReceiveOutcome(outcomes, "protocol")
            var current: FilesCodec.Entry? = null
            var digest: MessageDigest? = null
            var pos = 0L
            var left = 0L
            while (true) {
                val (type, payload) = wire.read() ?: break
                when (type) {
                    FilesWire.FILE -> {
                        val b = JSONObject(String(payload, Charsets.UTF_8))
                        val e = expected.getOrNull(b.getInt("i")) ?: return FilesReceiveOutcome(outcomes, "protocol")
                        if (b.getString("name") != e.name || b.getLong("size") != e.size) return FilesReceiveOutcome(outcomes, "protocol")
                        val off = b.getLong("off")
                        if (off != store.length(e.name)) {
                            wire.writeJson(FilesWire.ERR, JSONObject().put("code", "offset"))
                            return FilesReceiveOutcome(outcomes, "offset")
                        }
                        current = e
                        digest = seedDigest(e.name, off)
                        pos = off
                        left = e.size - off
                        if (left == 0L) {
                            val ok = finishFile(wire, outcomes, e, digest!!, pos)
                            if (!ok) return FilesReceiveOutcome(outcomes)
                            current = null; digest = null
                        }
                    }
                    FilesWire.DATA -> {
                        val e = current ?: return FilesReceiveOutcome(outcomes, "protocol")
                        if (payload.size > left) return FilesReceiveOutcome(outcomes, "protocol")
                        store.write(e.name, pos, payload, 0, payload.size)
                        digest!!.update(payload)
                        pos += payload.size
                        left -= payload.size
                        if (left == 0L) {
                            val ok = finishFile(wire, outcomes, e, digest!!, pos)
                            if (!ok) return FilesReceiveOutcome(outcomes)
                            current = null; digest = null
                        }
                    }
                    FilesWire.BYE -> return FilesReceiveOutcome(outcomes)
                    FilesWire.ERR -> return FilesReceiveOutcome(outcomes, "io")
                    else -> return FilesReceiveOutcome(outcomes, "protocol")
                }
            }
            FilesReceiveOutcome(outcomes, if (outcomes.size == expected.size) null else "io")
        } catch (e: Exception) {
            FilesReceiveOutcome(outcomes, when {
                e is java.net.SocketTimeoutException -> "timeout"
                e.message?.contains("auth failed") == true || e.message?.contains("counter mismatch") == true -> "protocol"
                else -> "io"
            })
        } finally {
            close()
        }
    }

    /** The digest of what is already stored, so a resumed file is checked whole. */
    private fun seedDigest(name: String, upTo: Long): MessageDigest {
        val d = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(FilesWire.CHUNK)
        var at = 0L
        while (at < upTo) {
            val n = store.read(name, at, buf, minOf(buf.size.toLong(), upTo - at).toInt())
            if (n <= 0) break
            d.update(buf, 0, n)
            at += n
        }
        return d
    }

    /** Checks the finished file against the offer and ACKs it; false when the digest differed (the sender stops). */
    private fun finishFile(wire: FilesWire, outcomes: ArrayList<FilesFileOutcome>, e: FilesCodec.Entry,
                           digest: MessageDigest, have: Long): Boolean {
        val ok = Bytes.b64url(digest.digest()) == e.sha256
        outcomes += FilesFileOutcome(e.name, ok, have)
        val index = expected.indexOf(e)
        wire.writeJson(FilesWire.ACK, JSONObject().put("i", index).put("ok", ok).put("have", have))
        return ok
    }

    fun close() {
        runCatching { socket?.close() }
        runCatching { server?.close() }
    }
}
