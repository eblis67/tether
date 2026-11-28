package app.tether.core

import org.json.JSONObject

/**
 * The control panel's feature switches (protocol §5.4). The Android phone is the source of truth, like the
 * block / mute lists: a client sends its whole map with `features.set`, the phone answers every client with
 * `features.state`, and a switch flipped on any phone applies on all of them.
 *
 * Every feature is OFF by default (the owner's explicit choice): a fresh install, and a phone that has never
 * seen a `features` message, has everything off. A feature that is off is not shown in the apps and does not
 * run — internet sharing off means the sharing proxy is not even started.
 *
 * Core telephony (bridged calls, SMS, contacts) is the base product and is never a switch. The switches cover
 * the extras: what Stage 3 adds (file transfer, Live Activity, demo mode) joins [Feature.ALL] with an entry in
 * [Feature.AVAILABLE] flipped on — no protocol or panel rework.
 */
object Feature {
    /** Internet sharing: Wi-Fi hotspot, Bluetooth tethering and the "Share VPN" proxy (see §5.3). */
    const val INTERNET = "internet"
    /** Carrier (USSD) codes from a client. */
    const val USSD = "ussd"
    /** "Find my phone": ring the Android phone from a client. */
    const val FIND = "find"
    /** Changing the Android phone's ringer mode from a client. */
    const val RINGER = "ringer"
    /** Wi-Fi file transfer (Stage 3: not in this build yet). */
    const val FILES = "files"
    /** Connection-status Live Activity on the iPhone (Stage 3: not in this build yet). */
    const val LIVE_ACTIVITY = "liveActivity"
    /** User-facing demo mode (Stage 3: not in this build yet). */
    const val DEMO = "demo"

    /** Every switch the panel knows, in panel order. */
    val ALL = listOf(INTERNET, USSD, FIND, RINGER, FILES, LIVE_ACTIVITY, DEMO)

    /** The switches whose feature exists in this build; the panels show only these. */
    val AVAILABLE = setOf(INTERNET, USSD, FIND, RINGER)
}

/**
 * One side's copy of the panel state: a value per feature id (absent = off) plus a revision that grows with
 * every change the side makes or adopts. Unknown ids are kept, so a newer phone's switches survive a round
 * trip through an older one.
 */
class FeatureSettings(val values: Map<String, Boolean> = emptyMap(), val rev: Long = 0) {

    fun isOn(id: String) = values[id] == true

    /** This side flips a switch: the value changes and the revision moves past everything seen so far. */
    fun with(id: String, on: Boolean) = FeatureSettings(values + (id to on), rev + 1)

    /**
     * Adopt a state received from the other side. A newer revision wins whole. The same or an older revision
     * loses — except the one switch the sender says it just flipped ([changedId]): two phones flipping two
     * different switches at the same time must not lose either flip, so that single value is taken and the
     * revision moves past both. Returns the state to keep (this, when nothing was adopted).
     */
    fun adopt(incoming: FeatureSettings, changedId: String? = null): FeatureSettings {
        if (incoming.rev > rev) return incoming
        if (changedId != null && incoming.isOn(changedId) != isOn(changedId))
            return FeatureSettings(values + (changedId to incoming.isOn(changedId)), maxOf(rev, incoming.rev) + 1)
        return this
    }

    /** The wire body of `features.set` / `features.state`; [changedId] only on a set that flips one switch. */
    fun json(changedId: String? = null): JSONObject {
        val f = JSONObject()
        for ((k, v) in values) f.put(k, v)
        return JSONObject().put("features", f).put("rev", rev).apply { changedId?.let { put("id", it) } }
    }

    override fun equals(other: Any?) = other is FeatureSettings && other.values == values && other.rev == rev
    override fun hashCode() = 31 * values.hashCode() + rev.hashCode()
    override fun toString() = "FeatureSettings($values, rev=$rev)"

    companion object {
        val DEFAULT = FeatureSettings()

        /** Parses a `features.set` / `features.state` body; a missing body is the default (all off, rev 0). */
        fun of(b: JSONObject?): FeatureSettings {
            if (b == null) return DEFAULT
            val f = b.optJSONObject("features") ?: return FeatureSettings(rev = b.optLong("rev", 0))
            val values = LinkedHashMap<String, Boolean>()
            val keys = f.keys()
            while (keys.hasNext()) { val k = keys.next(); values[k] = f.optBoolean(k) }
            return FeatureSettings(values, b.optLong("rev", 0))
        }

        /** The id a `features.set` body says it just flipped, if any. */
        fun changedId(b: JSONObject?) = b?.optString("id")?.takeIf { it.isNotEmpty() }
    }
}
