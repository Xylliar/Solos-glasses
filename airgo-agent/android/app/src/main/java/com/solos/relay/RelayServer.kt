package com.solos.relay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * A WebSocket server that exposes the glasses to whatever is running on your laptop.
 *
 * The phone stays dumb on purpose. It holds the Bluetooth connection and nothing else,
 * so the agent logic - prompts, tools, scenario scripts - can be edited and re-run
 * without rebuilding and reinstalling an APK. That is the difference between a two
 * second iteration loop and a ninety second one.
 *
 * Protocol is line-oriented JSON, one object per message.
 *
 *   laptop -> phone   {"id":"7","cmd":"look","detail":"detailed"}
 *   phone  -> laptop  {"id":"7","type":"result","ok":true,"image":"<base64>"}
 *   phone  -> laptop  {"type":"action","action":"next_step","gesture":"FORWARD_SLIDE"}
 *
 * Commands carrying an "id" get a matching result. Events (gestures, status) arrive
 * unsolicited with no id.
 */
class RelayServer(
    port: Int,
    private val relay: GlassesRelay,
) : WebSocketServer(InetSocketAddress(port)) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = mutableSetOf<WebSocket>()

    /** Latest log lines, for the phone's on-screen debug view. */
    val log = ArrayDeque<String>()

    companion object {
        private const val TAG = "RelayServer"
        private const val LOG_MAX = 200
    }

    fun note(line: String) {
        synchronized(log) {
            log.addLast(line)
            while (log.size > LOG_MAX) log.removeFirst()
        }
        Log.i(TAG, line)
    }

    /** Push an unsolicited event to every connected client. */
    fun broadcastEvent(payload: Map<String, Any?>) {
        val json = JSONObject(payload.filterValues { it != null }).toString()
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.send(json) } }
    }

    // ---------------------------------------------------------------- server callbacks

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        synchronized(clients) { clients.add(conn) }
        note("client connected: ${conn.remoteSocketAddress}")
        conn.send(
            JSONObject(
                mapOf(
                    "type" to "hello",
                    "connected" to relay.isConnected,
                    "gestures" to GestureBinder.GESTURES,
                )
            ).toString()
        )
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        synchronized(clients) { clients.remove(conn) }
        note("client gone: $reason")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        note("ws error: ${ex.message}")
    }

    override fun onStart() {
        note("relay listening on port $port")
        connectionLostTimeout = 30
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val req = runCatching { JSONObject(message) }.getOrNull() ?: run {
            note("bad json: $message"); return
        }
        scope.launch { handle(conn, req) }
    }

    // ---------------------------------------------------------------- command dispatch

    private suspend fun handle(conn: WebSocket, req: JSONObject) {
        val id = req.optString("id", "")
        val cmd = req.optString("cmd")
        val out = JSONObject().put("type", "result").put("cmd", cmd)
        if (id.isNotEmpty()) out.put("id", id)

        try {
            when (cmd) {
                "scan" -> { relay.startScan(); out.put("ok", true) }
                "stop_scan" -> { relay.stopScan(); out.put("ok", true) }

                "connect" -> {
                    val target = if (req.isNull("device")) null else req.optString("device")
                    out.put("ok", relay.connect(target))
                }

                "disconnect" -> { relay.disconnect(); out.put("ok", true) }

                "look" -> {
                    val b64 = relay.look(
                        detail = req.optString("detail", "detailed"),
                        transfer = if (req.isNull("transfer")) null else req.optString("transfer"),
                    )
                    if (b64 == null) out.put("ok", false).put("error", relay.lastCameraError ?: "capture failed")
                    else out.put("ok", true).put("image", b64)
                }

                "heading" -> {
                    val r = relay.ensureHeading()
                    if (r == null) out.put("ok", false).put("error", relay.lastHeadingError)
                    else out.put("ok", true)
                        .put("yaw", r.yaw).put("pitch", r.pitch).put("compass", r.compass)
                        .put("reliable", r.reliable).put("state", r.state.name)
                        .put("accuracy", r.accuracy.name).put("text", r.describe())
                }

                "speak" -> {
                    val text = req.optString("text")
                    note("speak: $text")
                    out.put("ok", relay.speak(text, req.optBoolean("flush", false)))
                }

                "stop_speaking" -> { relay.stopSpeaking(); out.put("ok", true) }

                // ---- the interesting ones -------------------------------------------

                "claim" -> {
                    relay.binder?.claim(req.optBoolean("steal_power_button", false))
                    out.put("ok", relay.binder != null)
                }

                "release" -> {
                    relay.binder?.release()
                    out.put("ok", true)
                }

                "bind" -> {
                    val label = req.optString("label", "task")
                    val map = req.optJSONObject("map").toStringMap()
                    relay.binder?.bind(label, map)
                    note("bind [$label] $map")
                    out.put("ok", true).put("bound", JSONObject(map))
                }

                "push" -> {
                    val label = req.optString("label", "sub")
                    val map = req.optJSONObject("map").toStringMap()
                    relay.binder?.push(label, map)
                    note("push [$label] $map")
                    out.put("ok", true)
                }

                "pop" -> {
                    val popped = relay.binder?.pop()
                    out.put("ok", true).put("popped", popped?.label)
                }

                "bindings" -> {
                    val c = relay.binder?.current
                    out.put("ok", true)
                        .put("label", c?.label)
                        .put("map", JSONObject(c?.map ?: emptyMap<String, String>()))
                }

                // ---- status ----------------------------------------------------------

                "battery" -> out.put("ok", true).put("level", relay.battery())
                "firmware" -> out.put("ok", true).put("version", relay.firmware())
                "ping" -> out.put("ok", true).put("connected", relay.isConnected)

                else -> out.put("ok", false).put("error", "unknown cmd: $cmd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "cmd $cmd failed", e)
            out.put("ok", false).put("error", e.message ?: e.toString())
        }

        runCatching { conn.send(out.toString()) }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { optString(it) }
    }
}
