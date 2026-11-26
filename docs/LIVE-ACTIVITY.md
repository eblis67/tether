# Live Activity (the Android phone on the Lock Screen and Dynamic Island)

Feature 6 of the iTell gap list. iTell's iPhone app keeps a Live Activity with the Android phone's
signal and battery on the Lock Screen and in the Dynamic Island, so a glance at the locked iPhone
answers "is the other phone alive, charged and in coverage?" — and, during a bridged call, "who am I
talking to, and for how long?". This note records the design and what was built.

## What it shows

One activity per paired phone, mirroring the in-app status strip (`StatusStrip`) plus the call:

- the Android phone's name and whether the link is up;
- battery percent and charging, with the same five-step battery icon the status strip uses;
- every SIM: name (when there are two), signal bars (0–4), network label (5G / LTE+ / …), carrier,
  roaming mark, or "No service" / "SOS";
- during a bridged call: who the call is with (contact name where known), incoming / on hold /
  on the Android phone itself, and the elapsed time once connected.

Nothing else: no message content, no numbers beyond the current call's party (which CallKit already
shows on the Lock Screen anyway).

## Target structure: a widget extension is required

ActivityKit splits the work in two: the **app** starts, updates and ends activities, but the **UI** —
Lock Screen banner and all Dynamic Island regions — is rendered by a **WidgetKit extension** through
`ActivityConfiguration`. There is no app-target-only form: an activity requested without a widget
extension providing its UI has nothing to draw and never becomes visible. So this feature adds one
target, `TetherLiveActivity` (type `app-extension`, in `ios/TetherLiveActivity/`), embedded in the
app. Costs weighed and accepted:

- `project.yml` gains the target and one dependency line on the app target; XcodeGen generates the
  embed phase and the extension's Info.plist (with the `com.apple.widgetkit-extension` point
  identifier) from the spec, as CI already regenerates the whole project before building.
- The IPA step copies the whole `.app`, so the extension in `PlugIns/` ships automatically, and
  AltStore/SideStore re-sign it together with the app. Live Activities need **no entitlement and no
  App Group**: the only requirement is `NSSupportsLiveActivities` in the *app's* Info.plist, which is
  added. Content state travels through ActivityKit itself, not through shared storage.
- The extension's deployment target is **iOS 16.1** (Live Activities begin there) while the app
  stays at 16.0: on iOS 16.0 the system simply never loads the extension, and every app-side
  ActivityKit call is availability-guarded. The app gains no new minimum version for this feature.

The shared type problem — the app and the extension must agree on the activity's attributes and
content state — is solved the way the rest of the app shares protocol types: both targets link
**TetherCore**, and `PhoneActivityAttributes` (in TetherCore, behind `#if canImport(ActivityKit)`)
names `PhoneLiveState` as its content state. The alternative, a copy of the attributes struct in each
target kept in sync by hand, is exactly the drift the core package exists to prevent.

## Content state and mapping (TetherCore, pure)

`PhoneLiveState` is a flattened, Codable/Hashable snapshot of what the UI draws: device name,
battery (clamped 0–100), charging, per-SIM rows (signal clamped to the 0–4 bars the UI draws, net,
service, roaming, which SIM is the data/default one), `linked`, and an optional call (label,
incoming, held, elsewhere, and the local-clock start from `CallClock`, so the extension can render
the elapsed timer without any clock math of its own).

`PhoneLiveState.make(status:deviceName:linked:call:callLabel:peerOffsetMs:)` maps a `StatusBody`;
`PhoneLiveState.pickCall(from:)` chooses which of the phone's calls the activity features (a call
whose audio is here beats one elsewhere; active beats holding beats dialing beats ringing; ended
calls never feature). Both are pinned by unit tests, as is the Codable round-trip — that JSON is the
contract between the app process and the extension process.

## Lifecycle (policy in TetherCore, mechanics in the app)

`LiveActivityPolicy.decide(...)` is a pure function of (setting on?, paired?, demo?, connected?,
current state, next state) → start / update / end / nothing, so the whole lifecycle is testable
without ActivityKit. `LiveActivityController` (app target) feeds it from `AppModel` whenever its
inputs change — link up/down, a new `status` from the phone, a call change, entering/leaving the
demo, unpairing, the setting itself — and executes the answer, adopting an activity a previous
process left running instead of starting a second one.

- **Start**: when the link comes up and the first status is in. Not before: an activity with no
  battery/SIM data yet would be an empty shell.
- **Update**: only when the state actually changed (the policy compares; equal states are a no-op).
- **End**: the setting switched off, unpairing, or entering the demo end it at once. Losing the link
  ends it with one final update — `linked: false`, the last known battery and signal — that the
  system keeps on screen for five more minutes ("Out of reach" with the last facts, honestly stale)
  and then dismisses; if the link returns first, a fresh activity starts.

### Cadence and the ActivityKit budget

No polling is added. The Android phone already pushes `status` **only when something changed**
(`Hub.pushStatus` dedupes per client: "every status wakes the iPhone app"), so updates arrive at the
rate the facts change — a battery percent every so often, signal when it moves — far inside
ActivityKit's budget (the system throttles frequent Dynamic Island updates; the budget resets daily,
and Tether's change-driven trickle never approaches it). The one-minute link watchdog is untouched
and is *not* used as an update driver. Each start/update also sets a **stale date one hour out**: if
the app is killed and the phone later dies silently, iOS marks the activity stale instead of showing
an hour-old battery as fresh; the system's own 8-hour activity limit is the final backstop.

## Demo mode

The demo never starts a Live Activity, and entering the demo ends a running one at once. The Lock
Screen is system space: a sample phone's battery and signal there would be indistinguishable from
the real phone's, and the demo's promise is that nothing fake survives or poses as real
(docs/DEMO-MODE.md). The in-app status strip already shows the sample state for anyone exploring.
Exiting the demo re-runs the policy, so a still-linked real phone gets its activity back.

## The switch

Link tab, "Lock Screen" section: **Live Activity**, on by default (`@AppStorage`, the same pattern
as "Show calls in the Phone app"), shown when paired and not in the demo. Turning it off ends the
activity immediately; the footer says what it shows and that it needs iOS 16.1 or later. If the user
has disabled Live Activities for the app in iOS Settings, starting quietly fails and nothing nags.

## Left out, deliberately

- **Push-updated activities** (`pushType: .token`): updates would need a server; Tether is
  peer-to-peer by design. All updates are local, driven by the link.
- **`NSSupportsLiveActivitiesFrequentUpdates`**: for sports-score-style bursts; Tether's
  change-driven updates do not need the larger budget, and asking for it would be dishonest.
- **A home-screen widget** with the same data: the extension ships only the `ActivityConfiguration`.
  A widget is a separate feature (it would need an App Group or a link-independent data path).
- **Per-call activities**: one activity features the current call; a second activity per call would
  fight CallKit's own Lock Screen UI.
