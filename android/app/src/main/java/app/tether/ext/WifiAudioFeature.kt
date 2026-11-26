package app.tether.ext

import app.tether.TetherApp
import app.tether.core.ext.WifiAudioExtension
import app.tether.core.ext.WifiAudioPolicy
import app.tether.link.Connection
import app.tether.link.Hub
import app.tether.link.LinkLog

/**
 * The Wi-Fi call path (`x.tether.wifiaudio`, protocol §7.11) as this app's user sees it: almost invisible.
 * The [WifiAudioExtension] does the protocol and owns the channel; this class owns the choices the core
 * library must not make: when an offer is worth accepting (only while this phone is bridging call audio to
 * that very client, on a local address it actually has — [WifiAudioPolicy.declineReason] decides), where
 * channel frames go (the audio session's uplink, exactly like Bluetooth audio frames), and which path a
 * downlink frame takes (the per-frame policy decision in [sendDown]). The phone never offers: it has no
 * network state of its own to judge, and the client knows when it is home.
 */
class WifiAudioFeature(private val app: TetherApp, private val hub: Hub, val ext: WifiAudioExtension) {
    init {
        ext.onOffer = { session, offer, message ->
            val conn = hub.connectionFor(session.link)
            val addr = LocalAddress.current()
            val why = WifiAudioPolicy.declineReason(
                dry = session.dry,
                channelOpen = ext.channelUp(session.link),
                bridging = conn != null && hub.audio.running && hub.audio.target === conn,
                hasAddress = addr != null,
            )
            if (why != null || conn == null || addr == null) {
                ext.decline(session, offer, message, why ?: "unavailable")
            } else {
                ext.accept(session, offer, message, host = addr.hostAddress!!, bind = addr)
            }
        }
        ext.onFrame = { link, payload ->
            hub.connectionFor(link)?.let { hub.onWifiAudio(it, payload) }
        }
        ext.onChannel = { _, up ->
            LinkLog.add("wi-fi call path: channel " + if (up) "up" else "down")
            hub.refresh()
        }
        ext.onDown = { _, _, _ -> hub.refresh() }
    }

    /**
     * Offers one downlink frame (a §4 audio payload) to [c]'s Wi-Fi channel; true when it went that way.
     * The per-frame decision: Wi-Fi only while the channel is up and has been heard from within the
     * freshness window — a channel that goes quiet mid-call stops being chosen, frame by frame.
     */
    fun sendDown(c: Connection, payload: ByteArray): Boolean {
        val link = hub.extensionLinkFor(c)
        val path = WifiAudioPolicy.sendPath(ext.channelUp(link), ext.lastHeardMs(link), System.currentTimeMillis())
        return path == WifiAudioPolicy.Path.WIFI && ext.sendFrame(link, payload)
    }

    /** The path [c]'s call audio is taking right now, for the UI. */
    fun pathFor(c: Connection): WifiAudioPolicy.Path {
        val link = hub.extensionLinkFor(c)
        return WifiAudioPolicy.sendPath(ext.channelUp(link), ext.lastHeardMs(link), System.currentTimeMillis())
    }

    /** The call to [c] ended: its channel ends with it, and the client is told over the session. */
    fun callEnded(c: Connection) {
        val link = hub.extensionLinkFor(c)
        val session = hub.extensions.sessions(ext).firstOrNull { it.link === link }
        if (session != null) ext.sendDown(session, "ended") else ext.closeChannel(link)
        hub.refresh()
    }
}
