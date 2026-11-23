# Roadmap

Near-term plans, roughly in order. Nothing here is a promise; [Discussions](https://github.com/amirwmr/tether/discussions)
is the place to suggest or argue priorities, and issues labelled `help wanted` are open for anyone.

## Verify on more devices

Tether is tested on one pair (a Samsung Galaxy A54 and an iPhone). Most other rows of the
[compatibility table](https://github.com/amirwmr/tether#compatibility) are expectations from the APIs used.

- Reports from other Android phones, especially non-Samsung ones on Android 13+, and Android 10–12
  ([device report](https://github.com/amirwmr/tether/issues/new?template=device_report.yml)).
- Confirm the per-vendor muting of the phone's own earpiece and microphone on Qualcomm and MediaTek phones.
- iPad and iOS 16 on real devices (so far simulator and build only).
- The Android client on a real second phone (so far tested against a simulated Android phone), and next to an
  iPhone on the same Android phone.

## Call audio

- Fewer Bluetooth hold-ups mid-call (the last ~0.3–1 % concealed audio) and a shorter hold-up at call start.
- Check playout timing on real carrier calls on more phones.
- Possibly 10 ms frames and a smaller inject buffer, once measurements show they help.

## Developer platform

- Stable, versioned Swift and Kotlin SDKs with API reference on the documentation site.
- Protocol extensions for third-party message types, with examples.
- Automatic releases with APKs and an unsigned IPA.

## Not planned

MMS, RCS, group messages, video calls and visual voicemail are out of scope for now (see Limits in the README).
