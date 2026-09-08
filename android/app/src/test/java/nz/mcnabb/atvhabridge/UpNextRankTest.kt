package nz.mcnabb.atvhabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The up-next picks: NEXT episodes first, recency kept within a group, what's playing left out. */
class UpNextRankTest {
    private val continueType = 0
    private val nextType = WATCH_NEXT_TYPE_NEXT

    private fun tile(title: String, season: String?, episode: String?, type: Int, ep: String? = null) =
        WatchNextResolver.Info(title, ep, null, 0L, season, episode, type = type)

    private fun snap(series: String?, title: String?, season: String?, episode: String?, state: String = "playing") =
        NowPlayingSnapshot(
            title = title, seriesTitle = series, episodeTitle = null, season = season, episode = episode,
            artist = null, album = null, durationMs = 0, positionMs = 0, positionUpdatedAt = 0,
            state = state, appPackage = "org.jellyfin.androidtv", appName = "Jellyfin",
        )

    private fun label(t: WatchNextResolver.Info) = "${t.title} S${t.season}E${t.episode}"

    @Test
    fun `next episodes lead, provider order survives within a group, the playing episode is dropped`() {
        val playing = snap("24", "24 — Day 6: 1:00 A.M.-2:00 A.M.", "6", "20")
        val tiles = listOf(
            tile("24", "6", "20", continueType),        // now playing → out
            tile("Hey Arnold!", "1", "17", continueType),
            tile("24", "4", "11", nextType),
            tile("Chuck", "2", "3", nextType),
            tile("Inside Out", null, null, continueType),
        )
        val ranked = rankUpNext(tiles) { isNowPlaying(it.title, it.episodeTitle, it.season, it.episode, playing) }
        assertEquals(
            listOf("24 S4E11", "Chuck S2E3", "Hey Arnold! S1E17", "Inside Out SnullEnull"),
            ranked.map(::label),
        )
    }

    @Test
    fun `a paused episode is still the current item, an idle snapshot excludes nothing`() {
        val paused = snap("24", "24 — x", "6", "20", state = "paused")
        assertTrue(isNowPlaying("24", "x", "6", "20", paused))
        assertFalse(isNowPlaying("24", "x", "6", "21", paused))   // the next episode stays
        assertFalse(isNowPlaying("24", "x", "6", "20", snap("24", "24 — x", "6", "20", state = "idle")))
        assertFalse(isNowPlaying("24", "x", "6", "20", null))
    }

    @Test
    fun `without episode numbers a movie or video matches by title`() {
        assertTrue(isNowPlaying("Inside Out", null, null, null, snap("Inside Out", "Inside Out", null, null)))
        // A MediaSession app: no series, the tile title is the video title.
        assertTrue(isNowPlaying("Some Video", null, null, null, snap(null, "some video", null, null)))
        assertFalse(isNowPlaying("Other Film", null, null, null, snap("Inside Out", "Inside Out", null, null)))
        // Nothing known about what's playing → nothing to exclude.
        assertFalse(isNowPlaying("Inside Out", null, null, null, snap(null, null, null, null)))
    }
}
