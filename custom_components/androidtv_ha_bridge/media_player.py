"""Media player for Android TV HA Bridge — now-playing + control."""
from __future__ import annotations

from homeassistant.components.media_player import (
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
    MediaType,
)
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from . import BridgeConfigEntry
from .entity import BridgeEntity

STATE_MAP = {
    "playing": MediaPlayerState.PLAYING,
    "paused": MediaPlayerState.PAUSED,
    "idle": MediaPlayerState.IDLE,
}

FEATURES = (
    MediaPlayerEntityFeature.PLAY
    | MediaPlayerEntityFeature.PAUSE
    | MediaPlayerEntityFeature.STOP
    | MediaPlayerEntityFeature.NEXT_TRACK
    | MediaPlayerEntityFeature.PREVIOUS_TRACK
    | MediaPlayerEntityFeature.SEEK
    | MediaPlayerEntityFeature.VOLUME_STEP
    | MediaPlayerEntityFeature.VOLUME_SET
    | MediaPlayerEntityFeature.VOLUME_MUTE
    | MediaPlayerEntityFeature.TURN_ON
    | MediaPlayerEntityFeature.TURN_OFF
    | MediaPlayerEntityFeature.PLAY_MEDIA
)


async def async_setup_entry(
    hass: HomeAssistant, entry: BridgeConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    async_add_entities([BridgeMediaPlayer(entry.runtime_data)])


class BridgeMediaPlayer(BridgeEntity, MediaPlayerEntity):
    """The TV as a media player."""

    _attr_name = None  # takes the device name
    _attr_supported_features = FEATURES
    _attr_media_content_type = MediaType.VIDEO
    _attr_media_image_remotely_accessible = False

    def __init__(self, client) -> None:
        super().__init__(client)
        self._attr_unique_id = f"{client.device_id}_media"

    @property
    def _np(self) -> dict:
        return self._client.state.get("now_playing") or {}

    @property
    def _app(self) -> dict:
        return self._client.state.get("app") or {}

    @property
    def _sensors(self) -> dict:
        return self._client.state.get("sensors") or {}

    @property
    def state(self) -> MediaPlayerState:
        return STATE_MAP.get(self._client.state.get("state"), MediaPlayerState.IDLE)

    @property
    def media_title(self) -> str | None:
        return self._np.get("title")

    @property
    def media_series_title(self) -> str | None:
        return self._np.get("series_title")

    @property
    def media_season(self) -> str | None:
        return self._np.get("season")

    @property
    def media_episode(self) -> str | None:
        return self._np.get("episode")

    @property
    def app_name(self) -> str | None:
        return self._app.get("name")

    @property
    def app_id(self) -> str | None:
        return self._app.get("package")

    @property
    def media_duration(self) -> int | None:
        return self._np.get("duration")

    @property
    def media_position(self) -> int | None:
        return self._np.get("position")

    @property
    def media_position_updated_at(self):
        return dt_util.parse_datetime(self._np.get("position_updated_at") or "")

    @property
    def media_image_url(self) -> str | None:
        return self._client.art_url(self._np.get("art"))

    async def async_get_media_image(self) -> tuple[bytes | None, str | None]:
        """Proxy the poster through the pinned TLS connection (self-signed cert)."""
        url = self.media_image_url
        if not url:
            return None, None
        return await self._client.async_fetch_image(url)

    @property
    def extra_state_attributes(self) -> dict:
        """Expose the richer now-playing detail that has no standard media_player field,
        so custom dashboards/automations (and a separate integration) can read it all."""
        np = self._np
        up = self._client.state.get("up_next") or {}
        attrs = {
            "episode_title": np.get("episode_title"),
            "is_ad": np.get("is_ad"),
            "up_next_title": up.get("title"),
            "up_next_series_title": up.get("series_title"),
        }
        if np.get("is_ad"):
            attrs["ad_skippable"] = np.get("ad_skippable")
            attrs["ad_skip_in"] = np.get("ad_skip_in")
        return {k: v for k, v in attrs.items() if v is not None}

    @property
    def volume_level(self) -> float | None:
        v = self._sensors.get("volume_level")
        return v / 100 if v is not None else None

    @property
    def is_volume_muted(self) -> bool | None:
        return self._sensors.get("volume_muted")

    async def async_media_play(self) -> None:
        await self._client.async_send("play")

    async def async_media_pause(self) -> None:
        await self._client.async_send("pause")

    async def async_media_stop(self) -> None:
        await self._client.async_send("stop")

    async def async_media_next_track(self) -> None:
        await self._client.async_send("next")

    async def async_media_previous_track(self) -> None:
        await self._client.async_send("previous")

    async def async_media_seek(self, position: float) -> None:
        await self._client.async_send("seek", position=int(position))

    async def async_volume_up(self) -> None:
        await self._client.async_send("volume_up")

    async def async_volume_down(self) -> None:
        await self._client.async_send("volume_down")

    async def async_set_volume_level(self, volume: float) -> None:
        await self._client.async_send("volume_set", level=int(volume * 100))

    async def async_mute_volume(self, mute: bool) -> None:
        await self._client.async_send("volume_mute" if mute else "volume_unmute")

    async def async_turn_off(self) -> None:
        await self._client.async_send("sleep")

    async def async_turn_on(self) -> None:
        await self._client.async_send("wake")

    async def async_play_media(self, media_type: str, media_id: str, **kwargs) -> None:
        if media_type in ("app", MediaType.APP):
            await self._client.async_send("launch", package=media_id)
