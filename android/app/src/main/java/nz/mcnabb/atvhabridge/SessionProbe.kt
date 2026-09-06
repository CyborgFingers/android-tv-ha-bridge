package nz.mcnabb.atvhabridge

import android.media.MediaMetadata
import android.media.session.MediaController

/**
 * One-line dump of everything a media session exposes (every metadata key,
 * playback state, actions, custom actions, extras). [MediaListenerService]
 * logs it whenever it changes so a single `adb logcat -s AdProbe` during a
 * YouTube ad shows exactly what the app publishes — the ground truth for
 * tuning [AdDetector].
 */
object SessionProbe {
    const val TAG = "AdProbe"

    private const val PREFIX = "android.media.metadata."
    private val LONG_KEYS = setOf(
        MediaMetadata.METADATA_KEY_DURATION, MediaMetadata.METADATA_KEY_YEAR,
        MediaMetadata.METADATA_KEY_TRACK_NUMBER, MediaMetadata.METADATA_KEY_NUM_TRACKS,
        MediaMetadata.METADATA_KEY_DISC_NUMBER, MediaMetadata.METADATA_KEY_BT_FOLDER_TYPE,
        METADATA_KEY_ADVERTISEMENT, "${PREFIX}DOWNLOAD_STATUS"
    )
    private val BITMAP_KEYS = setOf(
        MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON
    )
    private val RATING_KEYS = setOf(MediaMetadata.METADATA_KEY_USER_RATING, MediaMetadata.METADATA_KEY_RATING)

    /** Position is deliberately left out so a steadily playing item doesn't re-log every refresh. */
    fun describe(controller: MediaController): String {
        val md = controller.metadata
        val ps = controller.playbackState
        val keys = md?.keySet()?.sorted()?.joinToString(" ") { k ->
            val name = k.removePrefix(PREFIX)
            when (k) {
                in LONG_KEYS -> "$name=${md.getLong(k)}"
                in BITMAP_KEYS -> "$name=bitmap${md.getBitmap(k)?.let { "${it.width}x${it.height}" }}"
                in RATING_KEYS -> "$name=rating"
                else -> "$name='${md.getString(k)}'"
            }
        }
        val custom = ps?.customActions?.joinToString(",") { c ->
            "${c.action}/${c.name}" + (c.extras?.keySet()?.let { "[${it.joinToString(",")}]" } ?: "")
        }
        val extras = ps?.extras?.keySet()?.joinToString(",")
        return "${controller.packageName} state=${ps?.state} speed=${ps?.playbackSpeed} " +
            "actions=0x${ps?.actions?.toString(16)} custom=[$custom] extras=[$extras] md{$keys}"
    }
}
