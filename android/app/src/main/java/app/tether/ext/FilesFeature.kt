package app.tether.ext

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import app.tether.R
import app.tether.TetherApp
import app.tether.core.Message
import app.tether.core.ext.ExtensionSession
import app.tether.core.ext.FilesCodec
import app.tether.core.ext.FilesExtension
import app.tether.core.ext.FilesStore
import app.tether.link.Hub
import app.tether.ui.MainActivity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * File transfer (`x.tether.files`, protocol §7.10) as this app's user sees it: the state behind the Files card
 * in MainActivity and the offer notification. The [FilesExtension] does the protocol and the byte moving; this
 * class owns the choices the core library must not make: which store a transfer lands in (a test link gets
 * memory, never real files — docs/EXTENDING.md rule 5), which local address the peer dials, when the user is
 * asked, and what the UI shows. Callbacks arrive on the extension thread and the extension's io threads; the UI
 * reads the volatile state and re-renders through [Hub.refresh].
 */
class FilesFeature(private val app: TetherApp, private val hub: Hub, val ext: FilesExtension) {
    /** An offer from the peer, waiting for the user to answer it here (never auto-accepted). */
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
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "files-ui").apply { isDaemon = true } }

    init {
        ext.lister = { FolderFilesStore(receivedDir()).entries() }
        ext.onOffer = { session, offer, message ->
            pending = Pending(session, offer, message)
            if (!session.dry) notifyOffer(session.peerName, offer.entries.size)
            hub.refresh()
        }
        ext.onDone = { _, tid -> finish(tid, Note.SENT) }
        ext.onSent = { _, tid, outcome -> if (outcome.error != null) finish(tid, Note.ERROR, outcome.error) }
        ext.onReceived = { _, tid, outcome ->
            when {
                outcome.error != null -> finish(tid, Note.ERROR, outcome.error)
                outcome.allOk -> finish(tid, Note.RECEIVED)
                else -> finish(tid, Note.ERROR, "checksum")
            }
        }
        ext.onDeclined = { _, tid, _ -> finish(tid, Note.DECLINED) }
        ext.onCancelled = { _, tid -> finish(tid, Note.CANCELLED) }
        ext.onError = { _, tid, code -> if (tid != null) finish(tid, Note.ERROR, code) else Unit }
        // Progress for the UI: poll the receiving store, and drop state whose link is gone.
        ticker.scheduleWithFixedDelay({ tick() }, 600, 600, TimeUnit.MILLISECONDS)
    }

    /** The folder received files land in (inside the app's own external files, so no permission is needed). */
    fun receivedDir(): File = File(app.getExternalFilesDir(null), "received")

    fun receivedFiles(): List<File> = FolderFilesStore(receivedDir()).files()

    /** The link the feature would use now (a peer that agreed `x.tether.files/1`), or null. */
    fun session(): ExtensionSession? = hub.extensions.sessions(ext).firstOrNull()

    /** Offers the picked files to the connected peer. False when there is no usable link or one is busy. */
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
        hub.refresh()
        return true
    }

    /** Accepts the pending offer: listens on this device's Wi-Fi / hotspot address and tells the peer where. */
    fun acceptPending() {
        val p = pending ?: return
        val address = LocalAddress.current()
        if (address == null) { setNote(Note.NO_NETWORK); return }   // the offer stays open; the user can retry
        val store: FilesStore = if (p.session.dry) MemoryFilesStore() else FolderFilesStore(receivedDir())
        if (ext.accept(p.session, p.offer, p.message, address.hostAddress!!, store, address)) {
            pending = null
            cancelOfferNotification()
            note = null; noteDetail = null
            active = Active(p.offer.tid, p.session, sending = false, names = p.entries.map { it.name },
                total = p.total, store = store)
        } else setNote(Note.ERROR, "io")
        hub.refresh()
    }

    fun declinePending() {
        val p = pending ?: return
        pending = null
        cancelOfferNotification()
        ext.decline(p.session, p.offer, p.message)
        hub.refresh()
    }

    fun cancelActive() {
        val a = active ?: return
        active = null
        ext.cancel(a.session, a.tid)
        setNote(Note.CANCELLED)
        hub.refresh()
    }

    fun clearNote() { note = null; noteDetail = null }

    private fun finish(tid: String, outcome: Note, detail: String? = null) {
        if (active?.tid == tid) active = null
        if (pending?.offer?.tid == tid) { pending = null; cancelOfferNotification() }
        setNote(outcome, detail)
        hub.refresh()
    }

    private fun setNote(outcome: Note, detail: String? = null) { note = outcome; noteDetail = detail }

    private fun tick() {
        val a = active
        if (a == null && pending == null) return
        if (session() == null) {   // the link went away mid-transfer: the extension has already dropped its state
            if (a != null) { active = null; setNote(Note.ERROR, "link") }
            if (pending != null) { pending = null; cancelOfferNotification() }
            hub.refresh()
            return
        }
        if (a != null) {
            if (!a.sending) a.store?.let { a.done = currentReceived(a, it) }
            hub.refresh()
        }
    }

    private fun currentReceived(a: Active, store: FilesStore): Long {
        // Names come from the active transfer itself; lengths are the store's current truth (resume included).
        var sum = 0L
        for (name in a.names) sum += store.length(name).coerceAtLeast(0)
        return sum
    }

    private fun notifyOffer(peer: String, count: Int) {
        val intent = PendingIntent.getActivity(app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(app, TetherApp.CH_FILES).setSmallIcon(R.drawable.ic_tether)
            .setContentTitle(app.getString(R.string.files_offer_title))
            .setContentText(app.getString(R.string.files_offer_text, peer, count))
            .setContentIntent(intent).setAutoCancel(true).build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIF_FILES, n)
    }

    private fun cancelOfferNotification() =
        app.getSystemService(NotificationManager::class.java).cancel(NOTIF_FILES)

    companion object { const val NOTIF_FILES = 41_007 }
}
