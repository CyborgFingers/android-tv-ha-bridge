package nz.mcnabb.atvhabridge

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRankTest {
    @Test
    fun `the session that says what it is doing beats the one that says nothing`() {
        // Jellyfin's video player (paused, with metadata) vs its media3 session (STATE_NONE, no metadata)
        assertTrue(sessionRank(PlaybackState.STATE_PAUSED, true) > sessionRank(PlaybackState.STATE_NONE, false))
        // ...and vs a session that names an item but reports no transport state at all
        assertTrue(sessionRank(PlaybackState.STATE_PAUSED, true) > sessionRank(PlaybackState.STATE_NONE, true))
        assertTrue(sessionRank(PlaybackState.STATE_BUFFERING, true) > sessionRank(PlaybackState.STATE_NONE, true))
        // Playing wins outright, metadata or not.
        assertTrue(sessionRank(PlaybackState.STATE_PLAYING, false) > sessionRank(PlaybackState.STATE_PAUSED, true))
        // An empty-session app (STATE_NONE, no metadata) is still readable — last resort.
        assertEquals(1, sessionRank(PlaybackState.STATE_NONE, false))
        assertEquals(1, sessionRank(null, false))
        // Stopped or errored sessions are never picked.
        assertEquals(0, sessionRank(PlaybackState.STATE_STOPPED, false))
        assertEquals(0, sessionRank(PlaybackState.STATE_ERROR, false))
    }

    @Test
    fun `a buffering session is playing, not idle`() {
        assertEquals("playing", playbackStateToString(PlaybackState.STATE_BUFFERING))
        assertEquals("playing", playbackStateToString(PlaybackState.STATE_PLAYING))
        assertEquals("paused", playbackStateToString(PlaybackState.STATE_PAUSED))
        assertEquals("idle", playbackStateToString(PlaybackState.STATE_NONE))
        assertEquals("idle", playbackStateToString(null))
    }
}
