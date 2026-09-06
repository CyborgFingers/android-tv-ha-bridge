# Play Store submission kit — Android TV HA Bridge

Everything you need to paste into Play Console. Package `nz.mcnabb.atvhabridge`, v0.1.1
(`versionCode 1`). Upload `android/app/build/outputs/bundle/release/app-release.aab`.

Privacy policy URL: **https://cyborgfingers.github.io/android-tv-ha-bridge/privacy.html**

---

## 1. Store listing

- **App name:** Android TV HA Bridge
- **Category:** Tools · **Tags:** smart home, remote control
- **Contact email:** *(your email)* · **Website:** https://cyborgfingers.github.io/android-tv-ha-bridge/

**Short description** (≤80 chars):
> See what's on your TV in Home Assistant — and control it. 100% local, no ADB.

**Full description** (≤4000 chars):
> Android TV HA Bridge turns your Android TV or Google TV into a first-class Home
> Assistant device: it shows exactly what's playing and lets you control it — all over your
> own local network, with no cloud, no account, and no ADB.
>
> WHAT YOU GET IN HOME ASSISTANT
> • Now playing — title, series and episode, poster, position and duration, playback state
>   and the current app, pushed to Home Assistant the moment it changes.
> • Titles even for apps that hide them — it reads the Android TV "Watch Next" row to
>   recover show, episode and poster for apps that publish no media metadata.
> • Native control — play, pause, next, previous, seek and volume; D-pad, OK, Back and
>   Home; and launching apps.
> • Device sensors — current app, up next, battery, free storage and memory, volume,
>   network, IP, Wi-Fi and last boot.
> • A "TV frame" dashboard card that installs automatically.
>
> HOW IT WORKS
> The app runs on your TV, reads what's playing, and serves it over an encrypted local API.
> Home Assistant discovers the TV on your network, you pair it with a 6-digit code (or QR),
> and every box becomes a proper Home Assistant device. No developer options, no ADB keys,
> no root, no accounts.
>
> PRIVATE BY DESIGN
> 100% local. The app talks only to your Home Assistant on your LAN, over a paired token and
> TLS encryption with certificate pinning. It collects nothing and sends nothing to anyone.
>
> Open-source (GPL-3.0): https://github.com/CyborgFingers/android-tv-ha-bridge

---

## 2. Permission declarations (Play Console will ask for these)

### AccessibilityService — the highest-scrutiny item
Play Console → *Policy → App content → "Accessibility"* / the in-console prompt asks how you
use the API. Paste:

> The app includes an optional accessibility service that the user explicitly enables during
> setup. Its sole function is to perform on-screen **navigation actions the user triggers
> from Home Assistant** — D-pad up/down/left/right, OK, Back and Home — so a Home Assistant
> remote or automation can navigate the TV. Android exposes these global navigation actions
> only through the AccessibilityService API (`GLOBAL_ACTION_DPAD_*`, `GLOBAL_ACTION_BACK`,
> `GLOBAL_ACTION_HOME`); there is no other on-device API for them, and the app deliberately
> avoids ADB. The service **reads no screen content**, records nothing, and acts only in
> response to an explicit, authenticated command from the paired Home Assistant instance.
> It is disclosed on the app's setup screen before the user enables it, and the app is fully
> usable (now-playing + media controls) without it.

The service is declared with `canRetrieveWindowContent="false"` and does not observe
window content. **Risk note:** remote control is not a disability-assistance use, so Google
may still push back. If rejected, the fallback is to ship the accessibility feature as a
separate, clearly-labelled "remote control" capability or remove the D-pad actions and keep
media-session control only (which needs no accessibility service).

### Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`)
Play Console → the notification-access declaration. Paste:

> The app's core feature is reporting the TV's current media session (what's playing) to the
> user's own Home Assistant. Android exposes active media sessions to a bound
> NotificationListenerService via MediaSessionManager. The app reads media metadata and
> playback state only; it does not read, store, or transmit the content of notifications, and
> the data is sent only to the user's paired local Home Assistant, never to the developer.

### `QUERY_ALL_PACKAGES`
Play Console → *App content → "App access"/"Sensitive permissions"* declaration. Paste:

> The app lists installed launchable apps so the user's Home Assistant can offer to open them
> on the TV (a launcher/remote-control core feature). The list is provided only to the paired
> local Home Assistant instance and is never transmitted off-device.

*(If Google rejects `QUERY_ALL_PACKAGES`, it can be replaced with a `<queries>` element that
scopes visibility to leanback launcher activities — ask and I'll switch it.)*

### Prominent disclosure (show at first run — already covered by the setup screen)
> "Android TV HA Bridge reads the current media session and, if you enable it, uses an
> accessibility service to send navigation keys — only to the Home Assistant you pair with,
> on your local network. No data is sent to the developer or any third party."

---

## 3. Data safety form

- **Does your app collect or share any user data?** → **No.**
- **Is all data encrypted in transit?** → Yes (local TLS with certificate pinning).
- **Do you provide a way to request data deletion?** → Not applicable (no data collected).
- No data types selected in any category. No third-party sharing. No analytics/ads SDKs.

---

## 4. Content rating

Questionnaire → **Tools/Utility**, no user-generated content, no ads, no data collection →
result: **Everyone / PEGI 3**.

## 5. Target audience & ads
- Target age: 18+ (or 13+) — not designed for children.
- **Contains ads: No.**

---

## 6. Graphics (in `store/assets/`)

| Asset | Size | File |
| --- | --- | --- |
| App icon | 512×512 | `icon-512.png` |
| Feature graphic | 1024×500 | `feature-graphic.png` |
| TV banner | 1280×720 | `tv-banner.png` |
| Screenshot — pairing | 1280×720 | `screenshot-1-pairing.png` |
| Screenshot — now playing | 1280×720 | `screenshot-2-nowplaying.png` |
| Screenshot — control | 1280×720 | `screenshot-3-control.png` |

(TV apps require a TV banner and at least one 1280×720 screenshot; the app already ships the
in-app leanback banner + adaptive icon.)

---

## 7. Order of operations
1. Create app (Tools, Free) → package auto-set from the AAB on first upload.
2. **Internal testing** track first → upload the AAB → add yourself as a tester → install on
   the TV to confirm it runs, before promoting to Production.
3. Fill: store listing (copy above + graphics), Data safety (No), Content rating, Target
   audience, Privacy policy URL, and the permission declarations in §2.
4. Roll out to Production and submit for review.
