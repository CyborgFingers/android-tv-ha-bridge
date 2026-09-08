package nz.mcnabb.atvhabridge

import org.junit.Assert.assertEquals
import org.junit.Test

class EmptySessionStateTest {
    @Test
    fun `audio means playing, the scrubber tells paused from browsing`() {
        assertEquals("playing", emptySessionState(musicActive = true, inPlayer = false, transport = null, sessionState = "idle"))
        assertEquals("paused", emptySessionState(musicActive = false, inPlayer = true, transport = "paused", sessionState = "idle"))
        // A player whose overlay has no readable play/pause control: in it + silent = paused.
        assertEquals("paused", emptySessionState(musicActive = false, inPlayer = true, transport = null, sessionState = "idle"))
        // Overlay still offers "Pause" with no audio yet: buffering/stalled — the app is playing.
        assertEquals("playing", emptySessionState(musicActive = false, inPlayer = true, transport = "playing", sessionState = "idle"))
        // Scrubber gone from the player's window = back in the menus, whatever was read last.
        assertEquals("idle", emptySessionState(musicActive = false, inPlayer = false, transport = "paused", sessionState = "idle"))
    }
}
