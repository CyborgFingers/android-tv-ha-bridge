package nz.mcnabb.atvhabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRulesTest {
    @Test
    fun `web links and the app deep links in use may be opened`() {
        assertTrue(openAllowed("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "app.smarttube.fdroid"))
        assertTrue(openAllowed("http://www.netflix.com/title/80057281", "com.netflix.ninja"))
        assertTrue(openAllowed("nextpvrtv://play/All%20Channels/42", "com.example.pvr"))
        assertTrue(openAllowed("vnd.youtube:dQw4w9WgXcQ", "app.smarttube.fdroid"))
        assertTrue(openAllowed("youtube://watch?v=dQw4w9WgXcQ", "app.smarttube.fdroid"))
        assertTrue(openAllowed("HTTPS://www.tvnz.co.nz/shows/country-calendar", "nz.co.tvnz.ondemand.tv"))
    }

    @Test
    fun `anything that reaches files, providers, intents or script is refused`() {
        val pkg = "app.smarttube.fdroid"
        assertFalse(openAllowed("file:///sdcard/x.mp4", pkg))
        assertFalse(openAllowed("content://media/external/video/1", pkg))
        assertFalse(openAllowed("intent://x#Intent;component=a/.B;end", pkg))
        assertFalse(openAllowed("javascript:alert(1)", pkg))
        assertFalse(openAllowed(" https://www.youtube.com/", pkg)) // scheme must be the very start
        assertFalse(openAllowed("www.youtube.com/watch?v=x", pkg)) // no scheme at all
        assertFalse(openAllowed("", pkg))
        assertFalse(openAllowed("https://x/\u0000y", pkg))
        assertFalse(openAllowed("https://x/" + "a".repeat(2048), pkg))
    }

    @Test
    fun `the target must be a well-formed package name`() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertFalse(openAllowed(url, ""))
        assertFalse(openAllowed(url, "smarttube"))
        assertFalse(openAllowed(url, "app.smarttube.fdroid/.Main"))
        assertFalse(openAllowed(url, "app..fdroid"))
        assertFalse(openAllowed(url, "1app.smarttube"))
    }

    @Test
    fun `search query is stripped of control characters and capped at 100`() {
        assertEquals("minecraft videos", searchQuery("  minecraft\n videos\t"))
        assertEquals("a".repeat(100), searchQuery("a".repeat(100)))
        assertNull(searchQuery("a".repeat(101)))
        assertNull(searchQuery(" \n\u0007 "))
        assertNull(searchQuery(""))
    }
}
