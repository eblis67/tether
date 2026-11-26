# Internet / VPN sharing — design note

Feature 2 of the iTell gap list. Tether already had the engine: the "Share VPN"
switch on the Link tab turns on `ShareProxy` on the Android phone — an
HTTP / HTTPS (CONNECT) / SOCKS5 proxy on port 8118, listening only on the
tethering interfaces (hotspot, Bluetooth PAN, USB), whose own traffic goes
through whatever VPN is up on the phone. It also already served a PAC file at
`GET /proxy.pac` ("PROXY …" while sharing, "DIRECT" while off) so an iPhone
set to *Configure Proxy › Automatic* on the hotspot follows the switch.
What was missing is the guided layer iTell wraps around the same idea. This
note maps each iTell behaviour to what existed, what this change adds, and
what is deliberately not done.

## iTell behaviour → Tether

| iTell | Before this change | Now |
|---|---|---|
| iPhone joins the Android hotspot | Hotspot switch on the Link tab; the iPhone joins it in iOS Settings like any Wi-Fi network | Unchanged (Tether does not own the hotspot's SSID/password — the helper flips the phone's own hotspot — so there is no Wi-Fi join QR; see "Not feasible" below) |
| Proxy profile offered by QR code | The PAC URL was in `net.state` and the iOS Link tab showed it as copyable text with manual steps | Each proxy address in `net.state` now also carries `qr`: the payload a QR code encodes (the PAC URL). The Android phone app shows the QR on its Share card while sharing is on (same ZXing renderer as the pairing QR), and the iOS Link tab renders the same payload as a QR for a second device. The iPhone's Camera opens the URL, which is pasted once into *Configure Proxy › Automatic* |
| Separate one-tap Telegram proxy setup | Nothing | Each address carries the SOCKS5 deep link too, and `net.state.proxy.telegram` names the preferred one. iOS shows "Set up Telegram" (opens the link) plus Copy; the Android client and the phone app show the link as selectable text |
| Split-tunnel bypass list (banks / local sites skip the proxy) | The PAC already sent plain hostnames and private ranges DIRECT; no user list | A user-editable host list, stored on the Android phone (`share_bypass` pref), edited from the iOS Link tab, echoed in `net.state.proxy.bypass`, and baked into the PAC the phone serves: listed hosts and their subdomains go DIRECT |
| USB-C wired option | The proxy already listened on USB tethering interfaces (`kind: "usb"` in `net.state`) and the iOS note mentioned proxy apps | Documented, not extended: with USB tethering on, the same proxy/PAC/Telegram setup applies at the `usb` address. There is nothing else to build — iOS has no per-network proxy setting for a USB link, so a proxy app is still required, exactly as over Bluetooth |
| Auto-off after ~10 min away | Airplane mode had a 90 s fail-safe of the same shape; Share VPN stayed on forever | If sharing is on and no client has been linked for 10 minutes, the Android phone turns Share VPN off by itself (same scheduler pattern as the airplane fail-safe). `net.state.proxy.offIn` reports the seconds left while the countdown is running |

## Protocol additions (PROTOCOL.md §5.3)

All inside the existing `net.*` messages; every new field is optional, so old
clients and old Android builds interoperate unchanged.

- `net.state.proxy.bypass`: `[host, …]` — the current bypass list.
- `net.state.proxy.offIn`: seconds until auto-off; present only while
  sharing is on and no client is linked.
- `net.state.proxy.telegram`: the preferred address's Telegram deep link.
- `net.state.proxy.addrs[].tg`: `tg://socks?server=<ip>&port=<port>`.
- `net.state.proxy.addrs[].qr`: the QR payload (the address's PAC URL).
- `net.set {what: "share", on, bypass?: [host, …]}`: when `bypass` is
  present it replaces the stored list (sanitized on the phone); when absent
  the list is untouched. Test (dry) clients can never change it — the guard
  that already gates `net.set` applies.

## Why Telegram gets `tg://socks`, not `tg://proxy`

iTell's Telegram code configures an MTProto proxy. MTProto needs a dedicated
MTProto server with a secret; Tether's proxy is HTTP/SOCKS5 and running an
MTProto server is out of scope for the offline model. Telegram also accepts
SOCKS5 proxies through the `tg://socks?server=…&port=…` deep link, and
SOCKS5 is exactly what `ShareProxy` speaks — so the link Tether generates is
the working equivalent, not a placeholder.

## Not feasible / deliberately not done

- **Installable `.mobileconfig` profile.** A profile that binds a proxy to a
  Wi-Fi network needs that network's SSID in a Wi-Fi payload. Tether does not
  set the hotspot's SSID or password (the helper switches the phone's own
  hotspot on and off), so it cannot write a correct Wi-Fi payload; the only
  SSID-free alternative, the Global HTTP Proxy payload, is restricted to
  supervised devices, and an unsigned profile would still warn on install.
  The PAC URL is the same configuration iOS consumes, so Tether ships that —
  as text, as a served file, and as a QR payload — with three manual steps.
- **Wi-Fi join QR (`WIFI:S:…`).** Same reason: the hotspot credentials are
  the phone's own and are not readable by a normal app on current Android.
- **Daily-updated bypass list.** iTell refreshes its bank/local list from a
  server. Tether is offline by design; the list is the user's own, edited in
  the app, and the PAC's built-in local/private-range rules still apply.

## Auto-off semantics

The countdown is about the *link*, mirroring the airplane fail-safe: any
client linked (BLE session up) holds sharing on; when the last client goes
away the 10 minutes start; any client linking cancels the countdown. When it
fires, the phone clears the `share_vpn` pref, stops proxying (the PAC starts
answering DIRECT, so a still-configured iPhone fails open, not dead), logs
the event, and refreshes its UI; the next `net.state` any client sees reports
`share: false`. The phone app's Share card and the iOS Link tab both show
the countdown while it runs, and turning Share VPN off (or any device
linking) cancels it.
