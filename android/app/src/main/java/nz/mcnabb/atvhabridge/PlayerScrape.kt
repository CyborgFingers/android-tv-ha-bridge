package nz.mcnabb.atvhabridge

import android.os.SystemClock

/**
 * Now-playing details read from the FOREGROUND player's on-screen controls by the
 * accessibility service — the generic last resort for apps (e.g. Jellyfin) that publish
 * nothing to their MediaSession and whose Watch-Next tile lags the live item.
 *
 * The overlay auto-hides, so a scrape only lands while the controls are visible (playback
 * start, and any time the user touches the remote). [positionMs] is therefore anchored at
 * [scrapedAtElapsed] and projected forward by the reader while audio is playing.
 */
object PlayerScrape {
    @Volatile var pkg: String? = null
    @Volatile var title: String? = null
    @Volatile var season: String? = null
    @Volatile var episode: String? = null
    @Volatile var positionMs: Long = 0
    @Volatile var durationMs: Long = 0
    @Volatile var scrapedAtElapsed: Long = 0L

    /** Fired (on the a11y thread) after a fresh scrape so the reader can publish promptly
     * instead of waiting for the next heartbeat. */
    @Volatile var onScrape: (() -> Unit)? = null

    fun clear() {
        pkg = null; title = null; season = null; episode = null
        positionMs = 0; durationMs = 0; scrapedAtElapsed = 0L
    }

    /** Age of the last scrape in ms (Long.MAX_VALUE if never scraped). */
    fun ageMs(): Long =
        if (scrapedAtElapsed == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - scrapedAtElapsed
}
