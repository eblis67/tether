package app.tether.link

/**
 * Where a call's audio goes (protocol §5.1), as pure rules the hub applies.
 *
 * A call's audio goes to a client only when that client answered it, dialed it or moved it to itself (`call.take`)
 * while the current calls were going on. A call answered or dialed on the Android phone itself (its screen, a
 * headset or a car connected to it) stays on the Android phone: nothing is bridged and nothing on the phone is
 * muted. Before 1.2.2 such a call went to the client used last, else the iPhone, and the phone's own earpiece and
 * microphone were silenced: with the iPhone linked, a call taken on the phone had no audio there (issue #13).
 *
 * A phone whose software gives Tether no call audio (the downlink capture reads only zeros in a real call) is
 * remembered by its build fingerprint: its calls are not bridged until the firmware changes or a self-test finds
 * call audio again. Clients are told `helper.audio` "blocked".
 */
object AudioOwner {
    /** The owner when nobody holds the audio now: the live client whose key claimed the call. */
    fun <C> pick(live: List<C>, key: (C) -> String, claimedKey: String?): C? =
        claimedKey?.let { k -> live.firstOrNull { key(it) == k } }

    /**
     * Whether [client] sees a call in [state] as being elsewhere: answered, dialing or held, with its audio on another
     * client, on the Android phone itself (no owner), or kept on the phone because it gives no call audio.
     */
    fun <C> elsewhere(state: String, owner: C?, client: C, blocked: Boolean): Boolean =
        state != "ringing" && state != "ended" && (blocked || owner == null || owner !== client)

    /** The name a client shows for "on …": the owning client's, else the Android phone's own. */
    fun where(ownerName: String?, phoneName: String): String = ownerName ?: phoneName

    /** Whether the remembered "no call audio" still holds for this firmware ([remembered] is the fingerprint saved). */
    fun blocked(remembered: String?, fingerprint: String): Boolean = remembered != null && remembered == fingerprint
}

/**
 * Which client claimed which call (protocol §5.1: a call's owner is the client that answered, dialed or took
 * THAT call — a claim for one call says nothing about another). Claims are by client key, so a client that
 * reconnects keeps its calls. A dial has no call id yet: its claim waits here and binds to the outgoing call
 * that shows up next, briefly. Claims for calls that are gone are pruned by the hub.
 *
 * Before, one claim covered every call: with a call held on the iPhone and a second call answered on the
 * Android phone itself, the phone still reported the second call as the iPhone's (not `elsewhere`), so the
 * iPhone performed an answer for a call nobody had given it and the call's audio left the phone.
 */
class CallClaims(private val now: () -> Long) {
    private val byCall = HashMap<String, String>()
    private var dialKey: String? = null
    private var dialAt = 0L

    /** [cid] null: a dial in flight — the claim binds when its outgoing call appears. */
    @Synchronized fun claim(key: String, cid: String?) {
        if (cid != null) byCall[cid] = key
        else { dialKey = key; dialAt = now() }
    }

    /** The key that claimed [cid]; an outgoing call with no claim of its own takes a fresh dial claim (bound to it). */
    @Synchronized fun keyFor(cid: String, outgoing: Boolean): String? {
        byCall[cid]?.let { return it }
        if (!outgoing) return null
        val k = dialKey ?: return null
        if (now() - dialAt > DIAL_CLAIM_MS) { dialKey = null; return null }
        byCall[cid] = k
        dialKey = null
        return k
    }

    /** Forgets the claims of calls that are gone, and a dial claim nothing came of. */
    @Synchronized fun prune(liveCids: Set<String>) {
        byCall.keys.retainAll(liveCids)
        if (dialKey != null && now() - dialAt > DIAL_CLAIM_MS) dialKey = null
    }

    companion object { const val DIAL_CLAIM_MS = 30_000L }
}
