/*! Android TV HA Bridge — "TV frame" Lovelace card.
 *  Shows what the TV is playing (poster, title, episode, live progress, up-next) and
 *  controls it (transport + D-pad). Dependency-free custom element; the integration
 *  auto-registers it, so `type: custom:androidtv-ha-bridge-card` works out of the box.
 *  Built entirely with DOM methods + textContent — never innerHTML — so nothing the
 *  bridge reports (titles, app names) can inject markup.
 *  © 2026 Josh McNabb. Licensed under GPL-3.0-or-later. */
const VERSION = "0.1.0";

const ICONS = {
  play: "M8 5v14l11-7z",
  pause: "M6 5h4v14H6zm8 0h4v14h-4z",
  prev: "M6 6h2v12H6zm3.5 6l8.5 6V6z",
  next: "M16 6h2v12h-2zM6 18l8.5-6L6 6z",
  up: "M12 8l6 6H6z", down: "M12 16l-6-6h12z",
  left: "M8 12l6-6v12z", right: "M16 12l-6 6V6z",
  back: "M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z",
  home: "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
  volUp: "M14 3.2v2.1c2.9.9 5 3.5 5 6.7s-2.1 5.8-5 6.7v2.1c4-1 7-4.6 7-8.8s-3-7.8-7-8.8zM16.5 12c0-1.8-1-3.3-2.5-4v8c1.5-.7 2.5-2.2 2.5-4zM3 9v6h4l5 5V4L7 9z",
  volDown: "M3 9v6h4l5 5V4L7 9zm13.5 3c0-1.8-1-3.3-2.5-4v8c1.5-.7 2.5-2.2 2.5-4z",
};

const SVGNS = "http://www.w3.org/2000/svg";

function el(tag, props, ...kids) {
  const n = document.createElement(tag);
  if (props) for (const [k, v] of Object.entries(props)) {
    if (k === "class") n.className = v;
    else if (k === "text") n.textContent = v;
    else n.setAttribute(k, v);
  }
  for (const c of kids) if (c != null) n.append(c);
  return n;
}

function icon(path) {
  const svg = document.createElementNS(SVGNS, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  const p = document.createElementNS(SVGNS, "path");
  p.setAttribute("d", path);
  svg.append(p);
  return svg;
}

const CSS = `
  [hidden] { display: none !important; }
  ha-card { overflow: hidden; position: relative; }
  .frame { position: relative; aspect-ratio: 16/9; background-color: #0b0b0f;
    background-size: cover; background-position: center; color: #fff;
    display: flex; flex-direction: column; justify-content: space-between; }
  .frame::after { content: ""; position: absolute; inset: 0; pointer-events: none;
    background: linear-gradient(180deg, rgba(0,0,0,.55) 0%, rgba(0,0,0,0) 30%,
      rgba(0,0,0,0) 55%, rgba(0,0,0,.85) 100%); }
  .top, .bottom { position: relative; z-index: 1; padding: 12px 14px; }
  .app { font-size: .8rem; opacity: .9; text-transform: uppercase; letter-spacing: .06em;
    display: flex; align-items: center; gap: 6px; }
  .live { color: #ff5252; font-weight: 700; }
  .title { font-size: 1.35rem; font-weight: 700; line-height: 1.15;
    text-shadow: 0 1px 4px rgba(0,0,0,.6); }
  .sub { font-size: .95rem; opacity: .9; margin-top: 2px; }
  .idle { margin: auto; text-align: center; opacity: .6; z-index: 1; position: relative; }
  .idle .big { font-size: 1.1rem; font-weight: 600; }
  .bar { position: relative; z-index: 1; height: 4px; border-radius: 2px;
    background: rgba(255,255,255,.25); margin: 10px 0 4px; overflow: hidden; }
  .bar > span { position: absolute; inset: 0 100% 0 0; background: var(--accent-color, #ff5252);
    border-radius: 2px; transition: right .5s linear; }
  .times { display: flex; justify-content: space-between; font-size: .72rem;
    opacity: .85; font-variant-numeric: tabular-nums; }
  .upnext { padding: 8px 14px; font-size: .82rem; color: var(--secondary-text-color);
    background: var(--card-background-color); display: flex; gap: 6px; align-items: center; }
  .upnext b { color: var(--primary-text-color); font-weight: 600; }
  .controls { display: grid; gap: 6px; padding: 10px 14px 14px;
    background: var(--card-background-color); }
  .row { display: flex; align-items: center; justify-content: center; gap: 8px; }
  .dpad { display: grid; grid-template-columns: repeat(3, 44px);
    grid-template-rows: repeat(3, 44px); justify-content: center; gap: 4px; }
  .sp { visibility: hidden; }
  button { cursor: pointer; border: none; border-radius: 10px; color: var(--primary-text-color);
    background: var(--secondary-background-color); height: 44px; min-width: 44px;
    display: inline-flex; align-items: center; justify-content: center; transition: transform .05s; }
  button:hover { background: var(--divider-color); }
  button:active { transform: scale(.94); }
  button.ok { background: var(--accent-color, #ff5252); color: #fff; border-radius: 50%; font-weight: 700; }
  button.pp { background: var(--accent-color, #ff5252); color: #fff; }
  svg { width: 22px; height: 22px; fill: currentColor; }
`;

class AndroidTVBridgeCard extends HTMLElement {
  setConfig(config) {
    if (!config.entity || !config.entity.startsWith("media_player."))
      throw new Error("Set `entity` to the bridge's media_player entity.");
    this.config = { controls: true, ...config };
    this._built = false;
  }

  set hass(hass) {
    this._hass = hass;
    if (!this._built) this._build();
    this._resolveSiblings();
    this._render();
  }

  getCardSize() { return this.config.controls ? 6 : 4; }

  /** Find the remote + up-next entities on the same device, so a user only has to
   *  set `entity`. Explicit config keys win; registry lookup is the fallback. */
  _resolveSiblings() {
    let remote = this.config.remote_entity;
    let upNext = this.config.up_next_entity;
    const reg = this._hass.entities || {};
    const deviceId = reg[this.config.entity]?.device_id;
    if (deviceId && (!remote || !upNext)) {
      for (const [eid, ent] of Object.entries(reg)) {
        if (ent.device_id !== deviceId) continue;
        if (!remote && eid.startsWith("remote.")) remote = eid;
        if (!upNext && eid.startsWith("sensor.") && eid.endsWith("_up_next")) upNext = eid;
      }
    }
    this._remote = remote;
    this._upNext = upNext;
  }

  _ctlButton(key, iconKey, cls) {
    const b = el("button", cls ? { class: cls } : null, icon(ICONS[iconKey]));
    b.addEventListener("click", () => this._press(key));
    return b;
  }

  _build() {
    const root = this.shadowRoot || this.attachShadow({ mode: "open" });
    root.append(el("style", { text: CSS }));

    this._live = el("span", { class: "live", text: "● LIVE" });
    this._appName = el("span");
    this._title = el("div", { class: "title" });
    this._sub = el("div", { class: "sub" });
    this._bar = el("span");
    this._barwrap = el("div", { class: "bar" }, this._bar);
    this._pos = el("span");
    this._dur = el("span");
    this._meta = el("div", { class: "bottom" },
      this._title, this._sub, this._barwrap,
      el("div", { class: "times" }, this._pos, this._dur));

    this._idleName = el("div", { class: "big" });
    this._idle = el("div", { class: "idle" }, this._idleName, el("div", { text: "Idle" }));

    this._frame = el("div", { class: "frame" },
      el("div", { class: "top" }, el("div", { class: "app" }, this._live, this._appName)),
      this._meta, this._idle);

    this._upLabel = el("span", { text: "Up next" });
    this._upVal = el("b");
    this._upnext = el("div", { class: "upnext" }, this._upLabel, el("span", { text: " " }), this._upVal);

    const ok = el("button", { class: "ok", text: "OK" });
    ok.addEventListener("click", () => this._press("ok"));
    this._dpad = el("div", { class: "dpad" },
      el("span", { class: "sp" }), this._ctlButton("up", "up"), el("span", { class: "sp" }),
      this._ctlButton("left", "left"), ok, this._ctlButton("right", "right"),
      this._ctlButton("back", "back"), this._ctlButton("down", "down"), this._ctlButton("home", "home"));

    this._controls = el("div", { class: "controls" },
      el("div", { class: "row" },
        this._ctlButton("prev", "prev"),
        this._ctlButton("playpause", "play", "pp"),
        this._ctlButton("next", "next"),
        el("span", { style: "width:12px" }),
        this._ctlButton("volDown", "volDown"),
        this._ctlButton("volUp", "volUp")),
      this._dpad);

    root.append(el("ha-card", null, this._frame, this._upnext, this._controls));
    this._built = true;

    if (!window.__atvhabridge_logged) {
      window.__atvhabridge_logged = true;
      console.info(`%c Android TV HA Bridge card %c v${VERSION} `,
        "background:#ff5252;color:#fff;border-radius:3px 0 0 3px", "background:#333;color:#fff;border-radius:0 3px 3px 0");
    }
  }

  _render() {
    const st = this._hass.states[this.config.entity];
    if (!st) return;
    const a = st.attributes;
    const playing = st.state === "playing";
    const active = playing || st.state === "paused";

    this._live.hidden = !playing;
    this._appName.textContent = a.app_name || this.config.name || "";
    this._meta.hidden = !active;
    this._idle.hidden = active;
    this._frame.style.backgroundImage = a.entity_picture ? `url("${encodeURI(a.entity_picture)}")` : "";

    if (active) {
      this._title.textContent = a.media_title || a.app_name || "Playing";
      this._sub.textContent = a.media_series_title || "";
      this._sub.hidden = !a.media_series_title;
      this._startProgress(a, playing);
    } else {
      this._idleName.textContent = this.config.name || a.friendly_name || "TV";
      this._stopProgress();
    }

    const un = this._upNext && this._hass.states[this._upNext];
    const unVal = un && !["unknown", "unavailable", ""].includes(un.state) ? un.state : null;
    this._upnext.hidden = !unVal;
    if (unVal) this._upVal.textContent = unVal;

    this._controls.hidden = !this.config.controls;
    this._dpad.style.display = this._remote ? "" : "none";
  }

  _startProgress(a, playing) {
    this._stopProgress();
    const dur = Number(a.media_duration) || 0;
    this._barwrap.hidden = !dur;
    this._pos.hidden = this._dur.hidden = !dur;
    if (!dur) return;
    const base = Number(a.media_position) || 0;
    const t0 = a.media_position_updated_at ? new Date(a.media_position_updated_at).getTime() : Date.now();
    const tick = () => {
      const elapsed = playing ? (Date.now() - t0) / 1000 : 0;
      const pos = Math.min(dur, base + elapsed);
      this._bar.style.right = `${100 - (pos / dur) * 100}%`;
      this._pos.textContent = this._fmt(pos);
      this._dur.textContent = this._fmt(dur);
    };
    tick();
    if (playing) this._timer = setInterval(tick, 1000);
  }

  _stopProgress() { if (this._timer) { clearInterval(this._timer); this._timer = null; } }

  disconnectedCallback() { this._stopProgress(); }

  _press(k) {
    const mp = { entity_id: this.config.entity };
    if (k === "playpause") return this._call("media_player", "media_play_pause", mp);
    if (k === "prev") return this._call("media_player", "media_previous_track", mp);
    if (k === "next") return this._call("media_player", "media_next_track", mp);
    if (k === "volUp") return this._call("media_player", "volume_up", mp);
    if (k === "volDown") return this._call("media_player", "volume_down", mp);
    if (this._remote) this._call("remote", "send_command", { entity_id: this._remote, command: k });
  }

  _call(domain, service, data) {
    this._hass.callService(domain, service, data);
    if (navigator.vibrate) navigator.vibrate(8);
  }

  _fmt(s) {
    s = Math.max(0, Math.floor(s));
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    const p = (n) => String(n).padStart(2, "0");
    return h ? `${h}:${p(m)}:${p(sec)}` : `${m}:${p(sec)}`;
  }

  static getStubConfig(hass, entities) {
    const mp = (entities || []).find((e) => e.startsWith("media_player."));
    return { entity: mp || "media_player.android_tv", controls: true };
  }
}

customElements.define("androidtv-ha-bridge-card", AndroidTVBridgeCard);
window.customCards = window.customCards || [];
window.customCards.push({
  type: "androidtv-ha-bridge-card",
  name: "Android TV HA Bridge",
  description: "TV frame: now-playing poster, live progress, up-next and controls.",
  preview: true,
  documentation: "https://github.com/CyborgFingers/android-tv-ha-bridge",
});
