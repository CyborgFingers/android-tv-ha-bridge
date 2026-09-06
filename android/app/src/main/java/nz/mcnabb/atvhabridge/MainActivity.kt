package nz.mcnabb.atvhabridge

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat

/**
 * Launcher screen: a 3-step permission checklist (all native — no ADB), then a pairing
 * screen showing the 6-digit code + QR for Home Assistant. Re-checks state every few
 * seconds so it advances the moment a permission is granted or HA pairs.
 */
class MainActivity : Activity() {

    private lateinit var pairing: Pairing
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, REFRESH_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pairing = Pairing(this)

        findViewById<Button>(R.id.grantNotifButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.grantTvButton).setOnClickListener {
            requestPermissions(arrayOf("android.permission.READ_TV_LISTINGS"), REQ_TV)
        }
        findViewById<Button>(R.id.grantA11yButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.newCodeButton).setOnClickListener {
            pairing.rotateCode(); render()
        }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        render()
    }

    private fun hasNotif() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun hasTv() =
        checkSelfPermission("android.permission.READ_TV_LISTINGS") == PackageManager.PERMISSION_GRANTED

    private fun hasA11y(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.startsWith(packageName) && it.contains("ControlAccessibilityService")
        }
    }

    private fun render() {
        val notif = hasNotif(); val tv = hasTv(); val a11y = hasA11y()

        stepButton(R.id.grantNotifButton, notif, R.string.grant_notif, R.string.grant_notif_done)
        stepButton(R.id.grantTvButton, tv, R.string.grant_tv, R.string.grant_tv_done)
        stepButton(R.id.grantA11yButton, a11y, R.string.grant_a11y, R.string.grant_a11y_done)

        val ready = notif && tv && a11y
        findViewById<LinearLayout>(R.id.onboarding).visibility = if (ready) View.GONE else View.VISIBLE
        findViewById<LinearLayout>(R.id.pairing).visibility = if (ready) View.VISIBLE else View.GONE
        if (ready) renderPairing()
    }

    private fun stepButton(id: Int, done: Boolean, todo: Int, doneText: Int) {
        findViewById<Button>(id).apply {
            setText(if (done) doneText else todo)
            isEnabled = !done
        }
    }

    private fun renderPairing() {
        val code = pairing.currentCode()
        findViewById<TextView>(R.id.codeText).text = code

        val host = NetUtil.lanIp() ?: ""
        val payload = "atvhabridge://pair?host=$host&port=${BridgeState.port}" +
            "&id=${pairing.deviceId}&code=$code&name=${pairing.defaultName}&fp=${BridgeState.fingerprint}"
        QrGen.bitmap(payload, 400)?.let { findViewById<ImageView>(R.id.qrImage).setImageBitmap(it) }

        findViewById<TextView>(R.id.pairedText).text =
            if (pairing.isPaired()) getString(R.string.pair_done) + " (${pairing.pairedCount()})"
            else getString(R.string.pair_waiting)
    }

    companion object {
        private const val REFRESH_MS = 3000L
        private const val REQ_TV = 1
    }
}
