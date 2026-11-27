package app.tether.link

import app.tether.core.Sms

/**
 * Texts that arrived while no client had a live session. The live `sms.new` send is one-shot: a client
 * that is not connected at that moment never sees it, and in the background the client's own sync may
 * not finish before iOS suspends it again — so the text effectively waited until the app was opened.
 * Held here instead and pushed the moment a client connects (like the call list in
 * [Hub.onEstablished]), with the id-based sync as the backstop for anything evicted.
 * Capped: a long absence must not replay a flood on reconnect.
 */
class PendingSms(private val cap: Int = 50) {
    private val queue = ArrayDeque<Sms>()

    @Synchronized fun add(s: Sms) {
        if (queue.any { it.id == s.id }) return
        queue.addLast(s)
        while (queue.size > cap) queue.removeFirst()
    }

    /** Everything held, oldest first; the queue is empty afterwards. */
    @Synchronized fun drain(): List<Sms> {
        val out = queue.toList()
        queue.clear()
        return out
    }

    @Synchronized fun size(): Int = queue.size
}
