package nz.mcnabb.atvhabridge

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject

/**
 * Executes control commands natively — no ADB. Media transport goes through the
 * active [MediaController] (we already have MediaSession access via the notification
 * listener); navigation goes through [ControlAccessibilityService]; app launch uses
 * a launcher intent. The [BridgeServer] calls [handle] for POST /api/command.
 */
object Controls {
    @Volatile var mediaController: MediaController? = null
    @Volatile var accessibility: ControlAccessibilityService? = null
    @Volatile var appContext: Context? = null

    /** Package whose player overlay the accessibility scraper reads (only while it is
     * foreground): the app owning the current media session — or null when that session
     * names its own item, in which case nothing is scraped. */
    @Volatile var currentMediaPackage: String? = null

    /** Launch intents of the last published up-next picks, by index — the only intents
     * `play_next` ever fires. They come from the TV provider (each app's own tile), never
     * from a client: the API takes an index, not an intent. */
    @Volatile var upNextIntents: List<String?> = emptyList()

    /** true if the command was dispatched. */
    fun handle(action: String, params: JSONObject): Boolean = when (action) {
        "play" -> transport { it.play() }
        "pause" -> transport { it.pause() }
        "playpause" -> togglePlayPause()
        "stop" -> transport { it.stop() }
        "next" -> transport { it.skipToNext() }
        "previous" -> transport { it.skipToPrevious() }
        "seek" -> transport { it.seekTo(params.optLong("position") * 1000) }
        "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center",
        "ok", "center", "back", "home",
        -> accessibility?.doGlobal(action) ?: false
        "sleep", "screen_off" -> accessibility?.doGlobal("lock_screen") ?: false
        "wake", "screen_on" -> wake()
        "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
        "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
        "volume_mute" -> adjustVolume(AudioManager.ADJUST_MUTE)
        "volume_unmute" -> adjustVolume(AudioManager.ADJUST_UNMUTE)
        "volume_set" -> setVolume(params.optInt("level", -1))
        "launch" -> launch(params.optString("package"))
        "play_next" -> playNext(params.optInt("index", -1))
        else -> false
    }

    private fun audio(): AudioManager? = appContext?.getSystemService(AudioManager::class.java)

    private fun adjustVolume(direction: Int): Boolean {
        val am = audio() ?: return false
        return try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI); true
        } catch (e: Exception) { false }
    }

    private fun setVolume(level: Int): Boolean {
        if (level < 0) return false
        val am = audio() ?: return false
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max / 100).coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) { false }
    }

    @Suppress("DEPRECATION")
    private fun wake(): Boolean {
        val pm = appContext?.getSystemService(PowerManager::class.java) ?: return false
        return try {
            val flags = PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE
            pm.newWakeLock(flags, "atvhabridge:wake").acquire(3000) // auto-released after the timeout
            true
        } catch (e: Exception) { false }
    }

    private inline fun transport(block: (MediaController.TransportControls) -> Unit): Boolean {
        val c = mediaController ?: return false
        return try { block(c.transportControls); true } catch (e: Exception) { false }
    }

    private fun togglePlayPause(): Boolean {
        val playing = mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
        return if (playing) transport { it.pause() } else transport { it.play() }
    }

    private fun launch(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val ctx = appContext ?: return false
        val intent = (ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: ctx.packageManager.getLaunchIntentForPackage(pkg)) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(intent); true } catch (e: Exception) { false }
    }

    /** Play up-next pick [index] the way the launcher would: fire the tile's own intent. If
     * the app that owns the tile is still inside its player, back out of it first and fire
     * a moment later — a deep link that lands on a live player is dropped by some apps
     * (Jellyfin ends up on Home), while the same link from any normal screen just works.
     * ponytail: "in the player" is the last overlay read; a stale one costs a Back press. */
    private fun playNext(index: Int): Boolean {
        val uri = upNextIntents.getOrNull(index) ?: return false
        val ctx = appContext ?: return false
        val intent = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (e: Exception) { return false }
        val fire = { runCatching { ctx.startActivity(intent) }.isSuccess }
        val target = intent.component?.packageName ?: intent.`package`
        val leavePlayer = target != null && inPlayer(target) && accessibility?.doGlobal("back") == true
        if (!leavePlayer) return fire()
        Handler(Looper.getMainLooper()).postDelayed({ fire() }, LEAVE_PLAYER_MS)
        return true
    }

    /** true while [pkg]'s player is up: its overlay was last read with the scrubber in it,
     * or its own session — one that names its item is never scraped — reports live transport. */
    private fun inPlayer(pkg: String): Boolean {
        if (pkg == PlayerScrape.pkg && PlayerScrape.inPlayer) return true
        val c = mediaController?.takeIf { it.packageName == pkg } ?: return false
        return c.playbackState?.state in LIVE_STATES
    }

    private val LIVE_STATES = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING)

    // How long the app gets to close its player before the tile's intent is fired.
    private const val LEAVE_PLAYER_MS = 900L
}
