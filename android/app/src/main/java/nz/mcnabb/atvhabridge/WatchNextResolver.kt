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
        val season: String? = null,
        val episode: String? = null,
        val durationMs: Long = 0L,
    )

    /** Best (most-recently-engaged) tile published by [pkg], or null. */
    fun resolve(cr: ContentResolver, pkg: String): Info? = resolveList(cr, pkg, 1).firstOrNull()

    /** Up to [limit] tiles, most-recently-engaged first: [pkg]'s own Watch-Next tiles,
     * else its preview-channel rows. A null [pkg] reads the launcher's cross-app
     * "continue watching" row (Watch-Next only — preview rows are per-app channels). */
    fun resolveList(cr: ContentResolver, pkg: String?, limit: Int): List<Info> {
        val tiles = query(cr, WATCH_NEXT, pkg).ifEmpty { if (pkg != null) query(cr, PREVIEW, pkg) else emptyList() }
        return tiles.take(limit)
    }

    private fun query(cr: ContentResolver, uri: Uri, pkg: String?): List<Info> {
        val cols = arrayOf(
            "package_name", "title", "episode_title", "poster_art_uri",
            "last_engagement_time_utc_millis", "last_playback_position_millis",
            "season_display_number", "episode_display_number", "duration_millis",
        )
        return try {
            cr.query(uri, cols, null, null, null)?.use { c ->
                val iPkg = c.getColumnIndex("package_name")
                val iTitle = c.getColumnIndex("title")
                val iEp = c.getColumnIndex("episode_title")
                val iArt = c.getColumnIndex("poster_art_uri")
                val iEng = c.getColumnIndex("last_engagement_time_utc_millis")
                val iPos = c.getColumnIndex("last_playback_position_millis")
                val iSeason = c.getColumnIndex("season_display_number")
                val iEpisode = c.getColumnIndex("episode_display_number")
                val iDur = c.getColumnIndex("duration_millis")
                val found = ArrayList<Pair<Long, Info>>()
                var total = 0
                while (c.moveToNext()) {
                    total++
                    if (pkg != null && c.str(iPkg) != pkg) continue
                    val eng = if (iEng >= 0) c.getLong(iEng) else 0L
                    found += eng to Info(
                        c.str(iTitle), c.str(iEp), c.str(iArt),
                        if (iPos >= 0) c.getLong(iPos) else 0L,
                        c.str(iSeason)?.takeIf { it.isNotBlank() },
                        c.str(iEpisode)?.takeIf { it.isNotBlank() },
                        durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                    )
                }
                val sorted = found.sortedByDescending { it.first }.map { it.second }
                Log.i(TAG, "${uri.lastPathSegment}: total=$total match($pkg)=${sorted.size} best=${sorted.firstOrNull()}")
                sorted
            }.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "query ${uri.lastPathSegment} failed: ${e.message}")
            emptyList()
        }
    }

    private fun Cursor.str(i: Int): String? = if (i >= 0) getString(i) else null
}
