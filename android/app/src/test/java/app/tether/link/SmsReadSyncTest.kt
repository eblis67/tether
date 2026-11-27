package app.tether.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsReadSyncTest {
    @Test fun refusedReadMarkGetsPromptlyRetriedAfterOneWriteGrant() {
        var unread = 1
        var grants = 0
        var writes = 0
        val scheduled = mutableListOf<() -> Unit>()
        val sync = SmsReadSync(
            unreadCount = { _, _ -> unread },
            markRead = { _, _ ->
                writes++
                if (grants == 0) -1 else { unread = 0; 1 }
            },
            grantWrite = { grants++; "ok" },
            schedule = { _, retry -> scheduled += retry },
        )

        assertEquals(-1, sync.mark(thread = 7, upTo = 42))
        assertEquals(1, scheduled.size)
        assertEquals(1, unread)

        scheduled.single().invoke()

        assertEquals(1, grants)
        assertEquals(2, writes)
        assertEquals(0, unread)
        assertEquals(SmsReadSync.RetryResult(0, 0, null), sync.retry())
    }

    @Test fun aBurstOfReadMarksSharesOnePromptRetry() {
        val scheduled = mutableListOf<() -> Unit>()
        val sync = SmsReadSync(
            unreadCount = { _, _ -> 1 },
            markRead = { _, _ -> -1 },
            grantWrite = { "" },
            schedule = { _, retry -> scheduled += retry },
        )

        sync.mark(thread = 1, upTo = 10)
        sync.mark(thread = 2, upTo = 20)

        assertEquals(1, scheduled.size)
        assertTrue(sync.retry().pending > 0)
    }
}
