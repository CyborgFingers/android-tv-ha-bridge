"""Base entity for Android TV HA Bridge."""
from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity import Entity

from .client import BridgeClient
from .const import DOMAIN


class BridgeEntity(Entity):
    """Common device link + push-update wiring for all bridge entities."""

    _attr_should_poll = False
    _attr_has_entity_name = True

    def __init__(self, client: BridgeClient) -> None:
        self._client = client

    @property
    def device_info(self) -> DeviceInfo:
        sensors = self._client.state.get("sensors") or {}
        device = self._client.state.get("device") or {}
        return DeviceInfo(
            identifiers={(DOMAIN, self._client.device_id)},
            name=self._client.device_name,
            manufacturer=sensors.get("manufacturer") or "Android TV",
            model=sensors.get("model") or device.get("model"),
            sw_version=sensors.get("android_version"),
        )

    @property
    def available(self) -> bool:
        return self._client.available

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(self._client.add_listener(self.async_write_ha_state))
