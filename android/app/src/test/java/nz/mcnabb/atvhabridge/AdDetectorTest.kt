package nz.mcnabb.atvhabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdDetectorTest {
    private var clock = 1_000_000L
    private val detector = AdDetector { clock }

    private fun tick(sec: Long) { clock += sec * 1000 }

    @Test
    fun `advertisement metadata key is definitive for any app`() {
        val ad = detector.update("com.netflix.ninja", "Promo", 15_000, adFlag = true)
        assertTrue(ad.isAd)
        assertFalse(ad.skippable)
        assertEquals(5, ad.skipInSec)

        tick(5)
        val later = detector.update("com.netflix.ninja", "Promo", 15_000, adFlag = true)
        assertTrue(later.skippable)
        assertEquals(0, later.skipInSec)

        assertFalse(detector.update("com.netflix.ninja", "Show", 2_747_000, adFlag = false).isAd)
    }

    @Test
    fun `youtube short item after long-form is an ad and clears when content resumes`() {
        val yt = "com.google.android.youtube.tv"
        assertFalse(detector.update(yt, "Episode", 2_747_000, adFlag = false).isAd)
        assertTrue(detector.update(yt, "Episode", 15_000, adFlag = false).isAd)
        assertFalse(detector.update(yt, "Episode", 2_747_000, adFlag = false).isAd)
    }

    @Test
    fun `a second ad in a pod restarts the skip countdown`() {
        val yt = "com.google.android.youtube.tv"
        detector.update(yt, "Episode", 2_747_000, adFlag = false)
        detector.update(yt, "Ad one", 15_000, adFlag = false)
        tick(5)
        assertTrue(detector.update(yt, "Ad one", 15_000, adFlag = false).skippable)
        val second = detector.update(yt, "Ad two", 20_000, adFlag = false)
        assertTrue(second.isAd)
        assertEquals(5, second.skipInSec)
    }

    @Test
    fun `short item with no long-form before it is not an ad`() {
        assertFalse(detector.update("com.google.android.youtube.tv", "Short", 30_000, adFlag = false).isAd)
    }

    @Test
    fun `short item that outlives an ad is a real video and stops being flagged`() {
        val yt = "com.google.android.youtube.tv"
        detector.update(yt, "Episode", 2_747_000, adFlag = false)
        assertTrue(detector.update(yt, "Clip", 90_000, adFlag = false).isAd)
        tick(AdDetector.MAX_AD_SEC + 16)
        assertFalse(detector.update(yt, "Clip", 90_000, adFlag = false).isAd)
        // Stays clear until long-form plays again.
        assertFalse(detector.update(yt, "Another clip", 60_000, adFlag = false).isAd)
    }

    @Test
    fun `heuristic is youtube-only`() {
        detector.update("com.netflix.ninja", "Show", 2_747_000, adFlag = false)
        assertFalse(detector.update("com.netflix.ninja", "Show", 15_000, adFlag = false).isAd)
    }
}
