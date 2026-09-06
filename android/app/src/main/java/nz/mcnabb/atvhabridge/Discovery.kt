package nz.mcnabb.atvhabridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises the bridge on the LAN via mDNS/DNS-SD so Home Assistant's zeroconf
 * discovery finds it automatically ("_mediabridge._tcp"). TXT records carry the
 * stable device id + a friendly default name; the HA config flow then pairs with
 * the 6-digit code. No permission required for NSD.
 */
class Discovery(context: Context, private val port: Int, private val pairing: Pairing) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun start() {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "MediaBridge-${pairing.deviceId.take(6)}"
            serviceType = Config.SERVICE_TYPE
            setPort(port)
            setAttribute("id", pairing.deviceId)
            setAttribute("name", pairing.defaultName)
            setAttribute("ver", Config.API_VERSION.toString())
            setAttribute("fp", BridgeState.fingerprint)
            setAttribute("secure", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        listener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.e(TAG, "registerService: ${it.message}"); listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    companion object {
        private const val TAG = "Discovery"
    }
}
