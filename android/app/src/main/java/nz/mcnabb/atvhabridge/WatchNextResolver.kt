package nz.mcnabb.atvhabridge

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * Reads the Android TV program providers — `watch_next_program` (the launcher's
 * "Continue watching / Up next" row) and `preview_program` (an app's own home-screen
 * rows) — to recover a title + poster for apps that publish no MediaSession metadata
 * (e.g. TVNZ+, ThreeNow). Requires READ_TV_LISTINGS (granted via `pm grant`).
 *
 * ponytail: reads raw provider columns by name so we don't pull in androidx.tvprovider.
 */
object WatchNextResolver {
    private const val TAG = "WatchNext"
    private val WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program")
    private val PREVIEW = Uri.parse("content://android.media.tv/preview_program")

    data class Info(
        val title: String?,
        val episodeTitle: String?,
        val posterUri: String?,
        val positionMs: Long,
    )

    /** Best (most-recently-engaged) tile published by [pkg], or null. */
    fun resolve(cr: ContentResolver, pkg: String): Info? =
        query(cr, WATCH_NEXT, pkg) ?: query(cr, PREVIEW, pkg)

    private fun query(cr: ContentResolver, uri: Uri, pkg: String): Info? {
        val cols = arrayOf(
            "package_name", "title", "episode_title", "poster_art_uri",
            "last_engagement_time_utc_millis", "last_playback_position_millis",
        )
        return try {
            cr.query(uri, cols, null, null, null)?.use { c ->
                val iPkg = c.getColumnIndex("package_name")
                val iTitle = c.getColumnIndex("title")
                val iEp = c.getColumnIndex("episode_title")
                val iArt = c.getColumnIndex("poster_art_uri")
                val iEng = c.getColumnIndex("last_engagement_time_utc_millis")
                val iPos = c.getColumnIndex("last_playback_position_millis")
                var best: Info? = null
                var bestTime = Long.MIN_VALUE
                var total = 0
                while (c.moveToNext()) {
                    total++
                    if (c.str(iPkg) != pkg) continue
                    val eng = if (iEng >= 0) c.getLong(iEng) else 0L
                    if (eng >= bestTime) {
                        bestTime = eng
                        best = Info(c.str(iTitle), c.str(iEp), c.str(iArt), if (iPos >= 0) c.getLong(iPos) else 0L)
                    }
                }
                Log.i(TAG, "${uri.lastPathSegment}: total=$total match($pkg)=$best")
                best
            }
        } catch (e: Exception) {
            Log.e(TAG, "query ${uri.lastPathSegment} failed: ${e.message}")
            null
        }
    }

    private fun Cursor.str(i: Int): String? = if (i >= 0) getString(i) else null
}
