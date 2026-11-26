# File transfer (`x.tether.files`) — design note

Stage A (this change): the extension in the core libraries, the wire rules in
[protocol/PROTOCOL.md §7.10](../protocol/PROTOCOL.md#710-built-in-xtetherfiles-version-1), and golden bodies in
`protocol/fixtures/x.tether.files*.json`. No app wires it up yet: nothing in the Android phone app, the Android
client or the iPhone app registers it, so no installed build announces `x.tether.files/1` and nothing changes for
users.

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

## What stage B still needs (app wiring)

- **Register** `FilesExtension` on every side (`PhoneExtensions.kt`, `ClientExtensions.kt`,
  `AppModel.registerExtensions`), handing it the live session's transcript hash as `keyMaterial` — the host owns
  the session, the core library never sees its traffic keys.
- **Stores and sources**: Android `FilesStore`/`FilesSource` over a user-picked folder and the media store
  (photos and videos land in the iTell-style Pictures/Movies/Download folders), iOS counterparts over the photo
  library and Files. For `dry` test links, in-memory stores only (EXTENDING.md rule 5).
- **Choosing the address**: the accept's `host`/`port` must be the receiver's Wi-Fi (or hotspot) interface
  address; the core receiver binds an address the host gives it. Same-network and hotspot cases, and the
  "both phones on the same Wi-Fi" hint, are UI concerns.
- **The iOS TCP engine**: `FilesWire` seals/opens the frames; the `NWConnection`/`NWListener` plumbing and the
  per-file digest loop mirror the Kotlin `FilesSender`/`FilesReceiver` and belong in TetherCore or the app.
- **UI**: pickers for sending, an accept/decline prompt (never auto-accept from a prompt-less state the user did
  not choose), progress with cancel, a "received" surface, and the English/Persian strings in both apps.
- **Status**: optionally surface an open transfer in `status.ext` (≤ 1 KiB) so the other side's Link tab can
  show it.
