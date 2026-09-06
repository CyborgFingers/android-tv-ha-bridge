package nz.mcnabb.atvhabridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Advertises the bridge on the LAN via mDNS/DNS-SD so Home Assistant's zeroconf
 * discovery — and the integration's own re-discovery on reconnect — can find it, and
 * FIND IT AGAIN if the bound port ever changes across a restart.
 *
 * NSD registration can fail transiently (network not settled at boot, mdnsd hiccup,
 * a stale registration still releasing). The old code logged the failure once and gave
 * up, leaving the advert permanently down until the process restarted — which silently
 * defeats every port self-heal that depends on the advert. So we retry until it sticks.
 *
 * NOTE: the param is `servicePort`, NOT `port`. Inside `NsdServiceInfo().apply { }` the
 * receiver already exposes a `port` property (Java get/setPort), so an unqualified `port`
 * there resolves to the receiver's (0 on a fresh object), silently zeroing setPort and
 * making registration fail with "Invalid port number". A distinct name can't be shadowed.
 */
class Discovery(context: Context, private val servicePort: Int, private val pairing: Pairing) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: NsdManager.RegistrationListener? = null
    private var stopped = false

    fun start() {
        stopped = false
        register()
    }

    private fun register() {
        if (stopped || listener != null) return
        if (servicePort <= 0) {
            // Nothing bound to advertise. A fixed bad port won't improve on retry, so
            // don't spin — the caller must reconstruct us with a real port.
            Log.e(TAG, "refusing to advertise invalid port $servicePort")
            return
        }
        val info = NsdServiceInfo().apply {
            serviceName = "MediaBridge-${pairing.deviceId.take(6)}"
            serviceType = Config.SERVICE_TYPE
            setPort(servicePort)
            setAttribute("id", pairing.deviceId)
            setAttribute("name", pairing.defaultName)
            setAttribute("ver", Config.API_VERSION.toString())
            setAttribute("fp", BridgeState.fingerprint)
            setAttribute("secure", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered: ${info.serviceName} :$servicePort")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "registration failed: $code; retry in ${RETRY_MS}ms")
                listener = null
                scheduleRetry()
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        listener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure {
                Log.e(TAG, "registerService: ${it.message}; retry in ${RETRY_MS}ms")
                listener = null
                scheduleRetry()
            }
    }

    private fun scheduleRetry() {
        if (!stopped) handler.postDelayed(::register, RETRY_MS)
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    companion object {
        private const val TAG = "Discovery"
        private const val RETRY_MS = 15_000L
    }
}
