package app.tether.link

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Applies client read marks and retries a refused provider write promptly, then periodically. */
internal class SmsReadSync(
    private val unreadCount: (Long, Long) -> Int,
    private val markRead: (Long, Long) -> Int,
    private val grantWrite: () -> String,
    private val schedule: (Long, () -> Unit) -> Unit,
) {
    data class RetryResult(val landed: Int, val pending: Int, val grant: String?)

    private val pending = ConcurrentHashMap<Long, Long>()
    private val retryScheduled = AtomicBoolean(false)

    fun mark(thread: Long, upTo: Long): Int {
        pending.merge(thread, upTo) { a, b -> maxOf(a, b) }
        val unread = unreadCount(thread, upTo)
        val marked = if (unread == 0) 0 else markRead(thread, upTo)
        val left = unreadCount(thread, upTo)
        if (left == 0) pending.remove(thread, upTo) else scheduleRetry()
        return marked
    }

    /** One app-op grant per pass; marks stay queued until the provider confirms they landed. */
    fun retry(): RetryResult {
        if (pending.isEmpty()) return RetryResult(0, 0, null)
        val grant = runCatching { grantWrite().trim() }.getOrElse { "appops: ${it.message}" }
        var landed = 0
        for ((thread, upTo) in pending) {
            runCatching { markRead(thread, upTo) }
            if (runCatching { unreadCount(thread, upTo) }.getOrDefault(-1) == 0) {
                pending.remove(thread, upTo)
                landed++
            }
        }
        return RetryResult(landed, pending.size, grant)
    }

    private fun scheduleRetry() {
        if (!retryScheduled.compareAndSet(false, true)) return
        try {
            schedule(250) {
                retryScheduled.set(false)
                retry()
            }
        } catch (t: Throwable) {
            retryScheduled.set(false)
        }
    }
}
