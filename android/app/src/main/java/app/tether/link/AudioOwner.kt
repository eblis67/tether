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
 * remembered by its build fingerprint and Tether's version: its calls are not bridged until the firmware or Tether
 * changes, or a self-test finds call audio again. Clients are told `helper.audio` "blocked".
 */
object AudioOwner {
    /** The owner when nobody holds the audio now: the live client whose key claimed during the current calls. */
    fun <C> pick(live: List<C>, key: (C) -> String, claimedKey: String?): C? =
        claimedKey?.let { k -> live.firstOrNull { key(it) == k } }

    /** The claim is forgotten once no call is left (ended calls do not count). */
    fun keepClaim(callStates: List<String>): Boolean = callStates.any { it != "ended" }

    /**
     * Whether [client] sees a call in [state] as being elsewhere: answered, dialing or held, with its audio on another
     * client, on the Android phone itself (no owner), or kept on the phone because it gives no call audio.
     */
    fun <C> elsewhere(state: String, owner: C?, client: C, blocked: Boolean): Boolean =
        state != "ringing" && state != "ended" && (blocked || owner == null || owner !== client)

    /**
     * Whether the Android phone shows its own call screen (and full-screen call pop-up): always, unless the owner turned
     * that off and the call's audio is with a client. A call answered or dialed on the phone itself ([owner] null), or
     * kept on the phone because it gives no call audio ([blocked]), always shows its screen.
     */
    fun <C> showsPhoneScreen(showWhenBridged: Boolean, owner: C?, blocked: Boolean): Boolean =
        showWhenBridged || owner == null || blocked

    /** The name a client shows for "on …": the owning client's, else the Android phone's own. */
    fun where(ownerName: String?, phoneName: String): String = ownerName ?: phoneName

    /**
     * How long a bridged call waits for its client after the client's link dropped. A Galaxy A73 (issue #13) lost the
     * link mid-call and the iPhone was back 6.3–6.5 s later every time (six drops, 2026-11-25); until 1.2.5 the audio
     * stopped at once, so the call jumped to the phone's own loud earpiece and back.
     */
    const val HOLD_MS = 10_000L

    /**
     * Keep the call bridged (the phone's earpiece and mic stay as they are, nothing reaches anyone) although no client
     * should have its audio now: only while it [running], its client's link is down ([targetUp] false), the call still
     * wants audio, nothing paused or blocked it, and for at most [HOLD_MS] since the link dropped ([lostForMs]).
     */
    fun holdForReconnect(running: Boolean, targetUp: Boolean, wantsAudio: Boolean, paused: Boolean, blocked: Boolean, lostForMs: Long): Boolean =
        running && !targetUp && wantsAudio && !paused && !blocked && lostForMs in 0 until HOLD_MS

    /** Whether the remembered "no call audio" still holds ([remembered] and [current]: "fingerprint|versionCode"). */
    fun blocked(remembered: String?, fingerprint: String): Boolean = remembered != null && remembered == fingerprint
}
