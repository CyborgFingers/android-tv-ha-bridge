"""Config flow for Android TV HA Bridge: zeroconf discovery + 6-digit pairing.

The bridge advertises itself over mDNS with its cert fingerprint in the TXT record, so
a discovered device is pinned from the first byte. Manual entry pins trust-on-first-use:
it reads the fingerprint from /api/info over TLS, then pins it for good.
"""
from __future__ import annotations

from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

from .client import async_get_info, async_pair
from .const import CONF_DEVICE_ID, CONF_FINGERPRINT, CONF_TOKEN, DOMAIN

DEFAULT_PORT = 8099


class AndroidTVBridgeConfigFlow(ConfigFlow, domain=DOMAIN):
    """Discover a bridge (or take it manually), then pair with its 6-digit code."""

    VERSION = 1

    def __init__(self) -> None:
        self._host: str = ""
        self._port: int = DEFAULT_PORT
        self._name: str = "Android TV"
        self._fingerprint: str | None = None

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Manual entry — host + port, then trust-on-first-use to learn the fingerprint."""
        errors: dict[str, str] = {}
        if user_input is not None:
            self._host = user_input[CONF_HOST]
            self._port = user_input[CONF_PORT]
            info = await async_get_info(async_get_clientsession(self.hass), self._host, self._port)
            if info is None or not info.get("id"):
                errors["base"] = "cannot_connect"
            else:
                await self.async_set_unique_id(info["id"])
                self._abort_if_unique_id_configured()
                self._name = info.get("name") or self._name
                self._fingerprint = info.get("fingerprint")
                return await self.async_step_pair()
        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {vol.Required(CONF_HOST): str, vol.Required(CONF_PORT, default=DEFAULT_PORT): int}
            ),
            errors=errors,
        )

    async def async_step_zeroconf(self, discovery_info: ZeroconfServiceInfo) -> ConfigFlowResult:
        """Discovered over mDNS — the TXT record carries id, name and fingerprint."""
        props = discovery_info.properties
        device_id = props.get("id")
        if not device_id:
            return self.async_abort(reason="no_id")
        await self.async_set_unique_id(device_id)
        self._host = str(discovery_info.ip_address)
        self._port = discovery_info.port or DEFAULT_PORT
        self._abort_if_unique_id_configured(
            updates={CONF_HOST: self._host, CONF_PORT: self._port}
        )
        self._name = props.get("name") or self._name
        self._fingerprint = props.get("fp")
        self.context["title_placeholders"] = {"name": self._name}
        return await self.async_step_pair()

    async def async_step_pair(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Redeem the 6-digit code shown on the TV for a long-lived token."""
        errors: dict[str, str] = {}
        if user_input is not None:
            token = await async_pair(
                async_get_clientsession(self.hass),
                self._host,
                self._port,
                user_input["code"].strip(),
                self._fingerprint,
            )
            if token is None:
                errors["base"] = "invalid_code"
            else:
                return self.async_create_entry(
                    title=self._name,
                    data={
                        CONF_HOST: self._host,
                        CONF_PORT: self._port,
                        CONF_TOKEN: token,
                        CONF_DEVICE_ID: self.unique_id,
                        CONF_FINGERPRINT: self._fingerprint,
                    },
                )
        return self.async_show_form(
            step_id="pair",
            data_schema=vol.Schema({vol.Required("code"): str}),
            description_placeholders={"name": self._name},
            errors=errors,
        )
