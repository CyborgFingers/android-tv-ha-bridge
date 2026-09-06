"""The Android TV HA Bridge integration."""
from __future__ import annotations

from pathlib import Path

from homeassistant.components.frontend import add_extra_js_url
from homeassistant.components.http import StaticPathConfig
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import HomeAssistant

from .client import BridgeClient
from .const import CONF_DEVICE_ID, CONF_FINGERPRINT, CONF_TOKEN, DOMAIN, PLATFORMS

type BridgeConfigEntry = ConfigEntry[BridgeClient]

CARD_FILENAME = "androidtv-ha-bridge-card.js"
CARD_URL = f"/{DOMAIN}/{CARD_FILENAME}"
CARD_VERSION = "0.1.0"


async def _async_register_card(hass: HomeAssistant) -> None:
    """Serve the bundled 'TV frame' card and load it on every dashboard (once), so the
    user never has to add a Lovelace resource by hand."""
    if hass.data.get(f"{DOMAIN}_card"):
        return
    path = Path(__file__).parent / CARD_FILENAME
    await hass.http.async_register_static_paths(
        [StaticPathConfig(CARD_URL, str(path), cache_headers=False)]
    )
    add_extra_js_url(hass, f"{CARD_URL}?v={CARD_VERSION}")
    hass.data[f"{DOMAIN}_card"] = True


async def async_setup_entry(hass: HomeAssistant, entry: BridgeConfigEntry) -> bool:
    """Set up a bridge from a config entry."""
    await _async_register_card(hass)
    client = BridgeClient(
        hass,
        entry.data[CONF_HOST],
        entry.data[CONF_PORT],
        entry.data[CONF_TOKEN],
        entry.data[CONF_DEVICE_ID],
        entry.data.get(CONF_FINGERPRINT),
    )
    client.device_name = entry.title
    await client.async_start()
    entry.runtime_data = client
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: BridgeConfigEntry) -> bool:
    """Unload a config entry."""
    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unload_ok:
        await entry.runtime_data.async_stop()
    return unload_ok
