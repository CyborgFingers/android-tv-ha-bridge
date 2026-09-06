package nz.mcnabb.atvhabridge

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Provides D-pad / OK / Back / Home navigation without ADB. The user enables it once
 * from the accessibility settings (native toggle). D-pad global actions require
 * Android 13+ (API 33); Back/Home work everywhere. It reads nothing from the screen —
 * it only performs the global actions the bridge asks for.
 */
class ControlAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        Controls.accessibility = this
    }

    override fun onDestroy() {
        if (Controls.accessibility === this) Controls.accessibility = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun doGlobal(action: String): Boolean {
        val dpad = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val id = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return false
            "dpad_up" -> if (dpad) GLOBAL_ACTION_DPAD_UP else return false
            "dpad_down" -> if (dpad) GLOBAL_ACTION_DPAD_DOWN else return false
            "dpad_left" -> if (dpad) GLOBAL_ACTION_DPAD_LEFT else return false
            "dpad_right" -> if (dpad) GLOBAL_ACTION_DPAD_RIGHT else return false
            "dpad_center", "ok", "center" -> if (dpad) GLOBAL_ACTION_DPAD_CENTER else return false
            else -> return false
        }
        return try { performGlobalAction(id) } catch (e: Exception) { false }
    }
}
