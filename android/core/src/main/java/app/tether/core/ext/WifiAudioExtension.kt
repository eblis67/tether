package app.tether.core.ext

import app.tether.core.Bytes
import app.tether.core.Ext
import app.tether.core.Message
import app.tether.core.P256
import app.tether.core.Proto
import app.tether.core.RecordReader
import app.tether.core.SecureSession
import app.tether.core.T
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * When the Wi-Fi call path (§7.11) is offered, used and given up. Every constant and decision lives here, pure,
 * so the two platforms cannot drift and the matrix is unit-testable without a socket or a call; the Swift
 * `WifiAudioPolicy` is the same table. Times are wall-clock milliseconds from the caller's clock.
 */
object WifiAudioPolicy {
    /** How often the client pings the channel, keeping it warm and proving it alive. */
    const val PING_INTERVAL_MS = 2_000L
    /** No frame heard for this long: stop *sending* on Wi-Fi (the per-frame decision falls back to Bluetooth). */
    const val HEARD_TIMEOUT_MS = 6_000L
    /** No frame heard for this long: the channel is dead — close it and forget it. */
    const val DEAD_TIMEOUT_MS = 15_000L
    /** How long after a failed or declined offer the client waits before offering again. */
    const val RETRY_DELAY_MS = 15_000L
    /** Offers per call, however they end; a call that keeps failing stays on Bluetooth. */
    const val MAX_ATTEMPTS_PER_CALL = 4
    /** How long the phone listens for the client's connection after accepting. */
    const val ACCEPT_WINDOW_MS = 15_000L

    /** Which path one audio frame should take. */
    enum class Path { BLUETOOTH, WIFI }

    /** Whether the client should offer a channel now (all inputs are the feature's own bookkeeping). */
    fun shouldOffer(enabled: Boolean, inCall: Boolean, hasNetwork: Boolean, channelBusy: Boolean,
                    attemptsThisCall: Int, lastAttemptMs: Long?, nowMs: Long): Boolean {
        if (!enabled || !inCall || !hasNetwork || channelBusy) return false
        if (attemptsThisCall >= MAX_ATTEMPTS_PER_CALL) return false
        if (lastAttemptMs != null && nowMs - lastAttemptMs < RETRY_DELAY_MS) return false
        return true
    }

    /** The per-frame send decision: Wi-Fi only while the channel is up and has been heard from recently. */
    fun sendPath(channelUp: Boolean, lastHeardMs: Long?, nowMs: Long): Path =
        if (channelUp && lastHeardMs != null && nowMs - lastHeardMs <= HEARD_TIMEOUT_MS) Path.WIFI else Path.BLUETOOTH

    /** Whether a silent channel should be closed and forgotten. */
    fun channelDead(lastHeardMs: Long?, nowMs: Long): Boolean =
        lastHeardMs == null || nowMs - lastHeardMs > DEAD_TIMEOUT_MS

    /** Whether the next keep-alive ping is due. */
    fun pingDue(lastPingMs: Long?, nowMs: Long): Boolean =
        lastPingMs == null || nowMs - lastPingMs >= PING_INTERVAL_MS

    /**
     * Why the phone must decline an offer, or null to accept. A test (`dry`) link never gets a channel (§7.5);
     * one channel per link; and the phone accepts only while it is bridging audio to this very client and has a
     * local address to listen on.
     */
    fun declineReason(dry: Boolean, channelOpen: Boolean, bridging: Boolean, hasAddress: Boolean): String? = when {
        dry -> "dry"
        channelOpen -> "busy"
        !bridging || !hasAddress -> "unavailable"
        else -> null
    }
}

/**
 * The one-time channel keys (§7.11): HKDF-SHA256 with the session's transcript hash as salt over both sides'
 * fresh nonces, with the channel id in the info — the §7.10 derivation with this extension's own info string,
 * so the two extensions' keys never coincide and the link's traffic keys are never inputs.
 */
object WifiAudioKeys {
    private val INFO = "tether/1 x.tether.wifiaudio".toByteArray()

    /** Returns (client → phone, phone → client) AES-256 keys. */
    fun derive(th: ByteArray, cid: String, key1: ByteArray, key2: ByteArray): Pair<ByteArray, ByteArray> {
        require(th.size == 32 && key1.size == 16 && key2.size == 16) { "bad key material" }
        val info = INFO + byteArrayOf(0) + Bytes.unb64url(cid)
        val okm = P256.hkdf(th, key1 + key2, info, 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }
}

/** The §7.11 message bodies, encoded and parsed in one place (the counterpart of the Swift bodies). */
object WifiAudioCodec {
    class Offer(val cid: String, val key1: ByteArray)
    class Accept(val cid: String, val host: String, val port: Int, val key2: ByteArray)

    fun offer(o: Offer): JSONObject = JSONObject().put("cid", o.cid).put("key1", Bytes.b64url(o.key1))
    fun parseOffer(b: JSONObject): Offer {
        val key1 = Bytes.unb64url(b.getString("key1"))
        require(key1.size == 16) { "key1" }
        return Offer(b.getString("cid"), key1)
    }

    fun accept(a: Accept): JSONObject = JSONObject().put("cid", a.cid).put("host", a.host)
        .put("port", a.port).put("key2", Bytes.b64url(a.key2))
    fun parseAccept(b: JSONObject): Accept {
        val key2 = Bytes.unb64url(b.getString("key2"))
        require(key2.size == 16) { "key2" }
        return Accept(b.getString("cid"), b.getString("host"), b.getInt("port"), key2)
    }

    /** `decline` / `down` / `error` all carry the channel id and an optional reason or code. */
    fun note(cid: String, field: String, value: String?): JSONObject =
        JSONObject().put("cid", cid).apply { value?.let { put(field, it) } }
    fun parseNote(b: JSONObject, field: String): Pair<String, String?> =
        b.getString("cid") to b.optString(field).ifEmpty { null }
}

/** The sealed frame stream of §7.11 over one TCP connection: §2 records, §4 sealing, a frame byte per plaintext. */
internal class WifiAudioWire(sendKey: ByteArray, recvKey: ByteArray, private val input: InputStream, private val output: OutputStream) {
    private val session = SecureSession(sendKey, recvKey)
    private val reader = RecordReader()
    private val buf = ByteArray(16 * 1024)

    fun write(type: Int, payload: ByteArray) {
        output.write(session.seal(Proto.CH_CONTROL, byteArrayOf(type.toByte()) + payload).encoded())
        output.flush()
    }

    fun writeJson(type: Int, body: JSONObject) = write(type, body.toString().toByteArray(Charsets.UTF_8))

    /** The next frame, or null when the peer closed. Throws on anything that does not open (§7.11: `protocol`). */
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
        const val AUDIO = 0x02
        const val PING = 0x03
        const val PONG = 0x04
        const val BYE = 0x05
        const val ERR = 0x06
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}

/** Why a channel ended: the peer said BYE, the stream closed, or it failed with a §7.11 code. */
sealed class WifiAudioEnd {
    object Bye : WifiAudioEnd()
    object Closed : WifiAudioEnd()
    class Failed(val code: String) : WifiAudioEnd()
}

/**
 * One §7.11 channel over an accepted or dialled socket: the initiator's first frame is its HELLO, audio frames
 * pass through untouched (they are §4 audio-channel payloads), pings are answered and pongs measured, and every
 * frame heard is timestamped for the policy's freshness decisions. [run] blocks; run it on a worker thread.
 * Writes are serialised (a seal is stateful), so audio and pings may be sent from any thread.
 */
class WifiAudioChannel(
    private val keys: Pair<ByteArray, ByteArray>,
    private val cid: String,
    private val initiator: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var wire: WifiAudioWire? = null
    @Volatile private var ended = false
    @Volatile private var lastHeard: Long = -1
    @Volatile private var lastPong: Long = -1
    private val writeLock = Any()

    val isUp: Boolean get() = !ended
    fun lastHeardMs(): Long? = lastHeard.takeIf { it >= 0 }
    fun lastPongRttMs(): Long? = lastPong.takeIf { it >= 0 }

    /** Sends one §4 audio payload on the channel; false when the channel is not (or no longer) writable. */
    fun sendAudio(payload: ByteArray): Boolean = write(WifiAudioWire.AUDIO, payload)

    /** Sends a keep-alive ping; the peer's PONG updates [lastPongRttMs]. */
    fun sendPing(): Boolean = writeJson(WifiAudioWire.PING, JSONObject().put("ts", now()))

    private fun write(type: Int, payload: ByteArray): Boolean {
        val w = wire ?: return false
        if (ended) return false
        return try {
            synchronized(writeLock) { w.write(type, payload) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun writeJson(type: Int, body: JSONObject): Boolean {
        val w = wire ?: return false
        if (ended) return false
        return try {
            synchronized(writeLock) { w.writeJson(type, body) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Closes the channel, telling the peer first when [notify]. Safe to call more than once. */
    fun close(notify: Boolean = true) {
        if (ended) return
        if (notify) write(WifiAudioWire.BYE, ByteArray(0))   // before `ended`: write() refuses afterwards
        ended = true
        try { socket?.close() } catch (e: Exception) { /* already gone */ }
        socket = null
        wire = null
    }

    /**
     * Runs the channel over [s] until it ends; [onFrame] receives audio payloads verbatim, [onEnd] fires exactly
     * once. The socket gets a short read timeout so a peer that goes silent without closing is noticed (the
     * policy's dead test) instead of blocking the pump forever.
     */
    fun run(s: Socket, onFrame: (ByteArray) -> Unit, onEnd: (WifiAudioEnd) -> Unit) {
        socket = s
        lastHeard = now()   // liveness is measured from the channel's birth, not from "never heard"
        var end: WifiAudioEnd = WifiAudioEnd.Closed
        try {
            s.soTimeout = 1_000
            val w = WifiAudioWire(
                if (initiator) keys.first else keys.second,
                if (initiator) keys.second else keys.first,
                s.getInputStream(), s.getOutputStream(),
            )
            // HELLO goes on the wire before the channel becomes sendable: sends gate on `wire`, which is
            // only stored after this write — no frame can ever precede the HELLO.
            if (initiator) w.writeJson(WifiAudioWire.HELLO, JSONObject().put("cid", cid))
            wire = w
            var greeted = initiator
            while (true) {
                val frame = try {
                    w.read()
                } catch (e: java.net.SocketTimeoutException) {
                    if (WifiAudioPolicy.channelDead(lastHeardMs(), now())) {
                        end = WifiAudioEnd.Failed("timeout")
                        break
                    }
                    continue
                } ?: break
                heard()
                val (type, payload) = frame
                if (!greeted) {
                    if (type != WifiAudioWire.HELLO ||
                        JSONObject(String(payload, Charsets.UTF_8)).optString("cid") != cid
                    ) {
                        end = WifiAudioEnd.Failed("protocol")
                        break
                    }
                    greeted = true
                    continue
                }
                when (type) {
                    WifiAudioWire.AUDIO -> onFrame(payload)
                    WifiAudioWire.PING -> write(WifiAudioWire.PONG, payload)
                    WifiAudioWire.PONG -> {
                        val ts = JSONObject(String(payload, Charsets.UTF_8)).optLong("ts", -1)
                        val rtt = now() - ts
                        if (ts >= 0 && rtt >= 0 && rtt < 60_000) lastPong = rtt
                    }
                    WifiAudioWire.BYE -> {
                        end = WifiAudioEnd.Bye
                        break
                    }
                    WifiAudioWire.ERR -> {
                        end = WifiAudioEnd.Failed(JSONObject(String(payload, Charsets.UTF_8)).optString("code", "protocol"))
                        break
                    }
                    else -> {
                        end = WifiAudioEnd.Failed("protocol")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            end = WifiAudioEnd.Failed(code(e))
        }
        val wasEnded = ended
        ended = true
        try { socket?.close() } catch (e: Exception) { /* already gone */ }
        socket = null
        wire = null
        if (!wasEnded) onEnd(end)
    }

    private fun heard() {
        lastHeard = now()
    }

    companion object {
        /** The §7.11 failure code for a channel exception (mirrors the §7.10 mapping). */
        fun code(e: Exception): String = when {
            e is java.net.SocketTimeoutException -> "timeout"
            e.message?.contains("auth failed") == true || e.message?.contains("counter mismatch") == true -> "protocol"
            else -> "io"
        }
    }
}

/**
 * `x.tether.wifiaudio` (protocol §7.11): a per-call TCP channel on the local network that carries a bridged
 * call's audio frames when both devices are on it, so a call at home does not pay Bluetooth's latency. Only
 * audio moves; signalling, pairing and the wake stay in the sealed session, and the negotiation rides it — the
 * channel's keys are derived from the session's transcript hash and fresh nonces from both sides, so the
 * listener the phone opens is useless to anyone but the paired peer. Receivers are path-agnostic (frames carry
 * their sequence number), so which path a frame takes is a per-frame decision and fallback is free.
 *
 * Either side may host this extension; the client offers, the phone accepts and listens. The extension owns
 * the channel's lifecycle; the host feeds it frames ([sendFrame]) and receives the peer's ([onFrame]).
 */
class WifiAudioExtension(
    private val keyMaterial: (link: ExtensionLink) -> ByteArray?,
    private val io: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "tether-wifiaudio").apply { isDaemon = true } },
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
) : TetherExtension {
    override val id = Ext.WIFI_AUDIO
    override val types = setOf(id, ACCEPT, DECLINE, DOWN, ERROR)

    /** An offer arrived; answer it with [accept] or [decline] (the host applies [WifiAudioPolicy.declineReason]). */
    var onOffer: ((session: ExtensionSession, offer: WifiAudioCodec.Offer, message: Message) -> Unit)? = null
    /** The phone accepted our offer; [connect] with the returned keys, or let the host drive the channel. */
    var onAccepted: ((session: ExtensionSession, accept: WifiAudioCodec.Accept, keys: Pair<ByteArray, ByteArray>) -> Unit)? = null
    /** The phone declined our offer (or refused it with an `error`, §7.4); [why] is the decline's reason or the error's code. */
    var onDeclined: ((session: ExtensionSession, cid: String, why: String?) -> Unit)? = null
    /** The phone reports the channel down (or took ours down): both sides forget it. */
    var onDown: ((session: ExtensionSession, cid: String, why: String?) -> Unit)? = null
    /** The negotiation or a channel failed outside the offer/answer flow. */
    var onError: ((session: ExtensionSession, cid: String?, code: String) -> Unit)? = null
    /** An audio frame arrived on the channel: a §4 audio-channel payload, verbatim. */
    var onFrame: ((link: ExtensionLink, payload: ByteArray) -> Unit)? = null
    /** A channel came up (true) or went down (false) on a link; the host re-reads [status] and its send path. */
    var onChannel: ((link: ExtensionLink, up: Boolean) -> Unit)? = null

    private class Outgoing(val cid: String, val key1: ByteArray) {
        var requestId: Int? = null
    }

    /** One link's channel state: the negotiated id, the listening socket while awaiting the peer, the channel. */
    private class Chan(val cid: String) {
        var server: ServerSocket? = null
        var channel: WifiAudioChannel? = null
    }

    private val lock = Any()
    private val outgoing = ConcurrentHashMap<ExtensionLink, Outgoing>()
    private val chans = ConcurrentHashMap<ExtensionLink, Chan>()
    /** The accepted channel's id on the client side, between the accept arriving and [connect] (or a `down`). */
    private val channelCids = ConcurrentHashMap<ExtensionLink, String>()

    // ------------------------------------------------------------------ negotiation

    /**
     * Offers a channel on this link; returns the channel id, or null when it could not be sent (no key material,
     * or a channel is already open or on offer). One channel per link (§7.11).
     */
    fun offer(session: ExtensionSession): String? {
        if (keyMaterial(session.link) == null) return null
        val link = session.link
        val cid = Bytes.b64url(ByteArray(16).also { random.nextBytes(it) })
        val key1 = ByteArray(16).also { random.nextBytes(it) }
        val out = Outgoing(cid, key1)
        synchronized(lock) {
            if (outgoing.containsKey(link) || chans.containsKey(link) || channelCids.containsKey(link)) return null
            outgoing[link] = out   // before sending: a fast peer's answer can arrive inside `request`
        }
        val reqId = session.request(id, WifiAudioCodec.offer(WifiAudioCodec.Offer(cid, key1)))
        if (reqId == null) {
            synchronized(lock) { if (outgoing[link] === out) outgoing.remove(link) }
            return null
        }
        out.requestId = reqId
        return cid
    }

    /**
     * Accepts [offer]: listens on [bind] (an ephemeral port; every interface when null — the host passes the
     * Wi-Fi interface's address) and answers with where. Returns the derived keys, or null when the session's
     * key material is missing (the peer is told `key`) or a channel is already open on the link. The channel
     * itself is served by the extension; [onChannel] reports it.
     */
    fun accept(session: ExtensionSession, offer: WifiAudioCodec.Offer, replyTo: Message, host: String,
               bind: InetAddress? = null): Pair<ByteArray, ByteArray>? {
        val link = session.link
        val th = keyMaterial(link)
        if (th == null) {
            session.send(ERROR, WifiAudioCodec.note(offer.cid, "code", "key"))
            return null
        }
        val chan = Chan(offer.cid)
        synchronized(lock) {
            if (chans.containsKey(link)) return null
            chans[link] = chan   // before replying, as in `offer`
        }
        val key2 = ByteArray(16).also { random.nextBytes(it) }
        val keys = WifiAudioKeys.derive(th, offer.cid, offer.key1, key2)
        val server = try {
            if (bind == null) ServerSocket(0, 1) else ServerSocket(0, 1, bind)
        } catch (e: Exception) {
            synchronized(lock) { if (chans[link] === chan) chans.remove(link) }
            session.send(ERROR, WifiAudioCodec.note(offer.cid, "code", "io"))
            return null
        }
        server.soTimeout = WifiAudioPolicy.ACCEPT_WINDOW_MS.toInt()
        chan.server = server
        val body = WifiAudioCodec.accept(WifiAudioCodec.Accept(offer.cid, host, server.localPort, key2))
        if (!session.reply(replyTo, body, ACCEPT)) {
            closeChannel(link)
            return null
        }
        io.execute {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                synchronized(lock) { if (chans[link] === chan) chans.remove(link) }
                try { server.close() } catch (ignored: Exception) { /* already gone */ }
                null
            }
            if (socket != null) {
                // One connection per channel: the listener's work is done either way.
                try { server.close() } catch (ignored: Exception) { /* already gone */ }
                serve(link, chan, WifiAudioChannel(keys, offer.cid, initiator = false, now), socket)
            }
        }
        return keys
    }

    /** Declines [offer] (the peer's [onDeclined] fires). */
    fun decline(session: ExtensionSession, offer: WifiAudioCodec.Offer, replyTo: Message, why: String? = null) {
        session.reply(replyTo, WifiAudioCodec.note(offer.cid, "why", why), DECLINE)
    }

    /**
     * Dials the accepted endpoint and runs the channel (the client side of [accept]); [onChannel] reports it.
     * False when a channel is already open on the link or the connection could not be started.
     */
    fun connect(session: ExtensionSession, accept: WifiAudioCodec.Accept, keys: Pair<ByteArray, ByteArray>): Boolean {
        val link = session.link
        val chan = Chan(accept.cid)
        synchronized(lock) {
            if (chans.containsKey(link)) return false
            channelCids.remove(link)
            chans[link] = chan
        }
        io.execute {
            val socket = try {
                Socket().apply { connect(InetSocketAddress(accept.host, accept.port), WifiAudioWire.CONNECT_TIMEOUT_MS) }
            } catch (e: Exception) {
                synchronized(lock) { if (chans[link] === chan) chans.remove(link) }
                onError?.invoke(session, accept.cid, WifiAudioChannel.code(e))
                null
            }
            if (socket != null) serve(link, chan, WifiAudioChannel(keys, accept.cid, initiator = true, now), socket)
        }
        return true
    }

    /** Runs [channel] over [socket] for [link], wiring its frames and its end into this extension's state. */
    private fun serve(link: ExtensionLink, chan: Chan, channel: WifiAudioChannel, socket: Socket) {
        synchronized(lock) {
            if (chans[link] !== chan) {
                try { socket.close() } catch (ignored: Exception) { /* already gone */ }
                return
            }
            chan.channel = channel
            chan.server = null
        }
        onChannel?.invoke(link, true)
        channel.run(socket, onFrame = { payload -> onFrame?.invoke(link, payload) }, onEnd = {
            val wasRegistered = synchronized(lock) {
                if (chans[link] === chan) {
                    chans.remove(link)
                    true
                } else {
                    false
                }
            }
            if (wasRegistered) onChannel?.invoke(link, false)
        })
    }

    /** Tells the peer the channel is down and forgets it on this side too. */
    fun sendDown(session: ExtensionSession, why: String? = null) {
        val chan = synchronized(lock) {
            channelCids.remove(session.link)
            chans.remove(session.link)
        }
        chan?.channel?.close()
        try { chan?.server?.close() } catch (ignored: Exception) { /* already gone */ }
        if (chan != null) session.send(DOWN, WifiAudioCodec.note(chan.cid, "why", why))
    }

    /** Reports a §7.11 failure to the peer outside the offer/answer flow. */
    fun sendError(session: ExtensionSession, cid: String, code: String) {
        session.send(ERROR, WifiAudioCodec.note(cid, "code", code))
    }

    // ------------------------------------------------------------------ the channel, for the host

    /** Sends one §4 audio payload on [link]'s channel; false when there is none or it would not take it. */
    fun sendFrame(link: ExtensionLink, payload: ByteArray): Boolean =
        chans[link]?.channel?.sendAudio(payload) == true

    /** Sends a keep-alive ping on [link]'s channel; false when there is none. */
    fun sendPing(link: ExtensionLink): Boolean =
        chans[link]?.channel?.sendPing() == true

    /** Whether a channel is established on [link]. */
    fun channelUp(link: ExtensionLink): Boolean = chans[link]?.channel?.isUp == true

    /** When [link]'s channel last heard any frame from the peer, or null. */
    fun lastHeardMs(link: ExtensionLink): Long? = chans[link]?.channel?.lastHeardMs()

    /** The channel id open or on offer on [link], or null. */
    fun channelId(link: ExtensionLink): String? = chans[link]?.cid ?: channelCids[link] ?: outgoing[link]?.cid

    /**
     * Closes [link]'s channel and listener locally, without telling the peer (the call's own teardown covers
     * that; [sendDown] is the variant that reports over the session). Safe in any state.
     */
    fun closeChannel(link: ExtensionLink) {
        val chan = synchronized(lock) { chans.remove(link) }
        chan?.channel?.close()
        try { chan?.server?.close() } catch (ignored: Exception) { /* already gone */ }
    }

    override fun status(): JSONObject? =
        if (chans.values.any { it.channel?.isUp == true }) JSONObject().put("path", "wifi") else null

    override fun onLink(session: ExtensionSession, up: Boolean) {
        if (up) return
        val link = session.link
        synchronized(lock) {
            outgoing.remove(link)
            channelCids.remove(link)
        }
        closeChannel(link)
    }

    override fun stop() {
        synchronized(lock) {
            outgoing.clear()
            channelCids.clear()
            chans.values.forEach {
                it.channel?.close(notify = false)
                try { it.server?.close() } catch (ignored: Exception) { /* already gone */ }
            }
            chans.clear()
        }
    }

    // ------------------------------------------------------------------ messages

    override fun onMessage(session: ExtensionSession, message: Message) {
        val link = session.link
        if (message.t == T.ERROR) {   // §7.4: an answer to one of our requests
            val re = message.re ?: return
            val out = synchronized(lock) { outgoing[link]?.takeIf { it.requestId == null || it.requestId == re } } ?: return
            synchronized(lock) { if (outgoing[link] === out) outgoing.remove(link) }
            onDeclined?.invoke(session, out.cid, message.b.optString("code", Ext.ERR_FAILED))
            return
        }
        when (message.t) {
            id -> {
                if (message.re != null) return
                val offer = WifiAudioCodec.parseOffer(message.b)
                if (synchronized(lock) { chans.containsKey(link) }) {
                    decline(session, offer, message, "busy")
                    return
                }
                onOffer?.invoke(session, offer, message)
            }
            ACCEPT -> {
                val acc = WifiAudioCodec.parseAccept(message.b)
                val out = synchronized(lock) { outgoing[link]?.takeIf { it.cid == acc.cid } } ?: return
                val th = keyMaterial(link)
                if (th == null) {
                    synchronized(lock) { if (outgoing[link] === out) outgoing.remove(link) }
                    session.send(ERROR, WifiAudioCodec.note(acc.cid, "code", "key"))
                    onError?.invoke(session, acc.cid, "key")
                    return
                }
                val key2 = acc.key2
                val keys = WifiAudioKeys.derive(th, acc.cid, out.key1, key2)
                synchronized(lock) {
                    if (outgoing[link] === out) outgoing.remove(link)
                    channelCids[link] = acc.cid
                }
                onAccepted?.invoke(session, acc, keys)
            }
            DECLINE -> {
                val (cid, why) = WifiAudioCodec.parseNote(message.b, "why")
                synchronized(lock) { if (outgoing[link]?.cid == cid) outgoing.remove(link) }
                onDeclined?.invoke(session, cid, why)
            }
            DOWN -> {
                val (cid, why) = WifiAudioCodec.parseNote(message.b, "why")
                val chan = synchronized(lock) { chans[link]?.takeIf { it.cid == cid } }
                synchronized(lock) { if (channelCids[link] == cid) channelCids.remove(link) }
                if (chan != null) closeChannel(link)
                onDown?.invoke(session, cid, why)
            }
            ERROR -> {
                val (cid, code) = WifiAudioCodec.parseNote(message.b, "code")
                synchronized(lock) {
                    if (channelCids[link] == cid) channelCids.remove(link)
                    if (chans[link]?.cid == cid) chans.remove(link)?.let { chan ->
                        chan.channel?.close()
                        try { chan.server?.close() } catch (ignored: Exception) { /* already gone */ }
                    }
                    if (outgoing[link]?.cid == cid) outgoing.remove(link)
                }
                onError?.invoke(session, cid, code ?: Ext.ERR_FAILED)
            }
        }
    }

    companion object {
        const val ACCEPT = Ext.WIFI_AUDIO + ".accept"
        const val DECLINE = Ext.WIFI_AUDIO + ".decline"
        const val DOWN = Ext.WIFI_AUDIO + ".down"
        const val ERROR = Ext.WIFI_AUDIO + ".error"
    }
}
