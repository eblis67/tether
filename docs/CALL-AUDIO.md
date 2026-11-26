# Call-audio methods: design note (feature 3)

How iTell's call-audio "method zoo" maps onto Tether's audio architecture,
what Tether can honestly offer on this phone class, and what this feature
adds: a method registry, per-model memory, a human feedback loop, a manual
picker, and an explicit speaker/microphone fallback.

## How iTell does it (from the static analysis, `~/workspace/itell-analysis/REPORT.md`)

iTell's Android side runs a privileged shell service (started through
Wireless Debugging) whose only job around calls is audio. It offers ~25
named capture/injection methods (animal codenames), picks one per phone
model automatically, verifies it with a test call, and asks the user
"could they hear you?" — a "no" makes it pick another method. When nothing
works it falls back to "Pig" mode: the Android phone's own speaker and
microphone carry the call. Rooted phones get a one-step setup instead.

The zoo exists because every vendor audio HAL breaks call audio in its own
way, and iTell's answer is a large per-model recipe book, tuned with data
from real users (and, per the analysis, adjustable per user by their
support). The recipes are still built from the same small set of Android
audio routes — there are only so many ways to hear a call and to speak
into one.

## How Tether does it today

Tether's Android phone app also runs a shell helper (uid 2000, started the
same way, protocol §6), and the helper is where call audio lives:

- **Capture** (the far end's voice) and **inject** (the client's voice into
  the call) are chosen **independently**, each from a short ladder in
  `CallAudio.captureMethods` / `injectMethods`; `auto` tries the ladder in
  order and keeps the first route that opens. A single route can be forced
  through the START message.
- A **self-test** (protocol `selftest`, needs a live call) plays a 1 kHz
  tone through each inject route and listens for it on `VOICE_UPLINK` with
  a Goertzel filter — a machine-verified "the far end hears us" — and
  likewise checks each capture route for real call audio. The hub stores
  the winning inject route (`chosen`) and starts later calls with it.
- The phone's own microphone is handled separately (`MicPath` redirect
  audio modes, `LocalMutes` vendor keys), each with **per-model memory**
  of what checks found, and an in-call uplink check that undoes a mute
  which silences the client's voice.
- A firmware that gives no call audio at all (the downlink reads only
  zeros in a real call) is remembered by build fingerprint ("blocked"):
  its calls stay on the phone until the firmware changes or a self-test
  hears the call again.
- Status (`helper.method` / `helper.capture`) already tells clients which
  routes the current or last call used.

So Tether already had: the shell service, method ladders, a (stronger,
machine) test, and per-model learning for the *microphone* problem. What
it did not have is what this feature adds:

1. The methods were bare string literals inside the helper — no registry
   saying what each route is, what it needs, or which Android versions
   offer it; nothing could list them to a user.
2. The self-test's choice was one device-level preference for inject
   only; capture was never remembered, and no per-method results were
   kept.
3. No human feedback: nothing asked "could they hear you?", so a route
   that opens and passes the tone test but fails real speech (wrong
   device on some HALs, a far end on a bad network leg) was never voted
   down by the person who knows.
4. No manual method picker, and no explicit speaker/microphone fallback
   the user can choose — calls stayed on the phone only as an accident
   (answered there, or a "blocked" firmware).

## What a "method" means in Tether

One direction's route. Capture and inject fail independently on real
phones (a classic case: the downlink is fine while the injection plays
on the phone's own speaker), they are opened from separate ladders, and
the self-test scores them separately — so the registry registers each
direction's routes as its own methods, and memory, feedback and the
picker are per direction too. iTell's single codename ≈ Tether's
(capture, inject) pair; the pair the phone is using is what status
reports and what the picker shows.

The registry (`app.tether.helper.AudioMethods`) — every route the helper
can really open:

| id | dir | Android | needs | what it is |
|---|---|---|---|---|
| `intercept` | capture | 13+ | shell helper, PSTN interception API | `getCallDownlinkExtractionAudioRecord`: the call's downlink, extracted by the system |
| `voice_downlink` | capture | 11+ | shell helper | `AudioRecord` on `VOICE_DOWNLINK` |
| `telephony_rx` | capture | 11+ | shell helper | default source, pinned to the telephony input device |
| `voice_call` | capture | 11+ | shell helper | `AudioRecord` on `VOICE_CALL` (both sides mixed on many HALs) |
| `intercept` | inject | 13+ | shell helper, PSTN interception API | `getCallUplinkInjectionAudioTrack`: straight into the call's uplink |
| `telephony_tx` | inject | 11+ | shell helper | a media track pinned to the Telephony Tx device; the route is verified after opening — some HALs quietly play it on the phone instead (issue #15) |
| `policy_mix` | inject | 13+ | shell helper | a dynamic audio-policy mix rendering a signalling-usage track to Telephony Tx |

Ladder order (preference, per Android version) lives in the registry and
`CallAudio` takes its ladders from it, so there is one source of truth.

Not registered, on purpose:

- `voice_uplink` capture — not a call route; it is the *check* capture
  (it hears what the uplink carries, injected voice included) used by the
  self-test and the in-call uplink check.
- `voice_communication` — openable in the helper but in no ladder; it
  records the phone's own mic path, not the call.
- The redirect audio modes (`call_redirect`, `call_screening`,
  `communication_redirect`) — they decide what the *phone's own* mic and
  earpiece do during a bridged call, not how the client's voice travels;
  they already have their own per-model verdict memory (`MicPath`).

Plus one non-helper "method", offered everywhere including Android
8–10: **`phone`** — the speaker/microphone fallback (below).

## What is not feasible (and is not faked)

- **~25 distinct routes.** Stock Android exposes only the routes in the
  registry; more "methods" would be the same routes renamed, or the same
  route with a knob turned. Where a vendor quirk matters, Tether already
  encodes it where it belongs: the per-vendor silence keys (Samsung /
  MediaTek / Qualcomm, `CallAudio.Silence`), the redirect modes, and the
  route verification in `telephony_tx`. New vendor quirks should land as
  registry notes and checks, not as new codenames.
- **A shipped per-model recipe book.** iTell learns which recipe fits a
  model from its whole user base (and its server). Tether is offline and
  serverless by design, so it learns **per device instead**: self-test
  results, in-call checks and now user feedback, all remembered for this
  phone's model (`MethodChoice`, below) and never uploaded anywhere.
- **Root quick-setup.** Tether does not ask for root anywhere else; the
  shell helper already has the privileges the routes need.
- **Anything without the helper.** Every real route needs the shell's
  privileges (hidden interception API, call audio sources, audio policy).
  Without the helper the only honest offer is the `phone` fallback —
  which is also what Android 8–10 gets.

## What this feature adds

### 1. Registry — `AudioMethods` (helper module)

As above: ids, direction, minimum SDK, whether the PSTN interception API
is required, and a one-line description per method. `CallAudio`'s ladders
are derived from it. The phone app publishes the routes this phone can
offer in status (`helper.methods`), so clients can show a real picker
instead of guessing ids.

### 2. Per-model memory — `MethodChoice` (helper module, stored by the app)

One JSON record per phone model (`audio_methods:<model>` in the phone
app's preferences, the same pattern as the mic memories):

- `manualInject` / `manualCapture` — the user's picker choice (absent:
  automatic).
- `chosenInject` / `chosenCapture` — the self-test's verified winners
  (the old device-level `inject` preference is adopted as
  `chosenInject` the first time the record is read).
- `tested` — the last self-test's verdict per method (`dir:id` → ok).
- `heard` / `notHeard` — the user's feedback counts per method.

Selection (pure, unit-tested): manual pick first; else the self-test's
choice while it is still offered on this Android version and the user
has not voted it down; else `auto`. In `auto`, methods the user voted
down (more "not heard" than "heard") are passed to the helper as a skip
list (START `captureSkip` / `injectSkip`, protocol §6) so the ladder
moves past a route that opens but does not reach the far end; if every
route is voted down the full ladder is used — the vote may have been
about the far end, not the phone. A method the self-test later verifies
has its "not heard" votes cleared: the tone in the uplink is stronger
evidence than one bad call.

### 3. Test-call feedback loop

The self-test already runs during a live call from the handset apps
("Run call-audio self-test"). Now, when a self-test ran during a call,
the handset app asks the two questions the tone cannot answer about real
speech:

- "Could the other side hear you?" → votes on the **inject** method the
  call used.
- "Could you hear the other side?" → votes on the **capture** method.

The answers go to the phone in `audio.set {feedback:{you, them}}`
(protocol); the phone attributes them to the methods its helper last
reported for the call (it does not trust the client to name them),
records them in the per-model memory, and a "could not hear you" also
clears that inject method's `chosen` so the next call re-picks at once.
The link log says what was remembered. This is iTell's loop with the
machine test kept in front of it: the tone test runs first and only a
call the user actually judged changes the human record.

### 4. Manual picker

Status `helper.methods` lists what this phone offers and `helper.pick`
the current manual choices; `audio.set {inject}` / `{capture}` change
them (`"auto"` clears), applied to a running call by restarting its
audio paths with the new pick. The iPhone app shows the two pickers in
the Call audio section; the Android phone app shows the same as radio
rows in its Audio card. The Android client app does not send `audio.set`
for anything (it never had the mic switch either); it takes part in the
feedback loop only.

### 5. Speaker/microphone fallback — `phone`

A user choice, not an error state: status `helper.fallback: true`,
`audio.set {fallback}`. While it is on, calls are never bridged — the
client still answers, places, holds and ends them, but the user hears
and speaks on the Android phone itself, exactly like a call answered on
the phone (and clients are told the call is there, per §5.1, with the
fallback named as the reason). Turning it on during a call hands the
call back to the phone (bridging stops, the phone's earpiece and mic
come back). It is the always-available last resort: no helper, no
Android-version requirement — and it is what a phone whose methods all
failed should end up on, with the user told so, instead of silent calls.

## Protocol additions (PROTOCOL.md)

- status `helper`: optional `methods: [{id, dir}]`, `pick: {inject,
  capture}`, `fallback: true`.
- `audio.set`: optional `{inject}`, `{capture}`, `{fallback}`,
  `{feedback: {you, them}}` alongside `{mic}` / `{micMute}`.
- Helper START (§6): optional `captureSkip` / `injectSkip` id lists,
  applied to the `auto` ladders only.
