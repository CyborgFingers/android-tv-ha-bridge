package nz.mcnabb.atvhabridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * On boot, get [MediaListenerService] re-bound without ADB. On Android 14
 * `requestRebind` is a no-op unless a prior `requestUnbind` ran, so we toggle our own
 * service component — that fires PACKAGE_CHANGED and makes the framework re-bind any
 * approved-but-unbound listener. Needs no special permission: an app may always toggle
 * its own components.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> ensureBound(context)
        }
    }

    private fun ensureBound(context: Context) {
        try {
            if (MediaListenerService.isConnected) return
            val component = ComponentName(context, MediaListenerService::class.java)
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
            NotificationListenerService.requestRebind(component)
            Log.i(TAG, "boot: toggled listener component + requested rebind")
        } catch (e: Exception) {
            Log.e(TAG, "boot rebind failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
