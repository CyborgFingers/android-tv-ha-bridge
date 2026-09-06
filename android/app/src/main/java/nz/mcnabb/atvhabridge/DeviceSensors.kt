package nz.mcnabb.atvhabridge

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Collects the device sensors a Home Assistant companion app would expose — battery,
 * storage, memory, network, screen, volume, uptime, device info. All best-effort and
 * wrapped so one failing probe never breaks the rest. No root; a couple of Wi-Fi
 * fields may be blank without location permission (that's fine).
 */
object DeviceSensors {

    fun collect(ctx: Context): JSONObject = JSONObject().apply {
        put("model", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("android_version", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        runCatching { put("uptime", bootTimeIso()) }

        runCatching {
            val pm = ctx.getSystemService(PowerManager::class.java)
            put("screen_on", pm?.isInteractive == true)
        }

        runCatching {
            val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batt != null) {
                val level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) put("battery_level", level * 100 / scale)
                put("battery_state", batteryStatus(batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1)))
            }
        }

        runCatching {
            val s = StatFs(Environment.getDataDirectory().path)
            put("storage_total_gb", round1(s.totalBytes / GB))
            put("storage_free_gb", round1(s.availableBytes / GB))
        }

        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            put("memory_total_mb", (mi.totalMem / MB))
            put("memory_free_mb", (mi.availMem / MB))
        }

        runCatching {
            val audio = ctx.getSystemService(AudioManager::class.java)
            if (audio != null) {
                val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                put("volume_level", vol * 100 / max)
                put("volume_muted", audio.isStreamMute(AudioManager.STREAM_MUSIC))
            }
        }

        runCatching { putNetwork(ctx) }
    }

    private fun JSONObject.putNetwork(ctx: Context) {
        NetUtil.lanIp()?.let { put("ip", it) }
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        put("network_type", type)
        if (type == "wifi") runCatching {
            @Suppress("DEPRECATION")
            val info = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
            info?.let {
                put("wifi_rssi", it.rssi)
                put("wifi_link_speed", it.linkSpeed)
                val ssid = it.ssid?.trim('"')?.takeIf { s -> s.isNotBlank() && s != "<unknown ssid>" }
                if (ssid != null) put("wifi_ssid", ssid)
            }
        }
    }

    private fun batteryStatus(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    private fun bootTimeIso(): String {
        val bootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(bootMs), ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
    private const val GB = 1024.0 * 1024 * 1024
    private const val MB = 1024L * 1024
}
