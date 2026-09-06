# Android TV HA Bridge — brand

> **See what's on. Take the remote.**

One line, both halves of the product: *now-playing* (what's on) and *native control* (the remote).
Use it under the wordmark, in the README hero, on the landing page and in store listings.

| Use | Copy |
| --- | --- |
| Tagline | **See what's on. Take the remote.** |
| One-liner | Now-playing and native control for Android TV / Google TV, inside Home Assistant — 100 % local, no ADB, no cloud. |
| Short | Your TV, in Home Assistant. |
| Proof chips | `100% local` · `No ADB` · `Native control` · `Works with HACS` |

Made by **Josh McNabb** ([@CyborgFingers](https://github.com/CyborgFingers)). Licensed [GPL-3.0](../LICENSE).

---

## The mark: *a screen on a bridge*

A TV **screen** (rounded frame) with a **coral play glyph** — what's on — standing on a
**signal-blue arch**. The arch is the bridge: its two **feet** are the endpoints (the TV and
Home Assistant) and its **keystone**, the coral node docked into the bezel, is the port the TV
talks through. Flat, two accent colours, readable at 16 px.

```
 ┌──────────┐
 │    ▶     │   frame  = foreground (white on dark · ink on light)
 └────●─────┘   screen = background · play + keystone = Coral 400
   ╱      ╲     arch + feet = Signal (400 on dark · 600 on light)
  ●        ●
```

The same path data drives every asset (SVG, Android vector, hero), so the geometry never drifts.

### Files

| File | What | Use it for |
| --- | --- | --- |
| `icon.svg` | Mark on an ink rounded square, 128 × 128 | App icon, favicon, avatars, social — anywhere a filled tile is wanted |
| `logo.svg` | Horizontal lockup, ink text, for **light** backgrounds | Docs, light UI |
| `logo-dark.svg` | Horizontal lockup, white text, for **dark** backgrounds | GitHub dark, dark UI |
| `hero.svg` | 1280 × 360 README banner (own dark background, rounded corners) | Top of `README.md` — works on both GitHub themes |
| `ha-brands/icon.svg` · `dark_icon.svg` | Mark alone on **transparent**, 256 × 256 | Masters for the `home-assistant/brands` PR |
| `ha-brands/logo.svg` · `dark_logo.svg` | Lockup on transparent | Masters for the `home-assistant/brands` PR |
| `android/app/src/main/res/drawable/ic_launcher_foreground.xml` | Adaptive-icon foreground (108 dp, mark inside the 66 dp safe zone) | Launcher icon |
| `android/app/src/main/res/values/ic_launcher_background.xml` | Adaptive-icon background colour (Ink 950) | Launcher icon |
| `android/app/src/main/res/drawable/ic_banner.xml` | 320 × 180 dp leanback banner (mark + outlined wordmark) | Android TV home screen |

The wordmark inside the SVGs and the banner is **outlined** (Inter Display ExtraBold + Inter
SemiBold converted to paths), so nothing depends on fonts being installed — GitHub, HA and
Android all render it identically.

### Rules

- **Clear space:** at least ⅛ of the mark's height on every side. Nothing else inside it.
- **Minimum size:** mark 16 px; lockup 24 px tall. Below that, use the mark alone.
- **Backgrounds:** dark → white frame (`icon.svg`, `logo-dark.svg`, `dark_*`); light → ink frame (`logo.svg`, `ha-brands/icon.svg`). Never put the ink-frame version on dark or vice-versa.
- **Colour:** only the four roles above. Don't recolour the play glyph, don't make the arch coral, don't tint the whole mark.
- **Don't** rotate, skew, outline, add shadows/glows, or place the mark inside another shape.
- **Don't** use the Android robot, the Google TV / Android TV logos, or the Home Assistant logo *inside* the mark. "Android TV" in the name is descriptive; the official *Made for Home Assistant* and *my.home-assistant.io* badges are the sanctioned way to show the HA relationship.

---

## Colour

Two accents on a near-black "ink". Coral is the product's signature (the card's `● LIVE` and
accent are coral); Signal blue is reserved for the bridge/connection idea and links.

### Tokens

| Token | Hex | Role |
| --- | --- | --- |
| **Ink 950** | `#0B0F14` | Dark background · mark frame on light · icon background |
| Ink 900 | `#141A22` | Raised dark surface (cards, code blocks on dark) |
| Ink 800 | `#1C2430` | Dark borders, hover fills |
| Ink 700 | `#2A3441` | Dark outlines, dividers |
| Ink 500 | `#5B6675` | Muted text on light (5.8 : 1 on white) |
| Ink 300 | `#AAB3BF` | Muted text on dark (9.1 : 1 on Ink 950) |
| Ink 100 | `#E4E7EC` | Light borders, dividers |
| Paper | `#F7F7F9` | Light background |
| White | `#FFFFFF` | Light surface · mark frame on dark · text on dark |
| **Coral 400** | `#FF5252` | The accent: play glyph, keystone, LIVE, primary buttons. 6.0 : 1 on Ink 950 |
| Coral 600 | `#D63037` | Coral **text/links on light** (4.8 : 1 on white). Don't use `#FF5252` for text on light |
| Coral 700 | `#B8242B` | Hover / pressed on light (6.3 : 1) |
| **Signal 400** | `#4CC2FF` | The bridge on dark; links on dark (9.6 : 1 on Ink 950) |
| Signal 600 | `#177FBF` | The bridge on light; large text (4.4 : 1 on white) |
| Signal 700 | `#0E6FAB` | Signal body text / links on light (5.4 : 1) |

### Themes

| Slot | Light | Dark |
| --- | --- | --- |
| Background | Paper `#F7F7F9` | Ink 950 `#0B0F14` |
| Surface | White `#FFFFFF` | Ink 900 `#141A22` |
| Border | Ink 100 `#E4E7EC` | Ink 800 `#1C2430` |
| Text | Ink 950 `#0B0F14` | White `#FFFFFF` |
| Muted text | Ink 500 `#5B6675` | Ink 300 `#AAB3BF` |
| Accent | Coral 400 `#FF5252` (buttons) · Coral 600 `#D63037` (text) | Coral 400 `#FF5252` |
| Link / bridge | Signal 700 `#0E6FAB` · Signal 600 `#177FBF` (graphics) | Signal 400 `#4CC2FF` |

All text pairings above meet WCAG AA (≥ 4.5 : 1). Coral 400 on white is 3.2 : 1 — fine for
the mark and other graphics (≥ 3 : 1), **not** for text.

```css
:root {
  --ink-950:#0B0F14; --ink-900:#141A22; --ink-800:#1C2430; --ink-700:#2A3441;
  --ink-500:#5B6675; --ink-300:#AAB3BF; --ink-100:#E4E7EC; --paper:#F7F7F9;
  --coral-400:#FF5252; --coral-600:#D63037; --coral-700:#B8242B;
  --signal-400:#4CC2FF; --signal-600:#177FBF; --signal-700:#0E6FAB;
}
```

---

## Type

| Role | Face | Weight | Notes |
| --- | --- | --- | --- |
| Display / wordmark | **Inter Display** | ExtraBold 800 | Headlines ≥ 32 px; tracking −1 % to −2 % |
| Headings | Inter Display | Bold 700 | H2/H3 |
| Text | **Inter** | Regular 400 · Medium 500 · SemiBold 600 | Body 16 px / 1.6 |
| Label ("ANDROID TV" line) | Inter | SemiBold 600 | Uppercase, tracking +16 % |
| Code / entity ids | System mono | — | `ui-monospace, SFMono-Regular, Menlo, Consolas, monospace` |

Inter and Inter Display are by Rasmus Andersson, SIL Open Font License 1.1. On the web, load
Inter from Google Fonts (`display=swap`) and always give the fallback stack — the site must
read fine on system fonts:

```css
font-family: Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto,
             "Helvetica Neue", Arial, sans-serif;
```

In SVG assets the text is already outlined; never add `<text>` elements to them.

---

## Home Assistant brands PR

`ha-brands/` holds the **masters** for a pull request to
[home-assistant/brands](https://github.com/home-assistant/brands) under
`custom_integrations/androidtv_ha_bridge/` (the integration domain). That repo takes **PNG**, not
SVG — export from these files (e.g. `rsvg-convert -w 256 -h 256 icon.svg > icon.png`) following
the size rules in the brands README at the time of the PR (square `icon.png` 256 px +
`icon@2x.png` 512 px; `logo.png` / `logo@2x.png`; transparent backgrounds; `dark_icon.png` /
`dark_logo.png` for the dark-theme variants). Keep the geometry as-is; only rasterise.

## Android icon

Adaptive icon: foreground = the mark (white frame, ink screen) scaled so its whole footprint sits
inside the 66 dp safe circle; background = Ink 950. Both `ic_launcher.xml` and
`ic_launcher_round.xml` in `mipmap-anydpi-v26/` point at them (`minSdk 26`, so no legacy
bitmaps are needed). The TV home screen uses `ic_banner.xml` (320 × 180 dp) instead of the icon.
