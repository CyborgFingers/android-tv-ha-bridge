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
    private var lastMetadata: MediaMetadata? = null
    private var lastPosterUrl: String? = null

    /** MediaSession art wins; the Watch-Next poster is the fallback for apps that
     * publish no album art (TVNZ+, ThreeNow). Served on the LAN so the remote never
     * has to reach the external CDN itself. */
    fun bitmap(): Bitmap? = currentArt ?: posterArt

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
            lastMetadata = null
            if (currentArt != null) {
                currentArt = null
                version++
            }
            return
        }
        if (metadata === lastMetadata) return
        lastMetadata = metadata
        val resolved = try {
            resolve(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "art resolve failed: ${e.message}")
            null
        }
        // ponytail: a metadata-identity change bumps the version even if the
        // resolved art is byte-identical to the previous one. A content hash
        // would be exact but isn't worth it just to avoid an occasional extra
        // HA image refetch.
        if (resolved != null || currentArt != null) {
            currentArt = resolved
            version++
        }
    }

    private fun resolve(metadata: MediaMetadata): Bitmap? {
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return it }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return it }

        val uriString = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: return null

        return fetchFromUri(uriString)
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
    }
}
