"""Live screen-mirror camera entity for Android TV HA Bridge.

Proxies the bridge's own multipart/x-mixed-replace MJPEG stream (BridgeServer.kt's
/screen.mjpeg) straight through over the pinned-TLS connection, byte-for-byte — no
re-encoding on this side. That's what makes it reachable via HA's own standard
/api/camera_proxy_stream/<entity_id>, which the dashboard's generic camera card and
any other client of HA's camera proxy already know how to consume with no changes on
their end. Still images (snapshots, dashboard thumbnails) come from the
bridge's /screen.jpg.

Empty (not erroring) until the bridge holds a MediaProjection grant — requested by the TV
app whenever a viewer asks when appop PROJECT_MEDIA is allowed, else via its 4th
onboarding step.
"""
from __future__ import annotations

import logging

import aiohttp
from aiohttp import web

from homeassistant.components.camera import Camera
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .client import BridgeClient
from .entity import BridgeEntity

_LOGGER = logging.getLogger(__name__)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    async_add_entities([BridgeScreenCamera(entry.runtime_data)])


class BridgeScreenCamera(BridgeEntity, Camera):
    """Live TV screen mirror, ~2.5fps — see what's actually on screen, not just
    now-playing metadata."""

    def __init__(self, client: BridgeClient) -> None:
        BridgeEntity.__init__(self, client)
        Camera.__init__(self)
        self._attr_unique_id = f"{client.device_id}_screen"
        self._attr_name = "Screen"
        self._attr_is_streaming = True

    async def async_camera_image(
        self, width: int | None = None, height: int | None = None
    ) -> bytes | None:
        return await self._client.async_screen_frame()

    async def handle_async_mjpeg_stream(self, request: web.Request) -> web.StreamResponse | None:
        upstream = await self._client.async_open_screen_stream()
        if upstream is None:
            return None

        response = web.StreamResponse(
            status=200,
            headers={
                "Content-Type": upstream.headers.get(
                    "Content-Type", "multipart/x-mixed-replace; boundary=atvhabridgeframe"
                )
            },
        )
        await response.prepare(request)
        try:
            async for chunk in upstream.content.iter_chunked(65536):
                await response.write(chunk)
        except (aiohttp.ClientError, ConnectionResetError):
            pass
        finally:
            await upstream.release()
        return response
