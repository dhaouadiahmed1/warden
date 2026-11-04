# Warden — v0.1 starter

A free, open-source Android firewall starter, inspired by the Warden app
shown in the screenshot you sent (github.com/sammyZi/warden, v0.2.0,
Kotlin + Jetpack Compose, Material 3, minSdk 26, GPL-3.0, no ads, no
trackers, no root needed).

**This is an original starter built for you, not a copy of that author's
source code.** Do not publish it as the original Warden. If you publish
your version, credit the idea, keep it open source, and use your own
name, icon and package.

## What is inside

- Kotlin + Jetpack Compose + Material 3, dark theme, bottom navigation:
  Home, Apps, Map, Log, Setting — matching the screenshots.
- **Home:** Protection on/off, blocked / connections / apps / domains
  counters, DNS shown (Cloudflare by default).
- **Apps:** every app that can go online, search, filters
  (All / With rules / System), one switch per app to block or allow it.
  Rules are saved on the phone in SharedPreferences.
- **Map:** "Where it went" — destinations seen in the log. The world
  map with per-country traffic is a v0.2 screen.
- **Log:** activity log with app, destination IP, protocol, time,
  Seen / Blocked.
- **Setting:** DNS provider (Cloudflare 1.1.1.1 / Google 8.8.8.8 /
  Quad9 9.9.9.9), block-trackers switch, privacy note.
- **No-root engine:** `service/WardenVpnService.kt` — Android's
  VpnService, the only way to control other apps' network access
  without root. The tunnel starts and ends on your phone; Warden runs
  no VPN server and sends no traffic anywhere else.
  - `service/PacketParser.kt` — minimal IPv4 parsing.
  - `service/UidLookup.kt` — maps a source port to the owning app UID
    via `/proc/net/tcp`, `/proc/net/udp`, etc., the technique other
    no-root firewalls use.
  - Blocked packets are dropped. Everything stays on the phone:
    no account, no analytics, no ads.

## How a no-root firewall works (short version)

Android will not let a normal app cut another app's internet directly.
So the firewall asks you once for VPN permission, creates a local TUN
interface, and all traffic passes through that interface *on the
device*. The firewall checks each packet's app against your rules,
drops the blocked ones, and forwards the rest. That is why the phone
shows a VPN key icon while protection is on, even though no traffic
leaves the phone to a VPN server.

## ⚠️ Read this before daily use — v0.1 limit

**Packet forwarding is NOT finished in v0.1.** When Protection is ON,
the service establishes the TUN, parses packets, looks up the app and
logs Seen / Blocked — but allowed packets are not yet forwarded back
to the internet. So apps will appear offline while Protection is ON.
Turn it OFF to restore internet immediately.

That is deliberate and labelled in the app itself (a warning dialog
appears before you turn it on). v0.1 is a working starter for the UI,
rules, log and VPN plumbing — not yet a daily-driver firewall.
**Test it on a spare device first, and do not rely on it as your
only protection yet.**

## Build it — from a phone, no PC

See `GITHUB_BUILD.md`. In short: unzip this project, upload its
contents to a GitHub repo (keeping `android/` and `.github/` at the
root), and GitHub Actions builds the debug APK for you. Download it
from the run's Artifacts as `warden-debug-apk`.

## Build it — on a PC

1. Open `android/` in Android Studio (not the outer folder).
2. Let Gradle sync. Toolchain matches the TunisGo starter:
   AGP 8.5.2, Kotlin 2.0.0, compileSdk 34, minSdk 26, Compose BOM 2024.06.00.
3. Run on a phone or emulator, or in Android Studio use
   Build > Build App Bundle(s) / APK(s) > Build APK(s).
   The APK lands in `app/build/outputs/apk/debug/`.
4. On first Protection ON, Android asks you to allow the VPN
   connection. Tap OK yourself — Warden never sees a password.

## Test it

1. Open Apps, block one app you can test with (for example a browser
   you do not need for the test).
2. Turn Protection ON, accept the warning, allow the VPN.
3. Open that app — its attempts should appear in Log as Blocked
   (or Seen, if the UID lookup misses a short-lived socket).
4. Turn Protection OFF and confirm internet works again normally.

## Roadmap

- **v0.2 — the forwarder:** TCP/UDP proxy that forwards allowed
  packets and returns replies through the TUN, so Protection ON no
  longer breaks the internet. This is the step that turns v0.1 into a
  usable firewall. Also: IPv6 parsing, DNS filtering for tracker
  domains, real world map, data-use counters.
- **v0.3:** per-app Wi-Fi vs mobile rules, allow/block by domain,
  export/import rules, lockdown mode (block everything when the
  VPN drops).

## Privacy

No account. No server. No analytics. Rules and logs never leave
the phone. The only permission that looks broad is
`QUERY_ALL_PACKAGES`, needed to list installed apps; if you publish
on Google Play you will need to declare it.

## Files

```
android/
  settings.gradle.kts, build.gradle.kts, gradle.properties
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/java/app/warden/
    MainActivity.kt
    data/Models.kt, data/WardenStore.kt
    service/WardenVpnService.kt, service/PacketParser.kt, service/UidLookup.kt
    ui/WardenViewModel.kt, ui/theme/Theme.kt, ui/screens/Screens.kt
  app/src/main/res/ (strings, theme, shield icon, launcher icons)
```
