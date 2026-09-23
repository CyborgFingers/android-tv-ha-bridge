package nz.mcnabb.atvhabridge

/**
 * Runs a blocking [block] with a hard timeout; returns null (abandoning the thread) if it
 * doesn't finish in time. `ContentResolver` IPC (unlike this app's http(s) fetches, which
 * already have explicit connect/read timeouts) has no built-in bound — confirmed via logcat
 * to hang indefinitely right after boot, when Android TV's content providers aren't always
 * responsive yet, which stalls MediaListenerService's single-threaded refresh queue forever.
 * A legitimate null result is indistinguishable from a timeout here, but every caller already
 * treats both the same way (as "fetch failed").
 */
fun <T> withBlockingTimeout(timeoutMs: Long, block: () -> T?): T? {
    var result: T? = null
    val thread = Thread {
        result = try { block() } catch (e: Exception) { null }
    }
    thread.isDaemon = true
    thread.start()
    thread.join(timeoutMs)
    return result
}
