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

    // A composite signature of everything that could distinguish one track's art
    // from the next, since no single field is trustworthy for every app: YouTube's
    // Cobalt TV client (confirmed via logcat) reuses the same MediaMetadata
    // container across videos, leaves TITLE/DISPLAY_TITLE empty, publishes no art
    // URI, AND (confirmed the hard way — a reference-identity check on the Bitmap
    // alone still missed real changes) appears to reuse/overwrite the same Bitmap
    // object rather than allocating a fresh one per track. DURATION reliably does
    // differ per video (verified against real playback) and a downscaled pixel
    // fingerprint catches a genuine content change even when the object itself
    // didn't. If ANY component differs, treat it as changed — false positives
    // just cost one extra (cheap, local) re-resolve; false negatives are the bug
    // this whole thing exists to prevent.
    private var lastArtSignature: String? = null
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
            lastArtSignature = null
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
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val fingerprint = bitmap?.let { runCatching { contentFingerprint(it) }.getOrNull() }
        val signature = "$title|$uri|$duration|$fingerprint"

        if (signature == lastArtSignature) return
        lastArtSignature = signature

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

    /** Cheap, genuinely content-based fingerprint: downscale to a tiny fixed size
     * (near-free even for a large source bitmap) and hash those pixels. Needed
     * because some apps reuse/overwrite the same Bitmap object per track rather
     * than allocating a fresh one, which defeats a reference-identity check even
     * though the pixels themselves did change. */
    private fun contentFingerprint(bitmap: Bitmap): Long {
        val w = FINGERPRINT_SIZE
        val h = FINGERPRINT_SIZE
        val small = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        var hash = 1125899906842597L
        for (p in pixels) hash = hash * 31 + p
        return hash
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
        private const val FINGERPRINT_SIZE = 8
    }
}
