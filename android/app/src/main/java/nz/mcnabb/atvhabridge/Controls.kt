package nz.mcnabb.atvhabridge

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
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

    /** Package of the app that owns the current media session — the accessibility
     * scraper only reads the overlay while this app is foreground. */
    @Volatile var currentMediaPackage: String? = null

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
}
