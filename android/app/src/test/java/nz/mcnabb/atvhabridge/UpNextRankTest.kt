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
        // A series name alone never identifies an episode tile — and an overlay that names
        // no tile (with no S:E label either) means an item the row doesn't hold: unknown.
        assertEquals(null, pickPlaying(tiles, listOf("Chuck")))
        assertEquals("24", pickPlaying(tiles, emptyList())?.title)
        assertEquals(null, pickPlaying(emptyList(), listOf("Chuck _amp")))
    }

    @Test
    fun `an item the row doesn't hold is never mistaken for another tile`() {
        val tiles = listOf(
            tile("24", "6", "21", continueType, ep = "Day 6: 2:00 A.M.-3:00 A.M.", durationMs = 2_495_000),
            tile("Mayday", "2", "6", continueType, ep = "Blow Out", durationMs = 2_687_000),
            tile("Inside Out", null, null, continueType, durationMs = 5_700_000),
        )
        // An 8-minute special that isn't in the row: nothing on screen names a tile → unknown.
        assertEquals(null, pickPlaying(tiles, listOf("Chuck _amp", "Pause"), playerDurationMs = 472_000))
        // A fresh 41-minute S2:E7 of another series: adjacent to Mayday S2E6 by number, but not its length.
        assertEquals(null, pickPlaying(tiles, listOf("Pause", "Skip Next"), season = "2", episode = "7", playerDurationMs = 2_477_000))
        assertEquals(null, pickPlaying(tiles, listOf("Pause", "Skip Next"), season = "2", episode = "8", playerDurationMs = 2_690_000))
        // Autoplay into the next episode of the same series keeps the series (S6E21 → S6:E22, same length).
        assertEquals("24", pickPlaying(tiles, listOf("Day 6: 3:00 A.M.-4:00 A.M."), season = "6", episode = "22", playerDurationMs = 2_500_000)?.title)
        // Lengths unknown on the tile → the adjacent-episode rule stands on its own.
        assertEquals("Mayday", pickPlaying(listOf(tile("Mayday", "2", "6", continueType)), listOf("Pause"), season = "2", episode = "7", playerDurationMs = 2_477_000)?.title)
        // A name match wins regardless of the label; an overlay never read → recency stands.
        assertEquals("Inside Out", pickPlaying(tiles, listOf("Inside Out"), season = "9", episode = "9")?.title)
        assertEquals("24", pickPlaying(tiles, emptyList(), season = "2", episode = "8")?.title)
        // The overlay named nothing this read (its title label fades) — the reader keeps its last pick.
        assertEquals(null, namedOnScreen(tiles, listOf("Pause", "Skip Next")))
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
