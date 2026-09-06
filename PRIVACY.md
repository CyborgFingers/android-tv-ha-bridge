# Privacy Policy — Android TV HA Bridge

**Effective date:** 6 September 2026
**Developer:** Josh McNabb ([@CyborgFingers](https://github.com/CyborgFingers))
**App:** Android TV HA Bridge (`nz.mcnabb.atvhabridge`)

## The short version

**Android TV HA Bridge does not collect, store, transmit, or share any personal data.**
It has no accounts, no analytics, no advertising, and no cloud service. Everything the app
does happens **on your own local network**, between your TV and your own Home Assistant
installation.

## What the app does

The app runs on your Android TV / Google TV and exposes, over your **local network only**,
what the TV is currently playing plus the ability to control it. Your Home Assistant server
connects to it after you approve a one-time pairing. The developer operates no servers and
receives no data of any kind.

## Data we collect

**None.** The app does not collect or send any data to the developer or to any third party.
It contains no analytics SDKs, no crash-reporting SDKs, no advertising SDKs, and no trackers.

## Information the app handles locally (and never sends to us)

To do its job, the app reads the following **on the device** and makes it available **only**
to the Home Assistant instance you pair with, over your local network:

- **Current media session** (title, artist, artwork, position, playback state) — via
  notification access, so it can report what's playing.
- **The Android TV "Watch Next" list** (`READ_TV_LISTINGS`) — to recover show/episode names
  for apps that publish no media metadata.
- **The list of installed apps** (`QUERY_ALL_PACKAGES`) — so Home Assistant can offer to
  launch them.
- **Device status** (model, network, storage, memory, volume, Wi-Fi signal, uptime) — as
  optional sensors.
- **Navigation actions** (D-pad, Back, Home) performed **only** when you trigger them from
  Home Assistant — via the accessibility service.

None of this information leaves your local network, and none of it is ever transmitted to
the developer. Communication between the TV and Home Assistant is protected by a per-device
**paired token** and **TLS encryption** with certificate-fingerprint pinning.

## Data sharing

We do not share any data, because we do not collect any.

## Permissions summary

| Permission | Why | Leaves your network? |
| --- | --- | --- |
| Notification access | Read the active media session (what's playing) | No |
| Accessibility service | Send D-pad/Back/Home **when you trigger them** from Home Assistant | No |
| Read TV listings | Recover show/episode titles from the Watch Next row | No |
| Query all packages | Let Home Assistant launch installed apps | No |
| Network / Wi-Fi state | Serve the local API and report connection info | No |

## Children's privacy

The app is not directed at children and collects no data from anyone, including children.

## Changes

Any changes to this policy will be published at this page. Continued use after a change
constitutes acceptance of the updated policy.

## Contact

Questions? Open an issue: <https://github.com/CyborgFingers/android-tv-ha-bridge/issues>
