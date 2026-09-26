package nz.mcnabb.atvhabridge

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Gets the screen-capture grant when a viewer asks for the screen and capture isn't running (it
 * stops itself once nobody's watching, so the TV's screen-capture indicator only shows while
 * someone is). When the app holds appop PROJECT_MEDIA (set once over adb), SystemUI's consent
 * activity returns RESULT_OK at once and shows nothing (AOSP 14 MediaProjectionPermissionActivity:
 * hasProjectionPermission → auto-grant), so this invisible activity just bounces through it.
 * Starting it from the background is allowed by the bridge's enabled accessibility service (or
 * appop SYSTEM_ALERT_WINDOW). Without PROJECT_MEDIA it never launches — the onboarding button stays
 * the way in, and ScreenCaptureService keeps that grant rather than letting it go when idle.
 */
class ScreenGrantActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Started here, while this activity is foreground (Android 14 requires that for a
        // mediaProjection foreground service).
        if (requestCode == REQ_SCREEN && resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, resultCode, data)
        } else {
            Log.w(TAG, "auto grant refused (result $resultCode)")
        }
        finish()
    }

    companion object {
        private const val TAG = "ScreenGrantActivity"
        private const val REQ_SCREEN = 1
        private const val OP_PROJECT_MEDIA = "android:project_media"

        /** Set by MediaListenerService so BridgeServer, which has no Context, can ask. */
        @Volatile var appContext: Context? = null

        // A phone polls about twice a second while capture starts: one bounce per gap, not one per poll.
        private const val REQUEST_GAP_MS = 20_000L
        private var lastAttemptMs = 0L

        /** A viewer asked for the screen: request the grant if capture is down, the appop allows
         * it, and the TV is awake. */
        @Synchronized
        fun requestForViewer() {
            val ctx = appContext ?: return
            if (ScreenCaptureService.isActive() || !canAutoGrant(ctx)) return
            val now = SystemClock.elapsedRealtime()
            if (lastAttemptMs != 0L && now - lastAttemptMs < REQUEST_GAP_MS) return
            if (ctx.getSystemService(PowerManager::class.java)?.isInteractive != true) return
            lastAttemptMs = now
            Log.i(TAG, "a viewer wants the screen — requesting the grant")
            runCatching {
                ctx.startActivity(
                    Intent(ctx, ScreenGrantActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }.onFailure { Log.w(TAG, "grant activity not started: ${it.message}") }
        }

        /** True when a grant can come back with nothing shown on the TV (appop PROJECT_MEDIA). */
        @Suppress("DEPRECATION")
        fun canAutoGrant(ctx: Context): Boolean {
            val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
            val uid = ctx.applicationInfo.uid
            return runCatching { ops.checkOpNoThrow(OP_PROJECT_MEDIA, uid, ctx.packageName) }
                .getOrNull() == AppOpsManager.MODE_ALLOWED
        }
    }
}
