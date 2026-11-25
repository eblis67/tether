---
title: FAQ
---

# Frequently asked questions

[راهنمای فارسی و پرسش‌های رایج](fa/faq.md) · [Install guide](INSTALL.md)

### How do I download it?

From the [latest release](https://github.com/amirwmr/tether/releases/latest): `Tether-A54-….apk` for the Android
phone with the SIMs, `Tether-…-unsigned.ipa` for the iPhone, `Tether-Client-….apk` for a second Android phone.
Which one you need: [Install guide, section 2](INSTALL.md#2-download).

### How do I install the .ipa? Do I need a Mac?

No Mac is needed. Sideload the IPA with your own Apple ID using SideStore (a computer once, then refreshes on the
iPhone), AltStore (Mac or Windows) or Sideloadly (Mac or Windows); TrollStore on the iOS versions it supports. Add
the source `https://github.com/amirwmr/tether/releases/latest/download/altstore.json` in SideStore or AltStore to get
updates. Step by step: [Install the iPhone app](INSTALL.md#4-install-the-iphone-app).

### Why does the iPhone app stop opening after a week?

A free Apple ID signs apps for 7 days. Refresh it in your sideloading tool before then; your data and pairing are
kept. A paid developer account (building it yourself, [BUILD.md](BUILD.md)) signs for a year; TrollStore does not
expire.

### Does it work Android to Android?

Yes. Install `Tether-Client-….apk` on the second Android phone (Android 10+) and pair it with the SIM phone's QR
code. It has calls with audio, texts, contacts, recents and the Link switches
([ANDROID-CLIENT.md](ANDROID-CLIENT.md)). It is new and so far tested against a simulated phone, not yet on two
real Android phones: reports are welcome.

### Does it work iPhone to iPhone?

No. The phone with the SIM cards must be Android: iOS does not let any app answer or place the iPhone's own calls,
or read and send its texts. The phone in your hand can be an iPhone, an iPad or an Android phone.

### Which Android versions work? Does Android 10 support calls?

| Android | Texts, contacts, call history | Answer / place / end calls from the iPhone | Call audio on the iPhone |
| --- | --- | --- | --- |
| 8 and 9 | yes | yes | no (talk on the Android phone) |
| 10 | yes | yes | no (talk on the Android phone) |
| 11 and 12 | yes | yes | best effort, depends on the phone |
| 13 and later | yes | yes | yes, best support |

Call audio also needs the phone's Android build to let the shell user capture call audio; the self-test in the app
tells you. More: [features by Android version](devices/android-versions.md).

### Can it fully integrate with the iPhone's Phone and Messages apps?

Partly, within what iOS allows any app:

- **Calls** use the iPhone's own call screen (CallKit): lock screen ringing, answering from AirPods or the car,
  Bluetooth headsets. Optionally (Link tab › Numbers in other apps › **Show calls in the Phone app**) Tether calls
  appear in the Phone app's **Recents** and you can call back from there. Tether's own Recents tab always has the
  full history of the Android phone.
- **Messages**: no. iOS has no way for an app to put texts into the Messages app or send SMS through it. Texts are
  in Tether's own Messages tab, with notifications and quick reply.
- **Contacts** are shown in Tether's own Contacts tab; they are not written into the iPhone's Contacts app.
- **Tapping a phone number** elsewhere can open Tether through Shortcuts / Siri ("Call with"), and on iOS 18.2+ with
  a paid developer account Tether can be the default calling app. Details: [USAGE.md](USAGE.md#calling-numbers-from-other-apps).

### Is there a server or an account? Is it safe?

No server and no account. The phones talk only over Bluetooth, end to end encrypted (P-256 key agreement, AES-GCM),
with keys exchanged by the pairing QR code. Your Apple ID is used only by your own sideloading tool, never by
Tether. See [SECURITY.md](https://github.com/amirwmr/tether/blob/main/SECURITY.md).

### Why does the Android app need Wireless debugging?

Bridging call audio and switching mobile data, hotspot, tethering or airplane mode need permissions only Android's
shell user has. Tether pairs with the phone's own Wireless debugging once and starts a small helper on the phone;
nothing leaves the phone. Texts, contacts and call control work without it.

### The other side cannot hear me. What do I do?

Check the iPhone's microphone permission for the app (Settings › Privacy & Security › Microphone; since 1.2 the call
screen and the Link tab say when it is refused or silent) and that the call is not muted. The Android phone keeps its
own microphone out of the call automatically and checks that your voice still gets through; if it does not, try
Call audio › "This phone's microphone during bridged calls" › **On**, and run the self-test during a call. Full list: [TROUBLESHOOTING.md](TROUBLESHOOTING.md#the-other-side-cannot-hear-me).

### How far apart can the phones be?

Bluetooth range: a few metres, through a bag or a pocket. In the next room it may drop.

### How do I send logs with a bug report?

Right after a test call: on the Android phone, Tether › Call audio › **Link log** › **Share** (and, during a call,
**Run call-audio self-test**, then a screenshot of its result); on the iPhone, Link › **Link log** › the share button
(Tether Client: Link › Link log › Copy). Attach them to an issue at
[github.com/amirwmr/tether/issues](https://github.com/amirwmr/tether/issues/new/choose). The logs hold no message text
or phone numbers; check them before posting.
