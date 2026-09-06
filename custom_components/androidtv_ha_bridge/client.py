"""WebSocket client for a single Android TV HA Bridge device.

The bridge PUSHES its full state over a WebSocket (now-playing + sensors) and accepts
control commands over the same socket — a persistent connection so navigation has no
per-key handshake latency. Falls back to HTTP POST for commands if the socket is down.
"""
from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Callable

import aiohttp

from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .const import DOMAIN, EVENT_UPDATED

_LOGGER = logging.getLogger(__name__)

RECONNECT_SECONDS = 5


def ssl_param(fingerprint: str | None):
    """aiohttp `ssl=` value: pin the cert SHA-256 when known, else trust-on-first-use.

    The bridge is a LAN device with a self-signed cert (no CA), so we pin its
    fingerprint instead of validating a chain. `False` still uses TLS — it just skips
    validation — which is only used to learn the fingerprint at pairing time.
    """
    return aiohttp.Fingerprint(bytes.fromhex(fingerprint)) if fingerprint else False


async def async_get_info(
    session: aiohttp.ClientSession, host: str, port: int, fingerprint: str | None = None
) -> dict | None:
    """GET /api/info (open, no auth) — used by discovery + the config flow.

    The response body carries the bridge's own `fingerprint`, so a TOFU call
    (fingerprint=None) can learn what to pin thereafter.
    """
    try:
        async with session.get(
            f"https://{host}:{port}/api/info",
            ssl=ssl_param(fingerprint),
            timeout=aiohttp.ClientTimeout(total=5),
        ) as resp:
            if resp.status == 200:
                return await resp.json()
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError):
        return None
    return None


async def async_pair(
    session: aiohttp.ClientSession, host: str, port: int, code: str, fingerprint: str | None = None
) -> str | None:
    """POST the 6-digit code; return a long-lived token on success."""
    try:
        async with session.post(
            f"https://{host}:{port}/api/pair",
            json={"code": code},
            ssl=ssl_param(fingerprint),
            timeout=aiohttp.ClientTimeout(total=5),
        ) as resp:
            if resp.status == 200:
                return (await resp.json()).get("token")
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError):
        return None
    return None


class BridgeClient:
    """Maintains the live WebSocket to one bridge and sends it commands."""

    def __init__(
        self,
        hass: HomeAssistant,
        host: str,
        port: int,
        token: str,
        device_id: str,
        fingerprint: str | None = None,
    ) -> None:
        self._hass = hass
        self.host = host
        self.port = port
        self._token = token
        self.device_id = device_id
        self.device_name = ""
        self._ssl = ssl_param(fingerprint)
        self._session = async_get_clientsession(hass)
        self._ws: aiohttp.ClientWebSocketResponse | None = None
        self._task: asyncio.Task | None = None
        self._listeners: list[Callable[[], None]] = []
        self.state: dict = {}
        self.available = False

    @property
    def base_url(self) -> str:
        return f"https://{self.host}:{self.port}"

    def art_url(self, rel: str | None) -> str | None:
        if not rel:
            return None
        return rel if rel.startswith("http") else f"{self.base_url}{rel}"

    async def async_fetch_image(self, url: str) -> tuple[bytes | None, str | None]:
        """Fetch poster bytes over the pinned TLS connection (HA can't validate the
        self-signed cert on its own, so the entity proxies the image through here)."""
        try:
            async with self._session.get(
                url, ssl=self._ssl, timeout=aiohttp.ClientTimeout(total=10)
            ) as resp:
                if resp.status == 200:
                    return await resp.read(), resp.headers.get("Content-Type")
        except (aiohttp.ClientError, asyncio.TimeoutError):
            pass
        return None, None

    @callback
    def add_listener(self, cb: Callable[[], None]) -> Callable[[], None]:
        self._listeners.append(cb)
        return lambda: self._listeners.remove(cb)

    def _notify(self) -> None:
        for cb in list(self._listeners):
            cb()

    async def async_start(self) -> None:
        self._task = self._hass.async_create_background_task(self._run(), f"{DOMAIN}_ws_{self.device_id}")

    async def async_stop(self) -> None:
        if self._task:
            self._task.cancel()
        if self._ws and not self._ws.closed:
            await self._ws.close()

    async def _run(self) -> None:
        url = f"wss://{self.host}:{self.port}/ws?token={self._token}"
        while True:
            try:
                async with self._session.ws_connect(url, ssl=self._ssl, heartbeat=30) as ws:
                    self._ws = ws
                    self.available = True
                    self._notify()
                    _LOGGER.debug("Connected to bridge %s", self.device_id)
                    async for msg in ws:
                        if msg.type is aiohttp.WSMsgType.TEXT:
                            try:
                                self.state = json.loads(msg.data)
                            except ValueError:
                                continue
                            # Re-broadcast the full document on the HA event bus so a
                            # separate integration can map it without touching our entities.
                            self._hass.bus.async_fire(
                                EVENT_UPDATED,
                                {"device_id": self.device_id, "name": self.device_name, "data": self.state},
                            )
                            self._notify()
                        elif msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                            break
            except asyncio.CancelledError:
                raise
            except (aiohttp.ClientError, asyncio.TimeoutError) as err:
                _LOGGER.debug("Bridge %s WS error: %s", self.device_id, err)
            self._ws = None
            if self.available:
                self.available = False
                self._notify()
            await asyncio.sleep(RECONNECT_SECONDS)

    async def async_send(self, action: str, **extra) -> None:
        """Send a control command — over the open socket first, HTTP as fallback."""
        payload = {"action": action, **extra}
        if self._ws is not None and not self._ws.closed:
            try:
                await self._ws.send_str(json.dumps(payload))
                return
            except (aiohttp.ClientError, RuntimeError) as err:
                _LOGGER.debug("WS send failed (%s), using HTTP", err)
        try:
            await self._session.post(
                f"{self.base_url}/api/command?token={self._token}",
                json=payload,
                ssl=self._ssl,
                timeout=aiohttp.ClientTimeout(total=5),
            )
        except (aiohttp.ClientError, asyncio.TimeoutError) as err:
            _LOGGER.warning("Command %s failed: %s", action, err)
