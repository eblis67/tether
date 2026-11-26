package app.tether.client.ext

import app.tether.client.ClientApp
import app.tether.core.Message
import app.tether.core.ext.ExtensionSession
import app.tether.core.ext.FilesCodec
import app.tether.core.ext.FilesExtension
import app.tether.core.ext.FilesStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * File transfer (`x.tether.files`, protocol §7.10) as this client's user sees it: the state behind the Files
 * card on the Link tab and the offer notification. The [FilesExtension] does the protocol and the byte moving;
 * this class owns the host choices: which store a transfer lands in, which local address the Android phone
 * dials, when the user is asked, and what the UI shows. Handlers run on the main thread (the client's registry
 * dispatch), as do the extension's io-thread callbacks, which are posted here before touching state.
 */
class FilesFeature(private val app: ClientApp, val ext: FilesExtension) {
    /** An offer from the Android phone, waiting for the user to answer it here (never auto-accepted). */
    class Pending(val session: ExtensionSession, val offer: FilesCodec.Offer, val message: Message) {
        val peer: String get() = session.peerName
        val entries: List<FilesCodec.Entry> get() = offer.entries
        val total: Long get() = offer.entries.sumOf { it.size }
    }

    /** A transfer on the wire, in either direction. [done] counts bytes in place (resume offsets included). */
    class Active(val tid: String, val session: ExtensionSession, val sending: Boolean,
                 val names: List<String>, val total: Long, val store: FilesStore?) {
        @Volatile var done: Long = 0
    }

    /** The last finished transfer's outcome, for one line in the UI. */
    enum class Note { SENT, RECEIVED, DECLINED, CANCELLED, ERROR, NO_NETWORK }

    @Volatile var pending: Pending? = null; private set
    @Volatile var active: Active? = null; private set
    @Volatile var note: Note? = null; private set
    @Volatile var noteDetail: String? = null; private set

    private val positions = ConcurrentHashMap<String, Long>()
    private var ticking = false

    init {
        ext.lister = { FolderFilesStore(receivedDir()).entries() }
        ext.onOffer = { session, offer, message ->
            pending = Pending(session, offer, message)
            app.notifier.showFilesOffer(session.peerName, offer.entries.size)
            app.changed()
        }
        ext.onDone = { _, tid -> finish(tid, Note.SENT) }
        ext.onSent = { _, tid, outcome -> app.main.post { if (outcome.error != null) finish(tid, Note.ERROR, outcome.error) } }
        ext.onReceived = { _, tid, outcome ->
            app.main.post {
                when {
                    outcome.error != null -> finish(tid, Note.ERROR, outcome.error)
                    outcome.allOk -> finish(tid, Note.RECEIVED)
                    else -> finish(tid, Note.ERROR, "checksum")
                }
            }
        }
        ext.onDeclined = { _, tid, _ -> finish(tid, Note.DECLINED) }
        ext.onCancelled = { _, tid -> finish(tid, Note.CANCELLED) }
        ext.onError = { _, tid, code -> if (tid != null) finish(tid, Note.ERROR, code) }
    }

    /** The folder received files land in (inside the app's own external files, so no permission is needed). */
    fun receivedDir(): File = File(app.getExternalFilesDir(null), "received")

    fun receivedFiles(): List<File> = FolderFilesStore(receivedDir()).files()

    /** The link the feature would use now (the phone agreed `x.tether.files/1`), or null. */
    fun session(): ExtensionSession? = app.extensions.sessions(ext).firstOrNull()

    /** Offers the picked files to the Android phone. False when there is no usable link or one is busy. */
    fun sendPicked(picked: List<PickedFile>): Boolean {
        val session = session() ?: return false
        positions.clear()
        val source = UriFilesSource(app.contentResolver, picked) { name, pos ->
            positions[name] = pos
            active?.let { if (it.sending) it.done = positions.values.sum() }
        }
        val tid = ext.offer(session, source) ?: run { setNote(Note.ERROR, "busy"); return false }
        note = null; noteDetail = null
        active = Active(tid, session, sending = true, names = picked.map { it.name },
            total = picked.sumOf { it.size }, store = null)
        startTicking()
        app.changed()
        return true
    }

    /** Accepts the pending offer: listens on this device's Wi-Fi / hotspot address and tells the phone where. */
    fun acceptPending() {
        val p = pending ?: return
        val address = LocalAddress.current()
        if (address == null) { setNote(Note.NO_NETWORK); app.changed(); return }   // the offer stays open
        val store = FolderFilesStore(receivedDir())
        if (ext.accept(p.session, p.offer, p.message, address.hostAddress!!, store, address)) {
            pending = null
            app.notifier.cancelFilesOffer()
            note = null; noteDetail = null
            active = Active(p.offer.tid, p.session, sending = false, names = p.entries.map { it.name },
                total = p.total, store = store)
            startTicking()
        } else setNote(Note.ERROR, "io")
        app.changed()
    }

    fun declinePending() {
        val p = pending ?: return
        pending = null
        app.notifier.cancelFilesOffer()
        ext.decline(p.session, p.offer, p.message)
        app.changed()
    }

    fun cancelActive() {
        val a = active ?: return
        active = null
        ext.cancel(a.session, a.tid)
        setNote(Note.CANCELLED)
        app.changed()
    }

    fun clearNote() { note = null; noteDetail = null }

    private fun finish(tid: String, outcome: Note, detail: String? = null) {
        if (active?.tid == tid) active = null
        if (pending?.offer?.tid == tid) { pending = null; app.notifier.cancelFilesOffer() }
        setNote(outcome, detail)
        app.changed()
    }

    private fun setNote(outcome: Note, detail: String? = null) { note = outcome; noteDetail = detail }

    /** While a transfer runs: follow the receiving store's growth, and drop state whose link is gone. */
    private fun startTicking() {
        if (ticking) return
        ticking = true
        app.main.postDelayed(::tick, 600)
    }

    private fun tick() {
        val a = active
        if (a == null && pending == null) { ticking = false; return }
        if (session() == null) {
            if (a != null) { active = null; setNote(Note.ERROR, "link") }
            if (pending != null) { pending = null; app.notifier.cancelFilesOffer() }
            ticking = false
            app.changed()
            return
        }
        if (a != null) {
            if (!a.sending) a.store?.let { store -> a.done = a.names.sumOf { store.length(it).coerceAtLeast(0) } }
            app.changed()
        }
        app.main.postDelayed(::tick, 600)
    }
}
