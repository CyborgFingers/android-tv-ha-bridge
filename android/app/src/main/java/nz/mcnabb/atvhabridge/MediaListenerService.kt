package nz.mcnabb.atvhabridge

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import java.util.concurrent.Executors

/** MediaMetadataCompat.METADATA_KEY_ADVERTISEMENT — support-lib key (no framework
 * constant); a non-zero long while the current item is an ad. */
const val METADATA_KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT"

/**
 * The heart of the bridge. Being a bound NotificationListenerService is what grants
 * [MediaSessionManager] access without ADB. It reads the active media session (+ the
 * Watch-Next tile for apps that publish no metadata), and publishes the result into
 * [BridgeState], which the [BridgeServer] serves to Home Assistant. It also hosts the
 * server + mDNS advertisement for their whole lifetime.
 */
class MediaListenerService : NotificationListenerService() {

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var artResolver: ArtResolver
    private lateinit var pairing: Pairing
    private var server: BridgeServer? = null
    private var discovery: Discovery? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null

    private val adDetector = AdDetector()
    private var lastProbe: String? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() {
            currentController = null
            refresh()
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> selectController(controllers) }

    private val heartbeat = object : Runnable {
        override fun run() {
            BridgeState.sensors = DeviceSensors.collect(this@MediaListenerService)
            refresh()
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private val adTick = Runnable { refresh() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        try {
            pairing = Pairing(this)
            Controls.appContext = applicationContext
            BridgeState.device = BridgeState.Device(id = pairing.deviceId, name = pairing.defaultName)
            BridgeState.sensors = DeviceSensors.collect(this)
            artResolver = ArtResolver(contentResolver)
            // TLS: self-signed cert from the AndroidKeyStore; HA pins its fingerprint.
            val tlsFactory = TlsProvider.serverSocketFactory()
            BridgeState.fingerprint = TlsProvider.fingerprintHex()
            // Bind the first free port from Config.PORT upward (robust if another app
            // holds it). The actual port is advertised over mDNS + shown in the QR.
            var bound = Config.PORT
            for (p in Config.PORT..(Config.PORT + 4)) {
                try {
                    val s = BridgeServer(p, pairing) { which ->
                        if (which == "current") artResolver.bitmap() else null
                    }
                    s.makeSecure(tlsFactory, null)
                    s.start()
                    server = s
                    bound = p
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "port $p busy, trying next: ${e.message}")
                }
            }
            BridgeState.port = bound
            discovery = Discovery(this, bound, pairing).also { it.start() }

            val manager = getSystemService(MediaSessionManager::class.java)
            mediaSessionManager = manager
            val component = ComponentName(this, MediaListenerService::class.java)
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            selectController(manager.getActiveSessions(component))

            mainHandler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS)
        } catch (e: Exception) {
            Log.e(TAG, "onListenerConnected failed: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            currentController?.unregisterCallback(controllerCallback)
            currentController = null
            mainHandler.removeCallbacks(heartbeat)
            mainHandler.removeCallbacks(adTick)
            discovery?.stop(); discovery = null
            server?.stop(); server = null
            requestRebind(ComponentName(this, MediaListenerService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "onListenerDisconnected failed: ${e.message}")
        }
    }

    private fun selectController(controllers: List<MediaController>?) {
        val next = pickController(controllers.orEmpty())
        if (next?.sessionToken == currentController?.sessionToken) {
            refresh()
            return
        }
        currentController?.unregisterCallback(controllerCallback)
        currentController = next
        Controls.mediaController = next
        next?.registerCallback(controllerCallback, mainHandler)
        refresh()
    }

    private fun pickController(controllers: List<MediaController>): MediaController? {
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { return it }
        return controllers.firstOrNull { it.metadata != null }
    }

    private fun refresh() {
        val controller = currentController
        bgExecutor.execute {
            try {
                if (controller != null) {
                    artResolver.update(controller.metadata)
                    val snap = buildSnapshot(controller)
                    artResolver.updatePoster(snap.posterUrl)
                    val artUrl = if (artResolver.bitmap() != null) "/art.jpg?v=${artResolver.version}" else null
                    probe(controller)
                    scheduleAdTick(snap.ad)
                    BridgeState.publish(snap, artUrl, null, null)
                } else {
                    artResolver.update(null)
                    BridgeState.publish(null, null, null, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "refresh failed: ${e.message}")
            }
        }
    }

    private fun buildSnapshot(controller: MediaController): NowPlayingSnapshot {
        val metadata = controller.metadata
        val ps = controller.playbackState
        val mediaTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val adFlag = (metadata?.getLong(METADATA_KEY_ADVERTISEMENT) ?: 0L) != 0L

        // Apps like TVNZ+/ThreeNow publish a session with no title/art. Recover the
        // show + episode + poster from the Android TV Watch-Next tile.
        var title = mediaTitle
        var series: String? = null
        var episode: String? = null
        var posterUrl: String? = null
        if (mediaTitle.isNullOrBlank()) {
            WatchNextResolver.resolve(contentResolver, controller.packageName)?.let { wn ->
                val (s, e) = orderSeriesEpisode(wn.title, wn.episodeTitle)
                series = s; episode = e
                title = listOfNotNull(s, e).joinToString(" — ").ifBlank { null }
                posterUrl = wn.posterUri?.takeIf { it.startsWith("http") }
            }
        }

        // Progress fix: playbackState.position is measured at ps.lastPositionUpdateTime
        // (elapsedRealtime). Project it to *now* so a consumer that extrapolates from a
        // wall-clock timestamp stays aligned with what's actually on screen.
        val rawPos = ps?.position ?: 0L
        val speed = ps?.playbackSpeed ?: 1f
        val livePos = if (ps?.state == PlaybackState.STATE_PLAYING && ps.lastPositionUpdateTime > 0) {
            rawPos + ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * speed).toLong()
        } else rawPos

        return NowPlayingSnapshot(
            title = title,
            seriesTitle = series,
            episodeTitle = episode,
            artist = artist,
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = durationMs,
            positionMs = livePos.coerceAtLeast(0),
            positionUpdatedAt = System.currentTimeMillis(),
            state = playbackStateToString(ps?.state),
            appPackage = controller.packageName,
            appName = resolveAppName(controller.packageName),
            ad = adDetector.update(controller.packageName, mediaTitle, durationMs, adFlag),
            posterUrl = posterUrl,
        )
    }

    private fun resolveAppName(pkg: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            friendlyAppName(pkg)
        }

    private val SEASON_EP = Regex("(?i)(season|episode|\\bS\\d+\\b|\\bE\\d+\\b)")

    /** Split the two Watch-Next label columns into (series, episode) regardless of which
     * column each app used (TVNZ+ puts the show in episode_title + the season/ep in title). */
    private fun orderSeriesEpisode(a: String?, b: String?): Pair<String?, String?> {
        val x = a?.trim()?.takeIf { it.isNotBlank() }
        val y = b?.trim()?.takeIf { it.isNotBlank() }
        if (x == null || y == null) return (x ?: y) to null
        return if (SEASON_EP.containsMatchIn(x) && !SEASON_EP.containsMatchIn(y)) y to x else x to y
    }

    private fun scheduleAdTick(ad: AdState) {
        mainHandler.removeCallbacks(adTick)
        if (ad.isAd && !ad.skippable) {
            mainHandler.postDelayed(adTick, ad.skipInSec * 1000L + AD_TICK_SLACK_MS)
        }
    }

    private fun probe(controller: MediaController) {
        runCatching {
            val line = SessionProbe.describe(controller)
            if (line != lastProbe) {
                lastProbe = line
                Log.i(SessionProbe.TAG, "$line pos=${controller.playbackState?.position}")
            }
        }.onFailure { Log.w(SessionProbe.TAG, "probe failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "MediaListenerService"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val AD_TICK_SLACK_MS = 200L

        @Volatile
        var isConnected = false
            private set
    }
}
