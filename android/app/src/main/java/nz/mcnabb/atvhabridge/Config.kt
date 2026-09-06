package nz.mcnabb.atvhabridge

/** Static config. No secrets — this app hosts an API that Home Assistant connects to. */
object Config {
    /** HTTP + WebSocket + art, all on one port. Advertised over mDNS so the port is free to change. */
    const val PORT = 8099

    /** mDNS/NSD service type HA's zeroconf discovery matches on. */
    const val SERVICE_TYPE = "_atvhabridge._tcp."

    const val PREFS = "atvhabridge"
    const val API_VERSION = 1
}
