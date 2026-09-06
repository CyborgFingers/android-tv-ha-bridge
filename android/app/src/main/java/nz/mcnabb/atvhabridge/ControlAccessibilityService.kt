package nz.mcnabb.atvhabridge

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * D-pad / OK / Back / Home navigation without ADB, AND a generic now-playing scraper.
 *
 * The scraper is the last resort for players that publish nothing to their MediaSession
 * (e.g. Jellyfin): while the on-screen player controls are visible it reads the live
 * position, duration and title straight off the overlay's accessibility nodes — no
 * per-app integration. It only reads while the current media app is foreground; the
 * reader ([MediaListenerService]) projects the position forward between reads.
 */
class ControlAccessibilityService : AccessibilityService() {

    private var lastScrapeMs = 0L

    override fun onServiceConnected() {
        Controls.accessibility = this
    }

    override fun onDestroy() {
        if (Controls.accessibility === this) Controls.accessibility = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != Controls.currentMediaPackage) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScrapeMs < SCRAPE_THROTTLE_MS) return
        lastScrapeMs = now
        runCatching { scrapePlayer(pkg) }
    }

    override fun onInterrupt() {}

    /** Walk the foreground window's nodes for the player overlay's time/title texts. */
    private fun scrapePlayer(pkg: String) {
        val root = rootInActiveWindow ?: return
        val screenH = resources.displayMetrics.heightPixels
        val times = ArrayList<Long>()            // media times (seconds) in the lower half
        val longTexts = ArrayList<String>()
        val bounds = Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            // Read both text and content-description — players often expose the title as a
            // content-desc, and it's the content-desc that survives in the a11y tree.
            for (s in listOfNotNull(n.text?.toString(), n.contentDescription?.toString())) {
                val secs = parseTime(s)
                if (secs != null) {
                    n.getBoundsInScreen(bounds)
                    // The scrubber's position/duration sit in the lower half of the screen;
                    // the wall clock lives at the top — exclude it by bounds.
                    if (bounds.top > screenH / 2) times.add(secs)
                } else if (s.length in 3..90) {
                    longTexts.add(s)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        if (times.size < 2) {
            Log.d(TAG, "scrape($pkg): only ${times.size} time(s); texts=${longTexts.take(8)}")
            return                               // overlay not showing time — keep last scrape
        }
        times.sort()
        val positionSec = times.first()
        val durationSec = times.last()
        if (durationSec <= 0 || positionSec > durationSec) return
        // Position/duration update whenever the scrubber is visible (even behind a menu).
        PlayerScrape.pkg = pkg
        PlayerScrape.positionMs = positionSec * 1000L
        PlayerScrape.durationMs = durationSec * 1000L
        PlayerScrape.scrapedAtElapsed = SystemClock.elapsedRealtime()
        // Take the title ONLY from a season/episode-tagged label, so a settings overlay
        // ("Quality profile", "Audio" …) can never overwrite the real episode; the last
        // good title persists. A movie has no S:E here and keeps its Watch-Next title.
        longTexts.firstOrNull { SEASON_EP.containsMatchIn(it) }?.let { titled ->
            val se = SEASON_EP.find(titled)
            PlayerScrape.title = titled
            PlayerScrape.season = se?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            PlayerScrape.episode = se?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
        }
        Log.i(TAG, "scrape($pkg): pos=${positionSec}s dur=${durationSec}s title=${PlayerScrape.title} s=${PlayerScrape.season} e=${PlayerScrape.episode}")
        PlayerScrape.onScrape?.invoke()
    }

    private fun parseTime(s: String): Long? {
        val m = TIME.matchEntire(s.trim()) ?: return null
        val g = m.groupValues
        val a = g[1].toLongOrNull() ?: return null
        val b = g[2].toLongOrNull() ?: return null
        val c = g.getOrNull(3)?.takeIf { it.isNotBlank() }?.toLongOrNull()
        return if (c != null) a * 3600 + b * 60 + c else a * 60 + b
    }

    fun doGlobal(action: String): Boolean {
        val dpad = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val id = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return false
            "dpad_up" -> if (dpad) GLOBAL_ACTION_DPAD_UP else return false
            "dpad_down" -> if (dpad) GLOBAL_ACTION_DPAD_DOWN else return false
            "dpad_left" -> if (dpad) GLOBAL_ACTION_DPAD_LEFT else return false
            "dpad_right" -> if (dpad) GLOBAL_ACTION_DPAD_RIGHT else return false
            "dpad_center", "ok", "center" -> if (dpad) GLOBAL_ACTION_DPAD_CENTER else return false
            else -> return false
        }
        return try { performGlobalAction(id) } catch (e: Exception) { false }
    }

    companion object {
        private const val TAG = "PlayerScrape"
        private const val SCRAPE_THROTTLE_MS = 900L
        private val TIME = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")
        private val SEASON_EP = Regex("""(?i)S(\d{1,3}):?\s*E(\d{1,3})""")
    }
}
