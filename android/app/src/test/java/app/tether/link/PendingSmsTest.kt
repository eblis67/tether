package app.tether.link

import app.tether.core.Sms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Store-and-forward for texts that arrive while no client is connected (see PendingSms). */
class PendingSmsTest {
    private fun sms(id: Long) = Sms(id = id, thread = 7, addr = "+15550100", body = "hi $id",
        date = 1_700_000_000_000 + id, box = 1, read = false, sub = null)

    @Test fun heldUntilDrained() {
        val p = PendingSms()
        p.add(sms(1)); p.add(sms(2))
        assertEquals(2, p.size())
        assertEquals(listOf(1L, 2L), p.drain().map { it.id })
        assertEquals(0, p.size())
        assertTrue(p.drain().isEmpty())
    }

    @Test fun sameTextHeldOnce() {
        val p = PendingSms()
        p.add(sms(5)); p.add(sms(5))
        assertEquals(1, p.size())
        assertEquals(listOf(5L), p.drain().map { it.id })
    }

    @Test fun capDropsTheOldest() {
        val p = PendingSms(cap = 3)
        for (id in 1L..5L) p.add(sms(id))
        assertEquals(3, p.size())
        assertEquals(listOf(3L, 4L, 5L), p.drain().map { it.id })
    }
}
