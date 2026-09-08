package nz.mcnabb.atvhabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The up-next picks: NEXT episodes first, recency kept within a group, what's playing left out. */
class UpNextRankTest {
    private val continueType = 0
    private val nextType = WATCH_NEXT_TYPE_NEXT

    private fun tile(title: String, season: String?, episode: String?, type: Int, ep: String? = null, durationMs: Long = 0L) =
        WatchNextResolver.Info(title, ep, null, 0L, season, episode, durationMs = durationMs, type = type)

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
            tile("Hey Arnold!", "1", "17", continueType), // a double-inserted row → out
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
    fun `the overlay's own labels pick the tile on screen, else recency stands`() {
        val tiles = listOf(
            tile("24", "6", "21", continueType, ep = "Day 6: 2:00 A.M.-3:00 A.M."), // most recent
            tile("Chuck", null, null, nextType, ep = "Chuck _amp"),
            tile("Inside Out", null, null, continueType),
        )
        assertEquals("Chuck", pickPlaying(tiles, listOf("Pause", "chuck _amp ", "Playback speed"))?.title)
        assertEquals("Inside Out", pickPlaying(tiles, listOf("Inside Out"))?.title)
        // An episode names itself by its episode name (the reader strips the S:E prefix).
        assertEquals("21", pickPlaying(tiles, listOf("Day 6: 2:00 A.M.-3:00 A.M."))?.episode)
        // A series name alone never identifies an episode tile.
        assertEquals("24", pickPlaying(tiles, listOf("Chuck"))?.title)
        assertEquals("24", pickPlaying(tiles, emptyList())?.title)
        assertEquals(null, pickPlaying(emptyList(), listOf("Chuck _amp")))
    }

    @Test
    fun `an item the row doesn't hold is not mistaken for the most recent tile when lengths disagree`() {
        val episode = tile("24", "6", "21", continueType, ep = "Day 6: 2:00 A.M.-3:00 A.M.", durationMs = 2_520_000)
        val tiles = listOf(episode, tile("Inside Out", null, null, continueType, durationMs = 5_700_000))
        // An 8-minute special that isn't in the row: nothing matches, no length agrees → unknown.
        assertEquals(null, pickPlaying(tiles, listOf("Chuck _amp", "Pause"), playerDurationMs = 472_000))
        // The next episode of the same series (near-same length, not in the row yet) keeps the series.
        assertEquals("24", pickPlaying(tiles, listOf("Day 6: 3:00 A.M.-4:00 A.M."), playerDurationMs = 2_540_000)?.title)
        // A 95-minute movie is not the 95-minute-ish tile of another film (3 %, not 10 %).
        assertEquals(null, pickPlaying(tiles, listOf("Pause"), playerDurationMs = 5_400_000))
        // A name match wins regardless of length; no lengths to compare → recency stands.
        assertEquals("Inside Out", pickPlaying(tiles, listOf("Inside Out"), playerDurationMs = 472_000)?.title)
        assertEquals("24", pickPlaying(tiles, listOf("Chuck _amp"), playerDurationMs = 0L)?.title)
        assertEquals("24", pickPlaying(listOf(tile("24", "6", "21", continueType)), listOf("Chuck _amp"), playerDurationMs = 472_000)?.title)
        // The newest tile is an 8-minute special; the 42-minute player is the newest 42-minute tile.
        val withSpecial = listOf(tile("Chuck", null, null, continueType, ep = "Chuck _amp", durationMs = 472_720)) + tiles
        assertEquals("21", mostRecentOfLength(withSpecial, 2_495_000)?.episode)
        assertEquals(null, namedOnScreen(withSpecial, listOf("Pause", "Skip Next")))
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
