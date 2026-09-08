package nz.mcnabb.atvhabridge

import android.content.ContentResolver
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log

/** `watch_next_type` of a tile an app marks as the NEXT episode (vs CONTINUE = a resume point). */
const val WATCH_NEXT_TYPE_NEXT = TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT

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
    val WATCH_NEXT: Uri = Uri.parse("content://android.media.tv/watch_next_program")
    private val PREVIEW = Uri.parse("content://android.media.tv/preview_program")

    data class Info(
        val title: String?,
        val episodeTitle: String?,
        val posterUri: String?,
        val positionMs: Long,
        val season: String? = null,
        val episode: String? = null,
        val durationMs: Long = 0L,
        /** `watch_next_type` (0 CONTINUE · 1 NEXT · 2 NEW · 3 WATCHLIST); -1 for preview rows, which have none. */
        val type: Int = -1,
        /** The tile's own launch intent (`intent_uri`) — what the launcher fires when the tile is picked. */
        val intentUri: String? = null,
    ) {
        val isNext: Boolean get() = type == WATCH_NEXT_TYPE_NEXT
    }

    /** Best (most-recently-engaged) tile published by [pkg], or null — the item being resumed. */
    fun resolve(cr: ContentResolver, pkg: String): Info? = tiles(cr, pkg).firstOrNull()

    /** Up to [limit] picks: [pkg]'s own Watch-Next tiles, else its preview-channel rows, ranked
     * by [rankUpNext] minus whatever [skip] rejects (the caller drops what's playing). A null
     * [pkg] reads the launcher's cross-app "continue watching" row (Watch-Next only —
     * preview rows are per-app channels). */
    fun resolveList(cr: ContentResolver, pkg: String?, limit: Int, skip: (Info) -> Boolean = { false }): List<Info> =
        rankUpNext(tiles(cr, pkg), skip).take(limit)

    private fun tiles(cr: ContentResolver, pkg: String?): List<Info> =
        query(cr, WATCH_NEXT, pkg).ifEmpty { if (pkg != null) query(cr, PREVIEW, pkg) else emptyList() }

    private val lastLog = HashMap<Uri, String>()

    /** Rows most-recently-engaged first (the provider's own notion of "what you were on"). */
    private fun query(cr: ContentResolver, uri: Uri, pkg: String?): List<Info> {
        val cols = arrayOf(
            "package_name", "title", "episode_title", "poster_art_uri",
            "last_engagement_time_utc_millis", "last_playback_position_millis",
            "season_display_number", "episode_display_number", "duration_millis", "intent_uri",
            // Only the Watch-Next table has a type column; asking preview_program for it throws.
            *(if (uri == WATCH_NEXT) arrayOf("watch_next_type") else emptyArray()),
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
                val iIntent = c.getColumnIndex("intent_uri")
                val iType = c.getColumnIndex("watch_next_type")
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
                        type = if (iType >= 0 && !c.isNull(iType)) c.getInt(iType) else -1,
                        intentUri = c.str(iIntent)?.takeIf { it.isNotBlank() },
                    )
                }
                val sorted = found.sortedByDescending { it.first }.map { it.second }
                // One line per distinct row set (not per refresh): type/season/episode of every
                // matched tile, so `logcat -s WatchNext` shows what the provider really holds.
                val line = "${uri.lastPathSegment}: total=$total match($pkg)=${sorted.size} " +
                    sorted.joinToString(" · ", "[", "]") { "${typeLabel(it.type)} ${it.title} S${it.season}E${it.episode}" }
                if (lastLog[uri] != line) {
                    lastLog[uri] = line
                    Log.i(TAG, line)
                }
                sorted
            }.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "query ${uri.lastPathSegment} failed: ${e.message}")
            emptyList()
        }
    }

    private fun typeLabel(type: Int) = when (type) { 0 -> "C"; 1 -> "N"; 2 -> "new"; 3 -> "wl"; else -> "?" }

    private fun Cursor.str(i: Int): String? = if (i >= 0) getString(i) else null
}

/** The picks order: NEXT-episode tiles ahead of resume points, the provider's recency order
 * kept within each group (stable sort), minus the tiles [skip] rejects. Pure — unit-tested. */
fun rankUpNext(tiles: List<WatchNextResolver.Info>, skip: (WatchNextResolver.Info) -> Boolean): List<WatchNextResolver.Info> =
    tiles.filterNot(skip).sortedByDescending { it.isNext }
