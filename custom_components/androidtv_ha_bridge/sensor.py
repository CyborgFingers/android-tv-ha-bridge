"""Device sensors for Android TV HA Bridge (battery, storage, network, …)."""
from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorEntityDescription,
    SensorStateClass,
)
from homeassistant.const import (
    PERCENTAGE,
    EntityCategory,
    SIGNAL_STRENGTH_DECIBELS_MILLIWATT,
    UnitOfInformation,
)
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from . import BridgeConfigEntry
from .entity import BridgeEntity


@dataclass(frozen=True, kw_only=True)
class BridgeSensorDescription(SensorEntityDescription):
    """Sensor description with a reader over the bridge state document."""

    value_fn: Callable[[dict], Any]


def _sensors(state: dict) -> dict:
    return state.get("sensors") or {}


SENSORS: tuple[BridgeSensorDescription, ...] = (
    BridgeSensorDescription(
        key="current_app", name="Current app",
        value_fn=lambda s: (s.get("app") or {}).get("name"),
    ),
    BridgeSensorDescription(
        key="up_next", name="Up next", icon="mdi:skip-next",
        value_fn=lambda s: (s.get("up_next") or {}).get("title"),
    ),
    BridgeSensorDescription(
        key="battery_level", name="Battery", device_class=SensorDeviceClass.BATTERY,
        native_unit_of_measurement=PERCENTAGE, state_class=SensorStateClass.MEASUREMENT,
        value_fn=lambda s: _sensors(s).get("battery_level"),
    ),
    BridgeSensorDescription(
        key="storage_free_gb", name="Storage free", icon="mdi:harddisk",
        native_unit_of_measurement=UnitOfInformation.GIGABYTES,
        device_class=SensorDeviceClass.DATA_SIZE, state_class=SensorStateClass.MEASUREMENT,
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda s: _sensors(s).get("storage_free_gb"),
    ),
    BridgeSensorDescription(
        key="memory_free_mb", name="Memory free", icon="mdi:memory",
        native_unit_of_measurement=UnitOfInformation.MEGABYTES,
        device_class=SensorDeviceClass.DATA_SIZE, state_class=SensorStateClass.MEASUREMENT,
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda s: _sensors(s).get("memory_free_mb"),
    ),
    BridgeSensorDescription(
        key="volume_level", name="Volume", icon="mdi:volume-high",
        native_unit_of_measurement=PERCENTAGE, state_class=SensorStateClass.MEASUREMENT,
        value_fn=lambda s: _sensors(s).get("volume_level"),
    ),
    BridgeSensorDescription(
        key="network_type", name="Network", icon="mdi:lan",
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda s: _sensors(s).get("network_type"),
    ),
    BridgeSensorDescription(
        key="ip", name="IP address", icon="mdi:ip-network",
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda s: _sensors(s).get("ip"),
    ),
    BridgeSensorDescription(
        key="wifi_ssid", name="Wi-Fi SSID", icon="mdi:wifi",
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda s: _sensors(s).get("wifi_ssid"),
    ),
    BridgeSensorDescription(
        key="wifi_rssi", name="Wi-Fi signal",
        device_class=SensorDeviceClass.SIGNAL_STRENGTH,
        native_unit_of_measurement=SIGNAL_STRENGTH_DECIBELS_MILLIWATT,
        state_class=SensorStateClass.MEASUREMENT, entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda s: _sensors(s).get("wifi_rssi"),
    ),
    BridgeSensorDescription(
        key="uptime", name="Last boot", device_class=SensorDeviceClass.TIMESTAMP,
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda s: dt_util.parse_datetime(_sensors(s).get("uptime") or ""),
    ),
)


async def async_setup_entry(
    hass: HomeAssistant, entry: BridgeConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    client = entry.runtime_data
    async_add_entities(BridgeSensor(client, desc) for desc in SENSORS)


class BridgeSensor(BridgeEntity, SensorEntity):
    """A single device sensor."""

    entity_description: BridgeSensorDescription

    def __init__(self, client, description: BridgeSensorDescription) -> None:
        super().__init__(client)
        self.entity_description = description
        self._attr_unique_id = f"{client.device_id}_{description.key}"

    @property
    def native_value(self) -> Any:
        return self.entity_description.value_fn(self._client.state)
