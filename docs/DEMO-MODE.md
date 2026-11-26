# Demo mode ("Try with a Sample Phone")

Feature 5 of the iTell gap list (see `docs/` notes for features 1–4). iTell's iPhone app offers
"Try with a Sample Phone": the whole app fills with a believable fake Android phone — contacts, call
history, text threads — so someone who does not (yet) have a second phone can explore everything
before pairing. This note records what Tether had, the gaps, and what was built.

## What existed before

- A **developer-only** demo: `ios/Tether/App/DemoData.swift`, compiled only in `DEBUG` builds and
  driven by launch arguments (`-TetherDemo`, `-TetherDemoCall`, `-TetherDemoMessages`, …). It exists
  so the interface can be checked in the Simulator, which has no Bluetooth. End users never see it.
- Its safety model was already the right one: `AppModel.demoMode` (a static, set at launch) swaps in
  a throwaway identity (`MemoryIdentityStore`), starts from an empty snapshot and disables saving
  (`persist()` / `persistNow()` no-op), so a demo run can never touch the real pairing or the real
  synced data on the same device.
- The sample data itself was decent: a two-way conversation, bank/verification texts, a rich-text
  thread (links, codes, Persian), contacts, recents on two SIMs, a populated phone status and a
  Share VPN proxy state.

## Gaps vs iTell's demo

1. **Not shipped**: `#if DEBUG` + launch arguments only; a released build has no demo at all.
2. **Not discoverable**: no "Try the demo" entry anywhere in the UI.
3. **Not a session**: it is decided once at process launch; a user cannot enter or leave it, and a
   user who is already paired cannot try it without risking their real data (there was no
   stash/restore of the real snapshot).
4. **Newer surfaces unpopulated**: nothing for Files (feature 1, no received files), and the phone
   status carried no call-audio method state (feature 3: no `helper.methods` / `pick`), so those
   screens showed their empty states.
5. **Actions unexplained**: sending a text in the debug demo queued it in an outbox and said
   "Will send when the Android phone is back in reach" — wrong story for a demo. Other actions
   (net switches, USSD, contact edits) silently did nothing or, worse, would have reached a real
   phone had one been linked.

## Design

### One source of sample data: `DemoSample` in TetherCore

`ios/TetherCore/Sources/TetherCore/DemoSample.swift` builds the whole sample phone as pure data
(`DemoSample.make(now:) -> DemoSampleData`: sms, contacts, recents, status, block lists, net state,
received-file names). It lives in the core so `swift test` can pin it (two-way thread, call types on
both SIMs, populated helper methods, determinism). The old `DemoData.swift` content moved there and
was enriched:

- contacts: one contact with two numbers and an e-mail, starred favourites (photos stay initials —
  the avatar view draws them, as for real contacts without a photo);
- recents: incoming / outgoing / missed on both SIMs, missed with duration 0, plus unknown numbers
  and a short code, like the debug demo had;
- files: two received-file names for the Link tab's Files section;
- status: `helper.methods` (the feature-3 ladders) + `pick` + `fallback: false`, so the Call audio
  section shows its populated state; tools (USSD / find / ringer) on;
- net: Share VPN on with PAC / Telegram / QR payloads and a bypass list, as before.

The DEBUG launch-argument tool keeps its extra tricks (demo call screen, sample notifications,
router jumps, USSD answer) but now seeds through the same factory, so there is exactly one sample
phone to maintain.

### A runtime session in AppModel

`AppModel.isDemo` is a normal observable property (the static `demoMode` stays what it was: the
DEBUG launch-arg tool). `demoActive` is `isDemo || AppModel.demoMode` and gates saving, so the
DEBUG tool keeps its guarantees unchanged.

- `enterDemo()` stashes the current snapshot in memory (`stashedSnapshot`), clears the transients
  that belong to the real phone (net state, self-test, USSD, files offer/phase, audio feedback) and
  seeds the sample phone. Nothing is written: saving is gated on `demoActive`, so the on-disk
  snapshot still holds the real data the whole time.
- `exitDemo()` puts the stashed snapshot back, clears the demo transients, restores the real
  received-file names from the files store, updates the badge, and — if the link is up — requests a
  sync, so anything that arrived while demoing is picked up (see below). If the app is killed while
  in the demo, the next launch simply loads the real snapshot from disk: the demo never persists.
- Entering the demo while already paired is therefore safe: the real pairing (Keychain identity) is
  never touched, and the real data is restored verbatim on exit.

### Keeping the real link out of the demo

While `isDemo` is on, the real phone may still be linked (paired user trying the demo). Three gates
keep the two worlds apart:

- `received(...)` drops every incoming message. Anything the real phone sent meanwhile is not lost:
  the restored snapshot still has its pre-demo `smsSyncedUpTo`, and `exitDemo()` requests a sync.
- `linkChanged(...)` still tracks `linkState` but skips the connect side-effects (no sync request,
  no outbox flush, no microphone prompt).
- Actions that would reach the real phone are blocked at the model, not just greyed in the UI:
  `sendSms`, `dial`, `sendFiles`, `addContact` / `updateContact`, the delete actions, `runSelftest`,
  `sendUssd`, and every `net.*` / `audio.set` sender (`setNet`, `setDataSim`, `setNetworkMode`,
  `setShareBypass`, `setFinding`, `setRinger`, `setPhoneMic(Mute)`, `setAudioMethod`,
  `setAudioFallback`) return early through `demoGuard()`. `requireLink()` (delete / block / mute /
  star) answers with the demo explanation too. `markRead` still updates the demo's read marks
  locally but never sends `sms.read` for a demo thread. `pair()` / `unpair()` leave the demo first,
  so a pairing change always acts on the real state.

### Explaining instead of failing

`demoGuard()` puts one friendly line on the app's usual error capsule:
"This is a demo with a sample phone — that only works with a real Android phone."
Sending a text, placing a call, sending a file, flipping a phone switch — all land there instead of
silently doing nothing or pretending to work. The Files section's send buttons stay tappable in the
demo for the same reason (they show the explanation instead of opening the picker).

### Badging and entry points

- A persistent orange **demo banner** sits under the status strip in `RootView` whenever `isDemo`:
  "You're exploring a sample phone. Nothing here is real." with an **Exit demo** button. The status
  strip itself shows "Demo" with an orange dot and the sample phone's SIMs/battery.
- First launch (Link tab's setup guide, next to "Scan Pairing Code"): **Try the demo**, captioned
  "No Android phone yet? Explore a sample phone with made-up calls, texts and contacts first."
  Entering switches to the Messages tab so the richest sample data is up front.
- Already paired: a **Try the demo** row in the Link tab (footer: the real phone and its data stay
  untouched), and the Link tab's first section becomes a sample-phone header with its own Exit demo
  button while the demo runs. Pair again / Unpair are hidden during the demo.
- The first-launch logic that forces the Link tab while unpaired is suspended during the demo, and
  leaving the demo while unpaired returns to the Link tab.

### Android client

Not implemented there, deliberately. The Android client is a handset mirror: unpaired it shows only
a pairing screen, and every surface it has is a live view of the phone it is linked to — there is no
local store of sample calls/texts to browse (its data model is the session, not a snapshot). A demo
there would mean faking a whole session (link, sync, call states), a much larger and more fragile
piece than the iPhone's snapshot swap, for a screen whose job is to be used *with* a phone in hand.
Noted as a possible later addition if the client grows a local snapshot like the iPhone's.

## Left out, deliberately

- **No fake incoming call.** The DEBUG tool can open the call screen (`-TetherDemoCall`), but the
  user demo does not ring: reporting a made-up call through CallKit would look like a real call from
  a real person, and a call the user "answers" could only pretend. The Recents list shows what calls
  look like instead.
- **No persistence across launches** (see above): deliberate, it is what makes "nothing in the demo
  survives as fake history" trivially true.
- **Demo data edits are throwaway**: starring a sample contact or deleting a sample conversation
  either explains itself (actions that need the phone) or edits only the in-memory sample snapshot,
  which is discarded on exit. Drafts typed in the demo are likewise never saved.
