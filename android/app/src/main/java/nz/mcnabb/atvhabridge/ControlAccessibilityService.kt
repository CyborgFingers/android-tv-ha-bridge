package nz.mcnabb.atvhabridge

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 *
 * It also says whether the user is *inside* the player at all: the scrubber stays in the
 * tree for as long as the player is up (Jellyfin only fades its overlay to alpha 0, it never
 * removes it) and is gone the moment the app's menus replace it — which is what tells paused
 * from browsing, the one thing audio alone cannot.
 */
class ControlAccessibilityService : AccessibilityService() {

    private var lastScrapeMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val trailingScrape = Runnable { scrapeNow() }

    override fun onServiceConnected() {
        Controls.accessibility = this
    }

    override fun onDestroy() {
        handler.removeCallbacks(trailingScrape)
        if (Controls.accessibility === this) Controls.accessibility = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != Controls.currentMediaPackage) return
        // At most one read per SCRAPE_THROTTLE_MS — but a burst always gets one more read
        // once it settles: backing out of the player is a burst whose first event can land
        // while the player is still in the tree, and dropping the rest would leave us
        // believing it's up until the next event (the overlay clock, up to a minute later).
        handler.removeCallbacks(trailingScrape)
        if (SystemClock.elapsedRealtime() - lastScrapeMs >= SCRAPE_THROTTLE_MS) scrapeNow()
        else handler.postDelayed(trailingScrape, SCRAPE_THROTTLE_MS)
    }

    override fun onInterrupt() {}

    /** Read the overlay now. The reader calls this the instant audio starts/stops so the
     * paused/playing decision sees the transport as it is, not as of the last event. */
    fun scrapeNow() {
        val pkg = Controls.currentMediaPackage ?: return
        lastScrapeMs = SystemClock.elapsedRealtime()
        runCatching { scrapePlayer(pkg) }
    }

    /** Walk the foreground window's nodes for the player overlay's time/title texts. */
    private fun scrapePlayer(pkg: String) {
        val root = rootInActiveWindow ?: return
        val screenH = resources.displayMetrics.heightPixels
        val times = ArrayList<Long>()            // media times (seconds) in the lower half
        val longTexts = ArrayList<String>()
        var transport: String? = null            // what the play/pause control says we're doing
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
                // The transport control is named for the action it OFFERS: "Pause" while
                // playing, "Play" while paused. Exact match only — "Playback speed" is a
                // neighbouring button, and a details page has a "Play" of its own (discarded
                // below along with everything else read without a scrubber).
                when (s.trim().lowercase()) {
                    "pause" -> transport = "playing"
                    "play" -> transport = "paused"
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        if (times.size < 2) {
            // No scrubber. In the window that last held it the player is gone (the app's
            // menus replaced it); in any other window this is only a popup over the player.
            if (PlayerScrape.inPlayer && root.windowId == PlayerScrape.windowId) {
                PlayerScrape.inPlayer = false
                PlayerScrape.transport = null
                Log.i(TAG, "scrape($pkg): player gone")
                PlayerScrape.onScrape?.invoke()
            } else {
                Log.d(TAG, "scrape($pkg): only ${times.size} time(s); texts=${longTexts.take(8)}")
            }
            return                               // keep the last scrape
        }
        times.sort()
        val positionSec = times.first()
        val durationSec = times.last()
        if (durationSec <= 0 || positionSec > durationSec) return
        // A new duration is a new item: drop the previous item's S:E label so a movie or
        // special (no S:E on its overlay) can't inherit the last episode's identity.
        // ponytail: two consecutive items of identical length keep the old label until an S:E shows.
        if (PlayerScrape.durationMs != durationSec * 1000L) {
            PlayerScrape.title = null; PlayerScrape.season = null; PlayerScrape.episode = null
        }
        // Position/duration update whenever the scrubber is visible (even behind a menu).
        PlayerScrape.pkg = pkg
        PlayerScrape.texts = longTexts
        PlayerScrape.positionMs = positionSec * 1000L
        PlayerScrape.durationMs = durationSec * 1000L
        PlayerScrape.scrapedAtElapsed = SystemClock.elapsedRealtime()
        PlayerScrape.inPlayer = true
        PlayerScrape.transport = transport
        PlayerScrape.windowId = root.windowId
        // Take the title ONLY from a season/episode-tagged label, so a settings overlay
        // ("Quality profile", "Audio" …) can never overwrite the real episode; the last
        // good title persists. A movie has no S:E here and keeps its Watch-Next title.
        longTexts.firstOrNull { SEASON_EP.containsMatchIn(it) }?.let { titled ->
            val se = SEASON_EP.find(titled)
            PlayerScrape.title = titled
            PlayerScrape.season = se?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            PlayerScrape.episode = se?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
        }
        Log.i(TAG, "scrape($pkg): pos=${positionSec}s dur=${durationSec}s transport=$transport title=${PlayerScrape.title} s=${PlayerScrape.season} e=${PlayerScrape.episode} texts=${longTexts.take(8)}")
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
