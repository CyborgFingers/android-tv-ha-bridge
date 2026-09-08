# Data & Protocol Reference

Android TV HA Bridge exposes its data **generically** so you can build your own
dashboards, automations, or a **separate integration** on top of it — nothing is locked
to the bundled card. There are two layers:

1. **Home Assistant** — entities, attributes and an event bus event (easiest for HA users).
2. **The raw local API** — HTTPS + WebSocket on the TV itself (for non-HA clients, or to
   talk to a box directly).

---

## 1. Home Assistant surface

Each paired TV becomes one HA **device** with these entities (names are prefixed with the
name you gave the TV, e.g. `Bedroom TV`):

| Entity | What it carries |
| --- | --- |
| `media_player.<tv>` | State (`playing`/`paused`/`idle`), `media_title`, `media_series_title`, `app_name`, `app_id`, `media_position`, `media_duration`, `media_position_updated_at`, `entity_picture` (poster, proxied over TLS), `volume_level`, `is_volume_muted` — plus the extra attributes below. |
| `remote.<tv>` | `send_command` with a bridge action (see command list); `turn_on`/`turn_off`; `turn_on` with `activity: <package>` launches an app. |
| `sensor.<tv>_current_app`, `_up_next`, `_battery`, `_storage_free`, `_memory_free`, `_volume`, `_network`, `_ip`, `_wifi_ssid`, `_wifi_signal`, `_last_boot` | One sensor per device metric. |

**Extra `media_player` attributes** (no standard field exists for these):
`episode_title`, `is_ad`, `ad_skippable`, `ad_skip_in`, `up_next_title`, `up_next_series_title`,
and `up_next_list` — the up-next picks (the current app's Watch-Next tiles, else the launcher's
cross-app *Continue watching* row; published while browsing too): a list of
`{ title, episode_title, season, episode, poster }`, where `poster` is an HA-relative
`/api/media_player_proxy/<entity>/browse_media/up_next/<i>?token=…` URL served exactly like
`entity_picture` (proxied over the pinned TLS connection). Picks are ordered *next episode*
tiles first, then most-recently-engaged, and never include what's playing (or paused) right now.

**Play a pick:** `media_player.play_media` with `media_content_type: up_next` and
`media_content_id: "<i>"` (the pick's index in `up_next_list`) plays it on the TV through the
tile's own launch intent — the same thing the launcher does when you pick the tile.

```yaml
action: media_player.play_media
target: { entity_id: media_player.bedroom_tv }
data: { media_content_type: up_next, media_content_id: "0" }
```

Read any of it from a template, card or automation, e.g.:

```jinja
{{ state_attr('media_player.bedroom_tv', 'media_series_title') }}
{{ state_attr('media_player.bedroom_tv', 'up_next_title') }}
{{ states('sensor.bedroom_tv_up_next') }}
```

### The event (push, for a separate integration)

On **every** state push the integration fires an event on the HA bus:

- **Event type:** `androidtv_ha_bridge_updated`
- **Data:** `{ "device_id": "<stable id>", "name": "<friendly name>", "data": <full state document> }`

`data` is the complete document described in [§2](#the-state-document). This is the
intended hook for **mapping the data into another integration** (e.g. a remote): subscribe
to the event, key off `device_id`, and read `data` — no dependency on our entities.

```python
# In another custom integration
@callback
def _on_update(event):
    if event.data["device_id"] == MY_TV_ID:
        now_playing = event.data["data"].get("now_playing")
        ...

hass.bus.async_listen("androidtv_ha_bridge_updated", _on_update)
```

---

## 2. The raw local API

One port per TV (default `8099`, falling back to `8100`+ if busy), serving **HTTPS and
WebSocket over the same port**. The cert is self-signed; pin its SHA-256 fingerprint
(advertised three ways — see [Security](#security)). Discover a TV via mDNS
`_atvhabridge._tcp.` (TXT: `id`, `name`, `ver`, `fp`, `secure`).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/info` | open | `{ id, name, model, api_version, paired, secure, fingerprint }` |
| POST | `/api/pair` | open | body `{ "code": "123456" }` → `{ "token": "…", "id": "…" }` |
| GET | `/api/state` | token | the current [state document](#the-state-document) |
| POST | `/api/command` | token | body `{ "action": "...", ... }` → `{ "ok": true }` |
| GET | `/api/apps` | token | `[ { package, name }, … ]` launchable apps |
| GET | `/art.jpg` | open | current poster JPEG |
| GET | `/art_next_<i>.jpg` | open | poster of `up_next_list[i]` (`/art_next.jpg` = index 0) |
| WS | `/ws?token=…` | token | live state pushes **and** commands (see below) |

**Auth:** pass the paired token as `?token=…` or `Authorization: Bearer …`. Get a token
by redeeming the 6-digit code shown in the TV app (`POST /api/pair`).

### WebSocket

Connect to `wss://<host>:<port>/ws?token=…`. The server:
- **pushes** the full state document (as a JSON text frame) on connect and on every change;
- **accepts commands** on the same socket — send `{"action":"dpad_up"}` etc. Because the
  socket is already open and authenticated, navigation has no per-key handshake latency.

### Commands (`action`)

`play` · `pause` · `playpause` · `stop` · `next` · `previous` · `seek` (`{"position": <seconds>}`)
· `dpad_up` · `dpad_down` · `dpad_left` · `dpad_right` · `ok` · `back` · `home`
· `volume_up` · `volume_down` · `volume_mute` · `volume_unmute` · `volume_set` (`{"level": 0-100}`)
· `sleep` · `wake` · `launch` (`{"package": "com.example"}`)
· `play_next` (`{"index": <i>}` — plays `up_next_list[i]` by firing that tile's own launch intent,
as read from the TV provider; the API takes an index, never an intent).

### The state document

```jsonc
{
  "api_version": 1,
  "device":   { "id": "…", "name": "Bedroom TV", "model": "…" },
  "state":    "playing",            // playing | paused | idle
  "app":      { "package": "com.example", "name": "TVNZ+" },
  "now_playing": {
    "title": "MasterChef Australia",
    "series_title": "MasterChef Australia",
    "episode_title": "Season 16 · Episode 42",
    "artist": null,
    "duration": 3600,               // seconds (omitted if unknown)
    "position": 1234,               // seconds
    "position_updated_at": "2026-09-06T01:23:45+00:00",  // project position from here
    "art": "/art.jpg?v=7",          // relative → prefix with the base URL
    "is_ad": false,
    "ad_skippable": false,          // present only while is_ad
    "ad_skip_in": 0                 // present only while is_ad
  },
  "up_next":  { "title": "…", "series_title": "…", "art": "/art_next.jpg?v=3" },
  "up_next_list": [                 // up to 6 picks: next episodes first, then most-recently-engaged; never what's playing
    { "title": "24", "episode_title": "Day 6: 1:00 A.M.-2:00 A.M.", "season": "6", "episode": "20",
      "art": "/art_next_0.jpg?v=1a2b3c", "duration": 2520, "position": 1653 }
  ],
  "sensors":  { "model": "…", "manufacturer": "…", "android_version": "…", "sdk": 34,
                "uptime": "…", "screen_on": true, "battery_level": 100, "battery_state": "…",
                "storage_free_gb": 12.3, "memory_free_mb": 512, "volume_level": 40,
                "volume_muted": false, "ip": "…", "network_type": "…",
                "wifi_ssid": "…", "wifi_rssi": -55 }
}
```

**Progress that lines up:** `position` is measured at `position_updated_at`. To render a
live progress bar, project it: `now = position + (wall_clock_now − position_updated_at)`
while `state == "playing"`.

Fields are absent (not null) when unavailable, except where noted. `title`/`series`/
`episode`/`art` for apps that publish no media session (e.g. TVNZ+, ThreeNow) are recovered
from the Android TV **Watch-Next** provider — the tile the player's own on-screen labels name
(an app rewrites its row only when playback stops), else the most recently engaged one.

For such apps `state` comes from the device rather than the session: audio playing →
`playing`; no audio but the player's on-screen scrubber still present (read via the
accessibility overlay scrape) → `paused`, with `now_playing` kept and `position` frozen;
neither (the app's menus are up) → `idle`, with `now_playing` cleared to just the app.

`up_next_list` is the current app's Watch-Next tiles (its *Next episode / Continue watching*
row), falling back to the launcher's cross-app *Continue watching* row when the app has none.
Tiles the app flags as the **next episode** (`watch_next_type` NEXT) come first, then the rest
by recency; the item that's playing or paused is left out (matched on show + season/episode,
or title). It is published whether playing or idle — browsing is exactly when it's useful —
and re-published the moment an app rewrites its row (the bridge watches the provider). A pick's
`art` is present only once its poster resolved; `duration`/`position` (seconds) when known.
Play one with the `play_next` command (index into this list).

---

## Security

Every connection is **TLS 1.3, encrypted and MITM-proof** via cert-fingerprint pinning
(no CA). The SHA-256 fingerprint (lowercase hex) is advertised in `/api/info`
(`fingerprint`), the mDNS TXT record (`fp`), and the pairing QR (`&fp=`). Pin it:
- **aiohttp / HA:** `ssl=aiohttp.Fingerprint(bytes.fromhex(fp))`
- **OkHttp / Android:** a custom `X509TrustManager` that accepts the leaf iff its DER
  SHA-256 equals `fp` (OkHttp's `CertificatePinner` pins the SPKI, not the cert — don't use it).
