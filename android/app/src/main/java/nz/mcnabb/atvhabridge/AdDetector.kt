package nz.mcnabb.atvhabridge

/** Ad state derived for one media-session snapshot. */
data class AdState(
    val isAd: Boolean = false,
    val skippable: Boolean = false,
    val skipInSec: Int = 0
) {
    companion object {
        val NONE = AdState()
    }
}

/**
 * Decides whether the current media-session item is an advertisement and,
 * if so, when its on-screen "Skip" becomes available.
 *
 * Signals, most trusted first:
 *
 *  1. `MediaMetadataCompat.METADATA_KEY_ADVERTISEMENT`
 *     ("android.media.metadata.ADVERTISEMENT", a long; non-zero == ad).
 *     ExoPlayer/media3's MediaSessionConnector sets it while
 *     `Player.isPlayingAd()`. Definitive whenever it is present.
 *
 *  2. YouTube heuristic. YouTube for Android TV runs on Cobalt, whose
 *     CobaltMediaSession publishes only title/artist/album/art/duration and
 *     never the advertisement key, so for YouTube an ad is inferred as a
 *     short item (<= [MAX_AD_SEC]) interrupting a long-form item
 *     (> [MAX_AD_SEC]) in the same app: ads are separate short streams, so
 *     the published duration is expected to drop to the ad length and come
 *     back afterwards. The flag auto-clears after MAX_AD_SEC + slack of
 *     wall-clock so a genuinely short video isn't flagged for its whole
 *     length, and it never fires before a long-form item has been seen.
 *
 * Nothing in the session says whether an ad is skippable, so [AdState.skipInSec]
 * counts [SKIP_AFTER_SEC] of wall-clock from the moment the ad (or the next
 * ad in a pod — a title/duration change) was first seen; `skippable` flips
 * when it reaches 0. Non-skippable ads therefore also flip — the launcher's
 * Skip press is a harmless DPAD_CENTER when no Skip button is on screen.
 *
 * Pure JVM (no android.* types) so it is unit-testable; the service feeds it
 * plain values from MediaMetadata/PlaybackState.
 */
class AdDetector(private val now: () -> Long = System::currentTimeMillis) {
    private var lastPackage: String? = null
    private var contentDurationMs = 0L
    private var adStartedAt = 0L
    private var adKey: String? = null

    fun update(packageName: String, title: String?, durationMs: Long, adFlag: Boolean): AdState {
        if (packageName != lastPackage) {
            lastPackage = packageName
            contentDurationMs = 0L
            endAd()
        }
        val isAd = adFlag || heuristicAd(packageName, durationMs)
        if (!isAd) {
            endAd()
            return AdState.NONE
        }
        val key = "$title|$durationMs"
        if (adStartedAt == 0L || key != adKey) {
            adStartedAt = now()
            adKey = key
        }
        val elapsedSec = (now() - adStartedAt) / 1000
        val skipIn = (SKIP_AFTER_SEC - elapsedSec).coerceAtLeast(0).toInt()
        return AdState(isAd = true, skippable = skipIn == 0, skipInSec = skipIn)
    }

    private fun heuristicAd(packageName: String, durationMs: Long): Boolean {
        if (MAX_AD_SEC <= 0 || !packageName.startsWith(YOUTUBE_PACKAGE_PREFIX)) return false
        val maxAdMs = MAX_AD_SEC * 1000L
        if (durationMs > maxAdMs) {
            contentDurationMs = durationMs
            return false
        }
        if (durationMs <= 0L || contentDurationMs == 0L) return false
        if (adStartedAt != 0L && now() - adStartedAt > maxAdMs + AD_SLACK_MS) {
            // Ran longer than any ad could: it's a real short video. Stop
            // flagging until a long-form item plays again.
            contentDurationMs = 0L
            return false
        }
        return true
    }

    private fun endAd() {
        adStartedAt = 0L
        adKey = null
    }

    companion object {
        /** YouTube's skippable ads unlock "Skip" 5 s in. */
        const val SKIP_AFTER_SEC = 5L

        /** Longest item still treated as an ad by the heuristic; 0 disables it. */
        const val MAX_AD_SEC = 120L

        private const val AD_SLACK_MS = 15_000L
        private const val YOUTUBE_PACKAGE_PREFIX = "com.google.android.youtube"
    }
}
