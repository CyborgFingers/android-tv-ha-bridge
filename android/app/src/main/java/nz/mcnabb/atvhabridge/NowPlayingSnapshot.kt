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

/** True when a Watch-Next tile (already split into [series] / [episodeTitle]) is the item in
 * [snap]: the same show plus the same season/episode when the snapshot has numbers, else the
 * same title. An idle snapshot matches nothing — what was last watched is a fair pick while
 * the user browses; only what's playing or paused is left out of the up-next picks. */
fun isNowPlaying(series: String?, episodeTitle: String?, season: String?, episode: String?, snap: NowPlayingSnapshot?): Boolean {
    if (snap == null || snap.state == "idle") return false
    val show = snap.seriesTitle ?: snap.title ?: return false
    val sameShow = series.equals(show, ignoreCase = true)
    if (snap.season != null || snap.episode != null) return sameShow && season == snap.season && episode == snap.episode
    return sameShow || episodeTitle.equals(snap.title, ignoreCase = true) ||
        listOfNotNull(series, episodeTitle).joinToString(" — ").equals(snap.title, ignoreCase = true)
}

/** How worth reading a session is (0 = never). Playing first; then one that both names its
 * item and reports a transport state — an app may publish two (the patched Jellyfin video
 * player, paused or buffering, next to a media3 session idling at STATE_NONE with nothing
 * in it), and the one that says what it's doing wins; then a session that only names its
 * item; last one that publishes neither, unless it's stopped or errored. Pure — unit-tested. */
fun sessionRank(state: Int?, hasMetadata: Boolean): Int = when {
    state == PlaybackState.STATE_PLAYING -> 4
    hasMetadata && state != null && state != PlaybackState.STATE_NONE -> 3
    hasMetadata -> 2
    state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_ERROR -> 1
    else -> 0
}

/** Buffering is playing: the player is running, as the overlay-scrape path already reads a
 * silent "Pause" control (see [emptySessionState]) — a rebuffer must not flash "idle". */
fun playbackStateToString(state: Int?): String = when (state) {
    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> "playing"
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
