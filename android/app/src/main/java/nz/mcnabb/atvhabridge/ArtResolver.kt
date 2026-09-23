package nz.mcnabb.atvhabridge

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves and caches media art from a MediaMetadata (embedded Bitmap, or a
 * fetched URI). [version] increments whenever the art changes so callers can
 * cache-bust a URL. Not thread-safe across concurrent calls to [update], but
 * [bitmap]/[version] reads are safe from any thread.
 */
class ArtResolver(private val contentResolver: ContentResolver) {
    @Volatile private var currentArt: Bitmap? = null
    @Volatile private var posterArt: Bitmap? = null
    @Volatile var version: Int = 0
        private set

    // Two independent "did this actually change" signals, since neither alone
    // covers every app: YouTube's Cobalt TV client (confirmed via logcat) reuses
    // the same MediaMetadata container across videos AND leaves TITLE/DISPLAY_TITLE
    // empty and no art URI — only a fresh embedded Bitmap object distinguishes one
    // video's art from the next, so that's compared by reference. An app that uses
    // a URI instead (e.g. Jellyfin) gets a real per-item string there instead.
    private var lastArtBitmap: Bitmap? = null
    private var lastArtUri: String? = null
    private var lastPosterUrl: String? = null

    /** Up-next posters by list index (served as /art_next_<i>.jpg). Replaced atomically
     * by [updateUpNext]; the URL cache behind it is touched only on that thread. */
    @Volatile private var upNextArt: List<Bitmap?> = emptyList()
    private val upNextCache = HashMap<String, Bitmap?>() // url → bitmap (null = fetch failed)

    /** MediaSession art wins; the Watch-Next poster is the fallback for apps that
     * publish no album art (TVNZ+, ThreeNow). Served on the LAN so the remote never
     * has to reach the external CDN itself. */
    fun bitmap(): Bitmap? = currentArt ?: posterArt

    /** Poster for up-next pick [i], or null. Safe from any thread. */
    fun upNextBitmap(i: Int): Bitmap? = upNextArt.getOrNull(i)

    /** Resolve the up-next posters (http/content URIs), cached by URL so only a new
     * poster hits the network — a failed fetch is cached too, so a dead link isn't
     * retried on every refresh. Off-main. */
    fun updateUpNext(urls: List<String?>) {
        upNextCache.keys.retainAll(urls.filterNotNull().toSet())
        upNextArt = urls.map { url ->
            url?.let {
                if (!upNextCache.containsKey(it)) {
                    upNextCache[it] = try {
                        fetchFromUri(it)?.let(::thumbnail)
                    } catch (e: Exception) {
                        Log.e(TAG, "up-next poster fetch failed: ${e.message}")
                        null
                    }
                }
                upNextCache[it]
            }
        }
    }

    /** Up-next posters are thumbnails on a remote; cap them so six don't cost 20 MB of heap. */
    private fun thumbnail(b: Bitmap): Bitmap =
        if (b.width <= THUMB_MAX_WIDTH) b
        else Bitmap.createScaledBitmap(b, THUMB_MAX_WIDTH, b.height * THUMB_MAX_WIDTH / b.width, true)

    /** Fetch a Watch-Next poster (http/content URI) into the fallback slot. Cached by
     * URL so it only hits the network when the poster actually changes. Off-main. */
    fun updatePoster(url: String?) {
        if (url == lastPosterUrl) return
        lastPosterUrl = url
        posterArt = url?.let {
            try {
                fetchFromUri(it)
            } catch (e: Exception) {
                Log.e(TAG, "poster fetch failed: ${e.message}")
                null
            }
        }
        version++
    }

    /** Must be called off the main thread: may perform network/content I/O. */
    fun update(metadata: MediaMetadata?) {
        if (metadata == null) {
            lastArtBitmap = null
            lastArtUri = null
            if (currentArt != null) {
                currentArt = null
                version++
            }
            return
        }

        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)

        // NOT metadata object identity: YouTube's Cobalt TV client (confirmed via
        // logcat) redelivers callbacks reusing/mutating the same MediaMetadata
        // container across genuinely different videos, with an EMPTY title/no art
        // URI every time — so any comparison built from those fields is constant
        // and this would never re-resolve after the first video. Only the embedded
        // Bitmap object itself reliably differs per video for that client, so when
        // one is present it's compared by reference; an app that uses a URI instead
        // (e.g. Jellyfin) gets a real per-item string there and is compared on that.
        val changed = if (bitmap != null) bitmap !== lastArtBitmap else uri != lastArtUri
        if (!changed) return
        lastArtBitmap = bitmap
        lastArtUri = uri

        val resolved = try {
            bitmap ?: uri?.let(::fetchFromUri)
        } catch (e: Exception) {
            Log.e(TAG, "art resolve failed: ${e.message}")
            null
        }
        if (resolved != null || currentArt != null) {
            currentArt = resolved
            version++
        }
    }

    private fun fetchFromUri(uriString: String): Bitmap? {
        val uri = Uri.parse(uriString)
        val bytes = if (uri.scheme == "content") {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } else {
            val conn = URL(uriString).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            try {
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
        return bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    companion object {
        private const val TAG = "ArtResolver"
        private const val THUMB_MAX_WIDTH = 640
    }
}
