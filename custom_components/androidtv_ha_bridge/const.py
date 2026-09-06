"""Constants for the Android TV HA Bridge integration."""

DOMAIN = "androidtv_ha_bridge"
PLATFORMS = ["media_player", "remote", "sensor"]

CONF_TOKEN = "token"
CONF_DEVICE_ID = "device_id"
CONF_FINGERPRINT = "fingerprint"

# Fired on the HA event bus on every state push, carrying the full bridge document
# ({device_id, name, data}). This is the generic pass-through other integrations (e.g.
# a remote) subscribe to, and what dashboards/automations can key off. See PROTOCOL.md.
EVENT_UPDATED = f"{DOMAIN}_updated"
