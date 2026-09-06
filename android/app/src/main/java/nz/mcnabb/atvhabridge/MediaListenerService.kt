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
            // Re-scan active sessions (not just refresh the current one) so a session that
            // came up without a change event — or after a lost attach — still gets picked up.
            val m = mediaSessionManager
            if (m != null) {
                runCatching {
                    selectController(
                        m.getActiveSessions(ComponentName(this@MediaListenerService, MediaListenerService::class.java))
                    )
                }.onFailure { refresh() }
            } else refresh()
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
            var bound = 0
            for (p in Config.PORT..(Config.PORT + 4)) {
                try {
                    val s = BridgeServer(p, pairing) { which ->
                        if (which == "current") artResolver.bitmap() else null
                    }
                    s.makeSecure(tlsFactory, null)
                    // timeout 0 = no socket read timeout: NanoHTTPD's default 5s would
                    // close an idle WebSocket between the client's keepalive pings. A
                    // real disconnect is still detected via EOF. daemon=true as default.
                    s.start(0, true)
                    server = s
                    bound = p
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "port $p busy, trying next: ${e.message}")
                }
            }
            // Only advertise a port that actually bound — never a phantom default, or the
            // mDNS advert points clients at a dead port (and clients can't self-heal).
            if (bound > 0) {
                BridgeState.port = bound
                discovery = Discovery(this, bound, pairing).also { it.start() }
            } else {
                Log.e(TAG, "no port bound in ${Config.PORT}..${Config.PORT + 4}; not advertising")
            }

            mediaSessionManager = getSystemService(MediaSessionManager::class.java)
            attachMediaSessions(attempt = 0)

            mainHandler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS)
        } catch (e: Exception) {
            Log.e(TAG, "onListenerConnected failed: ${e.message}")
        }
    }

    /** Wire up media-session reading, retrying through the transient "Missing permission
     * to control media" SecurityException the system throws for a few hundred ms right
     * after the notification listener (re)binds. Without the retry the first
     * getActiveSessions() throws once, selectController never runs, and a stable session
     * (Jellyfin fires no change event once it's up) is never picked up — so nothing ever
     * shows as now-playing. */
    private fun attachMediaSessions(attempt: Int) {
        val manager = mediaSessionManager ?: return
        val component = ComponentName(this, MediaListenerService::class.java)
        try {
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            selectController(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            if (attempt < MEDIA_ATTACH_MAX_RETRIES) {
                mainHandler.postDelayed({ attachMediaSessions(attempt + 1) }, MEDIA_ATTACH_RETRY_MS)
            } else {
                Log.e(TAG, "media session attach failed after $attempt retries: ${e.message}")
            }
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
            // Stop each independently so a throw from one (NanoHTTPD.stop can) doesn't
            // skip the rest — especially requestRebind, without which we may not rebind.
            runCatching { discovery?.stop() }; discovery = null
            runCatching { server?.stop() }; server = null
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
        controllers.firstOrNull { it.metadata != null }?.let { return it }
        // Last resort: an app that's active but publishes neither a play-state nor
        // metadata — Jellyfin's video player sits at STATE_NONE. Take it so buildSnapshot
        // can still name the app and recover the title/poster from the Watch-Next tile;
        // skip clearly-dead sessions so a stopped/errored one isn't shown as playing.
        return controllers.firstOrNull {
            val s = it.playbackState?.state
            s != PlaybackState.STATE_STOPPED && s != PlaybackState.STATE_ERROR
        }
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
        val metaDurationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val adFlag = (metadata?.getLong(METADATA_KEY_ADVERTISEMENT) ?: 0L) != 0L
        var wnPositionMs = 0L
        var wnDurationMs = 0L

        // Apps like TVNZ+/ThreeNow publish a session with no title/art. Recover the
        // show + episode + poster from the Android TV Watch-Next tile.
        var title = mediaTitle
        var series: String? = null
        var episode: String? = null
        var season: String? = null
        var episodeNum: String? = null
        var posterUrl: String? = null
        if (mediaTitle.isNullOrBlank()) {
            WatchNextResolver.resolve(contentResolver, controller.packageName)?.let { wn ->
                val (s, e) = orderSeriesEpisode(wn.title, wn.episodeTitle)
                series = s; episode = e
                season = wn.season; episodeNum = wn.episode
                title = listOfNotNull(s, e).joinToString(" — ").ifBlank { null }
                posterUrl = wn.posterUri // http OR content:// — ArtResolver fetches both
                wnPositionMs = wn.positionMs
                wnDurationMs = wn.durationMs
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

        // Apps with an empty session (Jellyfin) report no position/duration — fall back to
        // the Watch-Next tile's saved position + episode duration so the card shows progress.
        val durationMs = if (metaDurationMs > 0) metaDurationMs else wnDurationMs
        val positionMs = (if (livePos > 0) livePos else wnPositionMs).coerceAtLeast(0)

        return NowPlayingSnapshot(
            title = title,
            seriesTitle = series,
            episodeTitle = episode,
            season = season,
            episode = episodeNum,
            artist = artist,
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = durationMs,
            positionMs = positionMs,
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
        // Retry window for the "Missing permission to control media" race right after (re)bind.
        private const val MEDIA_ATTACH_RETRY_MS = 800L
        private const val MEDIA_ATTACH_MAX_RETRIES = 8

        @Volatile
        var isConnected = false
            private set
    }
}
