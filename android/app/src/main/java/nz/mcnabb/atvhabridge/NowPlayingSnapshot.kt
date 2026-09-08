package nz.mcnabb.atvhabridge

import android.media.session.PlaybackState

/** Immutable snapshot of the current item, taken at [positionUpdatedAt] (wall-clock ms).
 * [positionMs] is projected to that instant so a consumer can extrapolate live progress. */
data class NowPlayingSnapshot(
    val title: String?,        // display title (composed "Show — Episode" for Watch-Next apps)
    val seriesTitle: String?,  // show name, when known
    val episodeTitle: String?, // episode / season-episode label, when known
    val season: String? = null,  // season number/label, when known (Watch-Next)
    val episode: String? = null, // episode number/label, when known (Watch-Next)
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val positionMs: Long,
    val positionUpdatedAt: Long,
    val state: String,
    val appPackage: String,
    val appName: String,
    val ad: AdState = AdState.NONE,
    /** http(s) poster from the Watch-Next tile, fetched + re-served locally by the bridge. */
    val posterUrl: String? = null,
)

/** The "up next" tile for the current app (Watch-Next type = NEXT), when the app publishes one. */
data class UpNext(
    val title: String?,
    val seriesTitle: String?,
    val posterUrl: String?,
)

/** One up-next pick (a Watch-Next tile) in `up_next_list`. [artPath] is the bridge-relative
 * poster path (`/art_next_<i>.jpg?v=…`), null until/unless the poster resolved. */
data class UpNextItem(
    val title: String?,        // show / series name
    val episodeTitle: String?, // episode label, when known
    val season: String?,
    val episode: String?,
    val artPath: String?,
    val durationMs: Long,
    val positionMs: Long,
)

fun playbackStateToString(state: Int?): String = when (state) {
    PlaybackState.STATE_PLAYING -> "playing"
    PlaybackState.STATE_PAUSED -> "paused"
    PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> "idle"
    else -> "idle"
}

/** Play-state for an app that publishes nothing to its session (Jellyfin): audio is the
 * honest "playing" signal; the overlay scrape says whether the user is still inside the
 * player — paused, or stalled/buffering when its control still offers "Pause" — or has
 * left it for the menus, in which case the session's own state (idle) stands. */
fun emptySessionState(musicActive: Boolean, inPlayer: Boolean, transport: String?, sessionState: String): String = when {
    musicActive -> "playing"
    inPlayer -> if (transport == "playing") "playing" else "paused"
    else -> sessionState
}

private val FRIENDLY_APP_NAMES = mapOf(
    "com.google.android.youtube.tv" to "YouTube",
    "com.netflix.ninja" to "Netflix",
    "org.jellyfin.androidtv" to "Jellyfin",
    "com.spotify.tv.android" to "Spotify",
    "com.disney.disneyplus" to "Disney+",
    "com.plexapp.android" to "Plex",
)

fun friendlyAppName(packageName: String): String = FRIENDLY_APP_NAMES[packageName] ?: packageName
