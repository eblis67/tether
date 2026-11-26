# Wi-Fi call path (`x.tether.wifiaudio`) — design note

Feature 7: at home, a bridged call's audio rides the local Wi-Fi instead of Bluetooth — lower latency,
no BLE throughput ceiling, no 15 ms connection-interval quantisation — while Bluetooth stays what it
already is: the wake path, the pairing path and the control path. The wire rules are
[protocol/PROTOCOL.md §7.11](../protocol/PROTOCOL.md#711-built-in-xtetherwifiaudio-version-1); golden
bodies in `protocol/fixtures/x.tether.wifiaudio*.json`.

## What moves, what stays

**Only the audio frames move.** A call's audio today is channel `0x01` payloads (`codec ‖ seq ‖ frame`)
inside the sealed session (§4) over L2CAP/GATT. Under this feature the *same payloads* travel over a
short-lived TCP connection on the local network instead, one payload per frame of the channel below.
Everything else stays exactly where it is:

- **Signalling stays on the sealed BLE session**: `call.state`, `call.answer`, `audio.start` /
  `audio.stop`, `audio.set`, DTMF, hold, mute. A call is set up, answered and ended over Bluetooth
  exactly as before; the Wi-Fi path never carries a control message.
- **Wake stays on BLE**: the wake characteristic (§1) is what brings a suspended iPhone up for a ring;
  there is no Wi-Fi wake (iOS gives an app no way to be woken by a LAN packet), and none is needed —
  the channel is only established once `audio.start` says a call's audio is flowing.
- **Pairing stays on BLE**: the §3 handshake runs there as today; the Wi-Fi channel has no handshake
  of its own beyond being bound to that session (below).
- **SMS, contacts, sync, files**: untouched. `x.tether.files` (§7.10) keeps its own per-transfer
  channel; this feature's channel is per-call and unrelated.

## How the path is established and authenticated

An extension, `x.tether.wifiaudio/1`, agreed through `caps` like every extension (§7.2). The client
drives it, because only the client knows its own network state:

1. When a call's audio starts (`audio.start`) and the client is on a local network, the client sends
   `x.tether.wifiaudio` (a request) with a fresh channel id `cid` and a fresh 16-byte salt `key1`.
   Coming home mid-call is the same step: the client's policy re-evaluates while a call runs and
   offers then (see Policy).
2. The Android phone answers `x.tether.wifiaudio.accept` `{cid, host, port, key2}` — it is already
   listening on its local-network interface (Wi-Fi, or its hotspot interface when the client joined
   the phone's hotspot) on an ephemeral port — or `x.tether.wifiaudio.decline` `{cid, why}`.
3. The client connects over TCP. Both sides derive one-time keys exactly as §7.10 does, with this
   extension's info string: HKDF-SHA256, salt = the session's transcript hash `th` (§3), input =
   `key1 ‖ key2`, info = `"tether/1 x.tether.wifiaudio" ‖ 0x00 ‖ cid`; client → phone is
   `okm[0..32)`, phone → client `okm[32..64)`. The link's traffic keys are never inputs and never
   reused; a new channel (a new call, a re-offer) always derives new keys.
4. The client's first frame on the connection is HELLO `{cid}`; any other first frame, or a frame
   that does not open under the derived keys, closes the connection. The phone accepts exactly one
   connection per accept, and only within 15 s of it.

So there is **no unauthenticated listener**: the phone listens only after the paired client asked
inside the sealed session, on an ephemeral port, for 15 s, for one connection whose first frame must
prove possession of keys bound to that very session's transcript hash. A random device on the same
Wi-Fi can find the port but cannot complete a single frame.

## The channel

Frames are §2 records sealed with the §4 record layer under the channel keys (channel byte 0), the
same construction as §7.10's TCP session. A frame's plaintext is one frame byte and a payload:

| Byte | Name | Payload |
|---|---|---|
| 0x01 | HELLO | `{cid}` (JSON): the client's first frame |
| 0x02 | AUDIO | one §4 audio payload, verbatim: `codec ‖ seq ‖ frame bytes` |
| 0x03 | PING | `{ts}` (JSON): the client's clock, every 2 s |
| 0x04 | PONG | `{ts}` (JSON): the phone echoes it |
| 0x05 | BYE | empty: this side is done with the channel |
| 0x06 | ERR | `{code}` (JSON) |

One channel per link. The channel lives for one call: either side ends it with BYE when the call's
audio stops, and `x.tether.wifiaudio.down` on the control channel says so too (so the peer's UI and
policy settle even if the BYE was lost with the connection).

## Migration, and why there is no migration handshake

**The path is a per-frame send decision; receivers are path-agnostic.** A frame's `seq` is assigned
by the sender's audio pipeline *before* the path is chosen (the iPhone's `CallAudio.seq`, the phone's
`AudioSession.seq`), and `seq` keeps counting for the whole call (§4) whichever path a frame takes.
Both receivers already impose order by `seq`: the iPhone drops a frame whose `seq` does not advance
and conceals the gap; the phone drops duplicates/late uplink frames and conceals 1–2 missing ones.
So migration is not an event that needs negotiating:

- The channel comes up mid-call → the sender's next frames go over Wi-Fi; a frame still in flight
  over BLE arrives whenever it arrives and is used or dropped by `seq`, like any late frame.
- The channel dies mid-call (TCP error, silence past the dead timeout, a BYE) → the sender's next
  frames go over BLE. Nothing is re-sent and nothing waits: at worst a few frames are concealed.
- No frame is ever deliberately sent on both paths; one in flight on the old path while the new
  path carries later frames is the only overlap, and `seq` settles it.

What Wi-Fi loss mid-call looks like to the user: the send path reverts to Bluetooth within the
policy's pong timeout (6 s worst case, typically at the TCP error itself), with at most a short
concealed patch in the audio — the call never drops, because the call was never on Wi-Fi; only its
frames were.

## Policy (pure, in both core libraries: `WifiAudioPolicy`)

One shared set of decisions, mirrored in Swift and Kotlin and unit-tested on the Swift side:

- **Offer** (client): enabled by the user, a call's audio is running, the client has a local-network
  address, no channel is up or being built, fewer than 4 attempts this call, and the last attempt
  was ≥ 15 s ago.
- **Accept** (phone): decline `dry` for a test link (a test client must never open a real socket),
  `busy` while a channel is open on that link, `unavailable` when no call's audio is bridged to that
  client (nothing to carry) or the phone has no local-network address.
- **Send path**: Wi-Fi while the channel is up and the peer was heard from within 6 s (a PONG, or
  any frame); otherwise Bluetooth.
- **Dead channel**: nothing heard for 15 s → close it (and the client may offer again, per above).
- **PING/PONG**: the client pings every 2 s; any inbound frame counts as being heard.

## What each side shows

- **iPhone, Link tab › Call audio**: a *Wi-Fi call path* switch (on by default) and, while a call
  runs, a *Call path* row: **Wi-Fi** or **Bluetooth**. The phone also reports the path in
  `status.ext["x.tether.wifiaudio"]` (`{path: "wifi"}` while its channel to that client is up), so
  the row can show the phone's view, not just the iPhone's.
- **Android phone, Audio card**: while a call is bridged, a *Call path* line: Wi-Fi or Bluetooth.

## Honest limits

- **The Android client is not wired in this pass.** The phone's extension is client-agnostic — any
  client that announces `x.tether.wifiaudio/1` can use the path — but the Android client app does
  not announce it yet, so its calls stay on Bluetooth. The iPhone is the client this feature ships
  for (it is the client iTell's Wi-Fi path serves too).
- **The phone never initiates.** Only the client offers: it is the side that knows it is on the
  home Wi-Fi. Walking *out* mid-call needs no offer (the channel just dies and frames revert);
  walking *in* is the client's reachability change, which it sees itself.
- **No pre-call channel.** Establishment starts at `audio.start`, so the first fraction of a second
  of a call at home may ride Bluetooth before the path migrates. Keeping a channel open all day for
  instant migration was rejected: an always-on listener and connection for a maybe-call is the
  wrong trade, and migration is seamless anyway.
- **The hotspot path is the same path.** When the iPhone joins the Android phone's hotspot, the
  phone's hotspot address is its local-network address and the channel runs over it unchanged —
  there is no separate hotspot mode. USB tethering is not used: an iOS app cannot reach the phone
  over USB.
- **Same network means same network.** AP/client isolation, a guest SSID, or the two devices on
  different bands behind a router that keeps them apart makes the connect fail; the policy backs
  off and the call stays on Bluetooth, silently (a line in the link log, no error to the user —
  the call was never at risk).
- **No quality promises.** Wi-Fi is usually lower-latency than BLE here, but a congested 2.4 GHz
  network can be worse; the policy does not compare paths by measured RTT and switch back and
  forth — it uses Wi-Fi while it is healthy and Bluetooth while it is not. (The channel does
  measure PONG round trips; they are logged with the call's audio statistics, not acted on.)
