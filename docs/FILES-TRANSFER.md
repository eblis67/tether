# File transfer (`x.tether.files`) — design note

Stage A: the extension in the core libraries, the wire rules in
[protocol/PROTOCOL.md §7.10](../protocol/PROTOCOL.md#710-built-in-xtetherfiles-version-1), and golden bodies in
`protocol/fixtures/x.tether.files*.json`.

Stage B (built): all three apps register the extension and wire it end to end — pickers to send, an explicit
Receive/Decline answer to offers, progress with cancel, and a received-files surface. The Android phone app and
the Android client share the same shape (`ext/FilesHost.kt` stores/sources, `ext/FilesFeature.kt` state and
engines, a Files card in the main UI); the iPhone app has the TCP engines in TetherCore
(`FilesTransferEngine.swift`, over a blocking stream the app adapts from `NWConnection`/`NWListener`) and its
host in `Model/FilesFeature.swift`, with a Files section on the Link tab. Received files land in each app's own
folder (Android: the app-specific external `received` folder; iOS: Application Support `TetherReceived`), never
in shared storage. `dry` test links get in-memory stores only, and no offer notification (EXTENDING.md rule 5).

## Flow

1. The two apps already share a sealed session (§4) and have agreed `x.tether.files/1` through `caps`, as for
   every extension (§7.2). No new pairing, no new core message.
2. The sender offers over the control channel: `x.tether.files` pages `{tid, page, pages, key1, files}` — names,
   sizes, SHA-256 digests, and a fresh 16-byte salt. Only the last page is a request, so only it can be answered.
3. The receiver answers `x.tether.files.accept` `{tid, host, port, key2, have}` (its local-network address, an
   ephemeral port it is listening on, its own salt, and the bytes it already holds of each file) or
   `x.tether.files.decline`. `x.tether.files.list` lets either side browse the other's shared folders first,
   in pages, so an offer names files the user actually picked.
4. The sender connects over TCP and the bytes move as §2 records sealed with the §4 record layer: HELLO, then
   per file a FILE header (with the resume offset), 16 KiB DATA chunks, and a per-file ACK once the receiver's
   SHA-256 matches the offer. BYE ends it; the receiver then sends `x.tether.files.done` on the control channel.
5. `x.tether.files.cancel` and `x.tether.files.error` (codes in §7.10) end a transfer from either side at any
   point. What already arrived stays on the receiver, so a later offer of the same files resumes where it stopped.

## Security

- The control channel is the sealed session itself: only the paired device can offer, list or accept.
- TCP payload keys are one-time: HKDF-SHA256, salt = the session's transcript hash `th` (§3), input = both
  sides' fresh salts, info = the extension name and the transfer id; 64 bytes out, one key per direction. The
  link's traffic keys are never used as, or fed into, these keys, and a new transfer always derives new ones.
- The receiver accepts exactly one connection, on an ephemeral port, for 120 s, and only for the transfer it
  accepted; the first frame must name that `tid` under the derived keys, so a random device on the same Wi-Fi
  cannot inject or read anything (it cannot even complete a frame).
- File names are basenames only (no separators, no `..`), validated by both sides before anything is stored, so
  an offer cannot write outside the folder the receiver chose.
- A file is only ever reported complete after its SHA-256 matches the offer; a mismatch is ACKed `ok: false`,
  reported as `checksum`, and never treated as received.
- Nothing leaves the two devices: no server sees names, sizes or bytes. The core libraries log counts only, as
  §7.8 requires.

## Limits (§7.10)

One transfer per link at a time; at most 1 024 files; a file at most 2^41 bytes; offer and listing pages stay
under the §7.3 envelope limits (hosts should not start a transfer during a call, when the limit is 2 KiB);
30 s idle timeout on the TCP side; partial files are kept only under the names the offer gave them.

## Stage B notes (as built)

- **Registration**: `PhoneExtensions.kt`, `ClientExtensions.kt` and `AppModel.registerExtensions` register
  `FilesExtension` with the live session's transcript hash as `keyMaterial` — the host owns the session, the
  core library never sees its traffic keys.
- **Choosing the address**: the accept's `host` is the receiver's Wi-Fi (or hotspot) interface address, found
  by walking the network interfaces (Android `LocalAddress`, iOS `localIPv4Address()` — Wi-Fi first, then the
  hotspot bridge); the port is ephemeral. With no local address the user is told to join the same Wi-Fi or the
  other's hotspot and try again. iOS states this in `NSLocalNetworkUsageDescription`.
- **Sending picks**: Android uses the system document picker (files and photos alike); iOS has Photos and
  Files pickers, staging picks into a private cache folder so digests and reads see plain files.
- **Not built**: a browsing UI for `x.tether.files.list` (the cores implement it and all apps answer it with
  their received folder's contents, but no app asks yet — offers are always of locally picked files), and
  surfacing an open transfer in `status.ext`. Photos picked on Android keep the picker's display names; on
  iOS they get generated names (`photo-1.jpg`, …).
