package nz.mcnabb.atvhabridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

/**
 * Holds the MediaProjection grant for the service's lifetime — no re-prompting the
 * TV-screen consent dialog per view. A VirtualDisplay+ImageReader (the actual capture
 * pipeline) is created only while >=1 screen.mjpeg client is connected (addClient/
 * removeClient, called by BridgeServer), and destroyed the moment the last one leaves.
 *
 * Deliberately PULL-based: the ImageReader is never given a listener. Frames are only
 * acquired+JPEG-encoded when latestJpeg() is called, which BridgeServer does at a fixed,
 * throttled rate while serving a stream (~2-3fps). This keeps the cost at literally zero
 * whenever nobody's actually watching, since the box has no CPU margin to spare — see
 * jellyfin-tv-box-cpu-starvation. If no new frame is ready since the last pull (static
 * content), the previous JPEG is re-served rather than encoding needlessly.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0
    @Volatile private var lastJpeg: ByteArray? = null
    @Volatile private var activeClients = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (resultCode == -1 || data == null) {
            Log.w(TAG, "started without a projection grant; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection = mgr.getMediaProjection(resultCode, data)
        instance = this
        Log.i(TAG, "MediaProjection granted; held idle until a client connects")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCaptureLocked()
        projection?.stop()
        projection = null
        if (instance === this) instance = null
    }

    /** BridgeServer calls this when a screen.mjpeg client connects. */
    @Synchronized
    fun addClient() {
        activeClients++
        if (virtualDisplay == null) startCaptureLocked()
    }

    /** BridgeServer calls this on client disconnect (including onException). */
    @Synchronized
    fun removeClient() {
        activeClients = (activeClients - 1).coerceAtLeast(0)
        if (activeClients == 0) stopCaptureLocked()
    }

    /** Pull-acquire the latest composited frame and JPEG-encode it. Only work done
     * here happens on the caller's thread — call off the request-handling thread
     * at your target frame interval, never in a tight loop. */
    fun latestJpeg(quality: Int = 55): ByteArray? {
        val reader = imageReader ?: return null
        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
        if (image == null) return lastJpeg // no new frame since last pull — reuse it
        try {
            val bmp = imageToBitmap(image, captureWidth, captureHeight)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bmp.recycle()
            return out.toByteArray().also { lastJpeg = it }
        } catch (e: Exception) {
            Log.w(TAG, "frame encode failed: ${e.message}")
            return lastJpeg
        } finally {
            image.close()
        }
    }

    private fun startCaptureLocked() {
        val proj = projection ?: return
        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.getRealMetrics(metrics)
        val aspect = if (metrics.widthPixels > 0) metrics.heightPixels.toFloat() / metrics.widthPixels else 9f / 16f
        val w = CAPTURE_WIDTH
        val h = (w * aspect).toInt().let { it - (it % 2) }.coerceAtLeast(2)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = proj.createVirtualDisplay(
            "atvhabridge-screen", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, captureHandler,
        )
        imageReader = reader
        captureWidth = w
        captureHeight = h
        Log.i(TAG, "capture started ${w}x$h")
    }

    @Synchronized
    private fun stopCaptureLocked() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        lastJpeg = null
        Log.i(TAG, "capture stopped (no clients)")
    }

    private fun imageToBitmap(image: Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPaddingPx = (rowStride - pixelStride * w) / pixelStride
        val padded = Bitmap.createBitmap(w + rowPaddingPx, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPaddingPx == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        return cropped
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.screen_notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_notif_title))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIF_ID = 42
        private const val CAPTURE_WIDTH = 640
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        @Volatile var instance: ScreenCaptureService? = null
            private set

        private val captureThread by lazy { HandlerThread("atvhabridge-capture").apply { start() } }
        private val captureHandler by lazy { Handler(captureThread.looper) }

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val intent = Intent(ctx, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun isActive(): Boolean = instance != null
    }
}
