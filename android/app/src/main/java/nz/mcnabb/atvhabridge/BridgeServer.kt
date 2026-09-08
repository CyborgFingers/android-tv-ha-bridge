package nz.mcnabb.atvhabridge

import android.graphics.Bitmap
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The bridge's local API, served on one port for both HTTP and WebSocket:
 *   GET  /api/info                 device id/name/model + paired flag (open — for discovery)
 *   POST /api/pair  {"code":"..."} redeem the 6-digit code → {"token":"..."} (open)
 *   GET  /api/state                current now-playing JSON            (token)
 *   GET  /art.jpg                  current poster JPEG                 (open; LAN image)
 *   GET  /art_next_{i}.jpg         poster of up_next_list[i]; /art_next.jpg = i 0 (open)
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
    }
}
