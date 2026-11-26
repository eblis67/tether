# Per-brand setup wizard: design note (feature 4)

How iTell's per-brand setup guides map onto Tether, the step catalogue the
in-app wizard ships, and — honestly — which steps Android lets an app
check for itself and which it can only ask the user to confirm.

## How iTell does it (from the static analysis, `~/workspace/itell-analysis/REPORT.md`)

iTell ships per-brand guided setup (Xiaomi/Redmi/POCO, OPPO/realme/OnePlus
ColorOS, Meizu Flyme, Samsung) because those makers kill background apps
far more aggressively than stock Android: autostart bans after reboot,
maker battery savers on top of Android's, "sleeping apps" lists, and
swipe-away-from-Recents kills. A bridge app that sits in a bag and must
stay linked all day dies on all of these unless the user changes maker
settings that no app may change for itself.

## How Tether does it today

`docs/SETUP.md` describes the same ground as text, and the phone app's
Setup card has four self-checking steps (permissions, default phone app,
unrestricted battery, Wireless debugging pairing) plus the TCP-adb
fallback. What is missing is exactly the maker layer: nothing in the app
mentions autostart, the makers' own battery savers, Samsung's sleeping
apps, or locking the app in Recents — the four things that actually kill
Tether on these phones a day after setup looked complete.

## The wizard

One new file, `android/app/src/main/java/app/tether/ui/SetupWizard.kt`,
holds the catalogue and the checks; `MainActivity` renders it with the
same programmatic card/step patterns as the rest of the screen.

- The brand is detected from `Build.MANUFACTURER` / `Build.BRAND`:
  Samsung, Xiaomi (incl. Redmi/POCO), ColorOS family (OPPO, Realme,
  OnePlus), Meizu, else a generic Android path with no maker steps.
- It opens by itself on the first launch when anything is still missing
  (once — a pref remembers it was offered), and can be re-opened any time
  from the Setup card, which shows a compact status row ("N steps left"
  / "all steps done") until everything passes.
- Steps the app can verify re-check themselves on every render — and
  `MainActivity` re-renders on resume, so coming back from a Settings
  screen updates them with no extra plumbing. Steps it cannot verify
  carry an explicit **I did it** button; the confirmation is stored in
  prefs (`wizard_ok_<step>`), so a finished wizard never nags again.
- Every step explains itself in plain words, and where a settings screen
  exists the step's button opens the best one the maker ships, falling
  back to Tether's app-details screen when the maker's screen is absent
  (renamed/removed between maker OS versions — this happens in practice).

## Step catalogue

Universal steps first (already in the Setup card; the wizard reuses the
same checks and actions), then the maker steps, then the helper steps.

| Step | What it fixes | Brands | Verified? | Opens |
| --- | --- | --- | --- | --- |
| Permissions | SMS/contacts/calls/Bluetooth/notifications access | all | yes — runtime permission state | system permission dialog |
| Default phone app | Only the default dialer may see and control calls | all | yes — dialer role held | dialer role dialog |
| Unrestricted battery use | Android Doze / battery optimisation stopping the link service | all | yes — `PowerManager.isIgnoringBatteryOptimizations` | system battery-exemption dialog |
| Allow auto-start | Maker bans app restarts after reboot/cleanup until allowed | Xiaomi, ColorOS, Meizu | **no** — no public API exposes the flag | MIUI AutoStartManagement / ColorOS StartupAppList where present, else app details |
| Maker's battery setting | The maker's own saver (MIUI Battery saver, ColorOS battery limits, Flyme power saving) stops Tether even when Android's exemption is set | Xiaomi, ColorOS, Meizu | **no** — the flag lives in the maker's apps | Tether's app-details screen, where the maker puts its battery choice |
| Never sleeping apps | Samsung moves "unused" apps to a sleeping list that cannot keep the link | Samsung | **no** — the list is not readable | system battery-optimisation list (the help text gives the exact Samsung path from there) |
| Lock in recent apps | Swiping Tether away on these makers force-stops it | Samsung, Xiaomi, ColorOS, Meizu | **no** — Recents state is not visible to apps | nothing — the gesture happens in the Recents screen itself |
| Wireless debugging | Pairs the shell helper that carries call audio and the network switches | all (Android 11+) | yes — secure-settings grant + paired pref (same check as the Setup card) | Developer options (+ pairing notification) |
| Audio helper | The helper process itself is running | all | yes — helper state, as the Call audio card shows it | — (Start button) |

Per-brand step lists shipped (universal steps omitted):

- **Samsung (One UI):** Never sleeping apps → Lock in recent apps.
- **Xiaomi (MIUI/HyperOS):** Allow auto-start → Maker's battery setting
  ("No restrictions") → Lock in recent apps.
- **ColorOS (OPPO/Realme/OnePlus):** Allow auto-start → Maker's battery
  setting → Lock in recent apps.
- **Meizu (Flyme):** Allow auto-start → Maker's battery setting → Lock in
  recent apps.
- **Generic Android:** no maker steps — Doze is the only killer, and the
  verified battery step already covers it.

## Honest limits

- **Autostart, maker battery flags, Samsung's sleeping list and the
  Recents lock cannot be read by any app.** There is no public API; the
  makers keep them inside their own system apps. Any app claiming to
  "check" them is guessing. The wizard therefore asks the user to
  confirm, and says so in the step text.
- **Deep links are best-effort.** MIUI's autostart activity and
  ColorOS's startup list exist on most builds but are renamed or removed
  on some; Flyme exposes no autostart screen to other apps at all, so
  the Meizu step opens app details and its text walks through the
  Security app instead. When a maker's screen is missing, the button
  falls back to app details and the help text gives the full manual
  path, so the step is still completable.
- **Samsung's "Never sleeping apps" screen cannot be deep-linked**
  either; the button opens the system battery list and the text gives
  the exact path (Settings › Battery › Background usage limits › Never
  sleeping apps › + › Tether).
- Nothing here grants Tether new powers: every maker step is the user
  changing a system setting, exactly as in `docs/SETUP.md`.

## Why the phone app only, not the Android client

The wizard ships in the SIM-host phone app only. The second-Android
client is the handset in the user's hand: it holds no dialer role, runs
no shell helper, serves no hotspot/proxy, and if a maker kills it the
user simply reopens it — no calls are lost on the phone side. It already
has the two prompts it needs (link permissions, battery exemption) on
its "This phone" card. The survival-critical side is the phone that
sits in a bag holding the SIM, and that is where the makers' autostart
and sleeping-apps settings bite.
