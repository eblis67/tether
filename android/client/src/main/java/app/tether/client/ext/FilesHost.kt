package app.tether.client.ext

import android.content.ContentResolver
import android.net.Uri
import app.tether.core.Bytes
import app.tether.core.ext.FilesCodec
import app.tether.core.ext.FilesSource
import app.tether.core.ext.FilesStore
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest

/**
 * The host side of `x.tether.files` (protocol §7.10) for this app: where received files live, what picked files
 * are read from, and which local address a transfer runs on. The core library knows none of this; it is handed
 * a [FilesStore] to receive into and a [FilesSource] to send from (docs/FILES-TRANSFER.md, stage B).
 */

/** Received files in one folder, addressed by the offer's basenames; offsets make resume a store question. */
class FolderFilesStore(private val dir: File) : FilesStore {
    private fun file(name: String): File {
        require(FilesCodec.validName(name)) { "bad file name" }
        return File(dir, name)
    }

    @Synchronized override fun length(name: String): Long = file(name).let { if (it.isFile) it.length() else 0L }

    @Synchronized override fun read(name: String, offset: Long, buf: ByteArray, len: Int): Int {
        val f = file(name)
        if (!f.isFile || offset >= f.length()) return -1
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(offset)
            return raf.read(buf, 0, len)
        }
    }

    @Synchronized override fun write(name: String, offset: Long, data: ByteArray, off: Int, len: Int) {
        dir.mkdirs()
        RandomAccessFile(file(name), "rw").use { raf ->
            raf.seek(offset)
            raf.write(data, off, len)
        }
    }

    /** The folder's current files (the listing the peer may browse, and the app's "received" surface). */
    fun entries(): List<FilesCodec.DirEntry> = dir.listFiles()?.filter { it.isFile }
        ?.sortedBy { it.name }?.map { FilesCodec.DirEntry(it.name, it.length(), "file") } ?: emptyList()

    fun files(): List<File> = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

/** In-memory store for `dry` (test) links: a test client must never make the app touch real files (rule 5). */
class MemoryFilesStore : FilesStore {
    private val data = HashMap<String, ByteArray>()
    @Synchronized override fun length(name: String) = data[name]?.size?.toLong() ?: 0L
    @Synchronized override fun read(name: String, offset: Long, buf: ByteArray, len: Int): Int {
        val d = data[name] ?: return -1
        if (offset >= d.size) return -1
        val n = minOf(len, d.size - offset.toInt())
        System.arraycopy(d, offset.toInt(), buf, 0, n)
        return n
    }
    @Synchronized override fun write(name: String, offset: Long, bytes: ByteArray, off: Int, len: Int) {
        val cur = data[name] ?: ByteArray(0)
        val need = offset.toInt() + len
        val grown = if (cur.size >= need) cur else cur.copyOf(need)
        System.arraycopy(bytes, off, grown, offset.toInt(), len)
        data[name] = grown
    }
}

/** One file the user picked to send: its content URI, a transferable name, its size and SHA-256. */
class PickedFile(val uri: Uri, val name: String, val size: Long, val sha256: String)

/**
 * Reads the picked files for one offer. Streams report their exact position (resume offset + bytes read), so
 * the host gets true per-file progress without the core engine knowing about UI.
 */
class UriFilesSource(
    private val resolver: ContentResolver,
    private val picked: List<PickedFile>,
    private val onProgress: (name: String, position: Long) -> Unit,
) : FilesSource {
    override fun files() = picked.map { FilesCodec.Entry(it.name, it.size, it.sha256) }

    override fun open(name: String, offset: Long): InputStream {
        val p = picked.first { it.name == name }
        val raw = resolver.openInputStream(p.uri) ?: throw java.io.IOException("cannot open $name")
        return CountingStream(raw, offset) { pos -> onProgress(name, pos) }
    }

    /** Skips to the resume offset, then counts every byte read from there. */
    private class CountingStream(private val inner: InputStream, start: Long, private val onProgress: (Long) -> Unit) : InputStream() {
        private var pos = start
        init {
            var left = start
            while (left > 0) {
                val skipped = inner.skip(left)
                if (skipped <= 0) {
                    if (inner.read() < 0) break
                    left--
                } else left -= skipped
            }
        }
        override fun read(): Int = inner.read().also { if (it >= 0) { pos++; onProgress(pos) } }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) { pos += it; onProgress(pos) } }
        override fun close() = inner.close()
    }
}

/** Picks the files to offer from content URIs: display name, size and SHA-256 (streamed once, here). */
fun digestPicked(resolver: ContentResolver, uris: List<Uri>): List<PickedFile> {
    val out = ArrayList<PickedFile>()
    val used = HashSet<String>()
    for (uri in uris) {
        var name = "file"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (ni >= 0) c.getString(ni)?.takeIf { it.isNotBlank() }?.let { name = it }
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        name = transferableName(name, used)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        resolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                total += n
            }
        } ?: continue
        out += PickedFile(uri, name, if (size >= 0) size else total, Bytes.b64url(digest.digest()))
    }
    return out
}

/** A name the protocol allows (§7.10: a basename), made unique within one offer. */
private fun transferableName(raw: String, used: MutableSet<String>): String {
    var name = raw.substringAfterLast('/').substringAfterLast('\\')
        .map { c -> if (c == '/' || c == '\\' || c.code < 0x20) '_' else c }.joinToString("").trim()
    if (name.isEmpty() || name == "." || name == "..") name = "file"
    if (name.toByteArray().size > FilesCodec.MAX_NAME_BYTES) name = name.take(60)
    var candidate = name
    var n = 2
    while (!used.add(candidate)) {
        val dot = name.lastIndexOf('.')
        candidate = if (dot > 0) "${name.substring(0, dot)} (${n++})${name.substring(dot)}" else "$name (${n++})"
    }
    return candidate
}

/**
 * This device's address on the local network the peer can dial: the Wi-Fi (client) or hotspot interface's IPv4.
 * File transfer needs both devices on the same network (or one on the other's hotspot); null when there is no
 * such address, which the UI turns into that hint instead of a failed transfer.
 */
object LocalAddress {
    fun current(): InetAddress? {
        val ifaces = try { NetworkInterface.getNetworkInterfaces().toList() } catch (t: Throwable) { return null }
        var fallback: InetAddress? = null
        for (ni in ifaces) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses.toList()) {
                if (a !is Inet4Address || a.isLoopbackAddress || !a.isSiteLocalAddress) continue
                if (ni.name.startsWith("wlan") || ni.name.startsWith("ap") || ni.name.startsWith("swlan")) return a
                if (fallback == null) fallback = a
            }
        }
        return fallback
    }
}
