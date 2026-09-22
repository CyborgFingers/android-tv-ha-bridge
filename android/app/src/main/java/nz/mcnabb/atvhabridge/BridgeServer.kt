package nz.mcnabb.atvhabridge

import android.graphics.Bitmap
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The bridge's local API, served on one port for both HTTP and WebSocket:
 *   GET  /api/info                 device id/name/model + paired flag (open — for discovery)
 *   POST /api/pair  {"code":"..."} redeem the 6-digit code → {"token":"..."} (open)
 *   GET  /api/state                current now-playing JSON            (token)
 *   GET  /art.jpg                  current poster JPEG                 (open; LAN image)
 *   GET  /art_next_{i}.jpg         poster of up_next_list[i]; /art_next.jpg = i 0 (open)
 *   GET  /screen.mjpeg             live screen mirror, multipart MJPEG (token)
 *   WS   /ws?token=...             live state pushes                   (token)
 */
class BridgeServer(
    port: Int,
    private val pairing: Pairing,
    private val art: (String) -> Bitmap?,
) : NanoWSD(port) {

    // ---- WebSocket (live push) ---------------------------------------------

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val token = handshake.parameters["token"]?.firstOrNull()
        return if (pairing.isValidToken(token)) StateSocket(handshake) else RejectSocket(handshake)
    }

    private inner class StateSocket(hs: IHTTPSession) : WebSocket(hs) {
        private val listener: (String) -> Unit = { text -> runCatching { send(text) } }
        override fun onOpen() {
            BridgeState.addListener(listener)
            // Push the current snapshot AFTER onOpen returns, off a background thread.
            // Sending during the WebSocket handshake/onOpen drops the connection on
            // strict clients (aiohttp), so the entity never stays available.
            Thread { runCatching { Thread.sleep(80); send(BridgeState.currentJson()) } }.start()
        }
        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, remote: Boolean) {
            BridgeState.removeListener(listener)
        }

        /** Commands arrive over the already-open, already-authed socket — no per-key
         * TCP/HTTP handshake, so navigation stays snappy. Fire-and-forget: we dispatch
         * immediately and don't block on a reply. */
        override fun onMessage(message: WebSocketFrame) {
            runCatching {
                val obj = JSONObject(message.textPayload ?: return)
                Controls.handle(obj.optString("action"), obj)
            }
        }

        override fun onPong(pong: WebSocketFrame) {}
        override fun onException(exception: IOException) = BridgeState.removeListener(listener).let { }
    }

    private inner class RejectSocket(hs: IHTTPSession) : WebSocket(hs) {
        override fun onOpen() { runCatching { close(WebSocketFrame.CloseCode.PolicyViolation, "unauthorized", false) } }
        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, remote: Boolean) {}
        override fun onMessage(message: WebSocketFrame) {}
        override fun onPong(pong: WebSocketFrame) {}
        override fun onException(exception: IOException) {}
    }

    // ---- HTTP --------------------------------------------------------------

    override fun serveHttp(session: IHTTPSession): Response = try {
        when {
            session.uri == "/api/info" -> json(infoJson())
            session.uri == "/api/pair" && session.method == Method.POST -> handlePair(session)
            session.uri == "/api/state" -> if (authed(session)) json(BridgeState.currentJson()) else unauthorized()
            session.uri == "/api/command" && session.method == Method.POST ->
                if (authed(session)) handleCommand(session) else unauthorized()
            session.uri == "/api/apps" -> if (authed(session)) json(appsJson()) else unauthorized()
            session.uri == "/art.jpg" -> serveArt("current")
            session.uri == "/art_next.jpg" -> serveArt("next_0")
            session.uri.startsWith("/art_next_") ->
                serveArt("next_" + session.uri.removePrefix("/art_next_").removeSuffix(".jpg"))
            session.uri == "/screen.mjpeg" -> if (authed(session)) serveScreen() else unauthorized()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
    } catch (e: Exception) {
        Log.e(TAG, "serve ${session.uri} failed: ${e.message}")
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error")
    }

    private fun infoJson(): String = JSONObject().apply {
        put("id", pairing.deviceId)
        put("name", BridgeState.device.name)
        put("model", android.os.Build.MODEL)
        put("api_version", Config.API_VERSION)
        put("paired", pairing.isPaired())
        put("secure", true)
        put("fingerprint", BridgeState.fingerprint)
    }.toString()

    private fun handlePair(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val code = runCatching { JSONObject(body["postData"] ?: "{}").optString("code") }.getOrDefault("")
        val token = pairing.redeem(code)
        return if (token != null) {
            json(JSONObject().put("token", token).put("id", pairing.deviceId).toString())
        } else {
            newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", """{"error":"bad_code"}""")
        }
    }

    private fun appsJson(): String {
        val ctx = Controls.appContext ?: return "[]"
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val arr = org.json.JSONArray()
        for (cat in listOf(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER, android.content.Intent.CATEGORY_LAUNCHER)) {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(cat)
            runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull()?.forEach {
                val pkg = it.activityInfo.packageName
                if (seen.add(pkg)) arr.put(JSONObject().put("package", pkg).put("name", it.loadLabel(pm).toString()))
            }
        }
        return arr.toString()
    }

    private fun handleCommand(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val obj = runCatching { JSONObject(body["postData"] ?: "{}") }.getOrDefault(JSONObject())
        val ok = Controls.handle(obj.optString("action"), obj)
        return json(JSONObject().put("ok", ok).toString())
    }

    private fun serveArt(which: String): Response {
        val bmp = art(which) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no art")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val bytes = out.toByteArray()
        return newChunkedResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes)).apply {
            addHeader("Cache-Control", "no-cache")
        }
    }

    /** Live screen mirror. Requires ScreenCaptureService to already hold a MediaProjection
     * grant (set up once from MainActivity's 4th onboarding step); if it doesn't, tells
     * the client plainly rather than hanging. addClient()/removeClient() on the service
     * gate the actual capture pipeline to "someone is watching", so an idle bridge with
     * no dashboard open costs nothing extra. */
    private fun serveScreen(): Response {
        if (ScreenCaptureService.instance == null) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT,
                "screen streaming not enabled on this device — open the app once to grant it",
            )
        }
        return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY", MjpegStream()).apply {
            addHeader("Cache-Control", "no-cache")
        }
    }

    /** Pull-based multipart JPEG stream. Each read() cycle throttles to FRAME_INTERVAL_MS
     * then asks ScreenCaptureService for the latest frame — all the actual capture/encode
     * cost lives there, gated to only run while this stream is open. close() (called by
     * NanoHTTPD once the response finishes sending or the client disconnects) is the one
     * and only place removeClient() fires, so the client count can't leak even if the
     * connection dies mid-frame. */
    private inner class MjpegStream : InputStream() {
        private var buffer = ByteArray(0)
        private var pos = 0
        private var closed = false
        private var started = false

        override fun read(): Int {
            fillIfNeeded()
            return if (pos >= buffer.size) -1 else buffer[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            fillIfNeeded()
            if (pos >= buffer.size) return -1
            val n = minOf(len, buffer.size - pos)
            System.arraycopy(buffer, pos, b, off, n)
            pos += n
            return n
        }

        private fun fillIfNeeded() {
            if (pos < buffer.size || closed) return
            val svc = ScreenCaptureService.instance
            if (svc == null) { buffer = ByteArray(0); pos = 0; return }
            if (!started) { svc.addClient(); started = true }

            // First frame may not be ready the instant capture starts — wait briefly
            // rather than emit a broken/empty frame.
            var jpeg = svc.latestJpeg()
            var waited = 0
            while (jpeg == null && waited < 2000) {
                Thread.sleep(100); waited += 100
                jpeg = svc.latestJpeg()
            }
            if (jpeg == null) { buffer = ByteArray(0); pos = 0; return }

            Thread.sleep(FRAME_INTERVAL_MS)
            val header = "--$MJPEG_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                .toByteArray(Charsets.US_ASCII)
            buffer = header + jpeg + "\r\n".toByteArray(Charsets.US_ASCII)
            pos = 0
        }

        override fun close() {
            if (closed) return
            closed = true
            if (started) ScreenCaptureService.instance?.removeClient()
        }
    }

    private fun authed(session: IHTTPSession): Boolean {
        val q = session.parameters["token"]?.firstOrNull()
        val h = session.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        return pairing.isValidToken(q ?: h)
    }

    private fun json(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun unauthorized(): Response =
        newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", """{"error":"unauthorized"}""")

    companion object {
        private const val TAG = "BridgeServer"
        private const val MJPEG_BOUNDARY = "atvhabridgeframe"

        // ~2.5fps: plenty to see what's on the TV at a glance, light on the box's CPU.
        private const val FRAME_INTERVAL_MS = 400L
    }
}
