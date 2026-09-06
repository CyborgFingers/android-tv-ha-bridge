package nz.mcnabb.atvhabridge

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /** Best-guess LAN IPv4 (prefers 192.168.x). Used only to seed the QR payload —
     * Home Assistant learns the real address from mDNS discovery. */
    fun lanIp(): String? = try {
        val addrs = NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .toList()
        addrs.firstOrNull { it.startsWith("192.168.") } ?: addrs.firstOrNull()
    } catch (e: Exception) {
        null
    }
}
