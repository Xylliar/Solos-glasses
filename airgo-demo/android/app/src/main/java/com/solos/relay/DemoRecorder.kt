package com.solos.relay

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Demo recording: everything needed to rebuild a session as an edited video.
 *
 * One folder per recording, in the app's external files (adb pull-able):
 *
 *   demo_<date>/
 *     events.jsonl     one JSON object per line: {"t": ms since start, "type": ..., ...}
 *     transcript.md    the same, readable - written on stop
 *     video/           the glasses' camera, continuous (MPEG-TS, copied from the stream)
 *     audio/user_N.wav what the user said (glasses mic), as sent to Gemini
 *     audio/ai_N.wav   what the AI said, synthesised to file alongside the spoken voice
 *     frames/N_k.jpg   exactly the images sent to Gemini
 *
 * "t" is milliseconds since the recording started, on the phone's clock, for every event
 * and file - that shared clock is what lets a script line everything up afterwards.
 */
object DemoRecorder {

    private const val TAG = "DemoRecorder"

    @Volatile var active = false
        private set
    @Volatile var dir: File? = null
        private set
    @Volatile private var t0 = 0L
    private var counter = 0
    private val lock = Any()

    /** Milliseconds since the recording started (or 0 when not recording). */
    fun now(): Long = if (active) System.currentTimeMillis() - t0 else 0

    /** Convert a wall-clock time to recording time. */
    fun at(wallMs: Long): Long = wallMs - t0

    fun start(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val d = File(context.getExternalFilesDir(null), "demo/demo_$stamp")
        File(d, "video").mkdirs(); File(d, "audio").mkdirs(); File(d, "frames").mkdirs()
        synchronized(lock) {
            dir = d
            t0 = System.currentTimeMillis()
            counter = 0
            active = true
        }
        event("recording_start", "wall_ms" to t0, "date" to stamp)
        Log.i(TAG, "recording to $d")
        return d
    }

    fun stop(): File? {
        if (!active) return null
        event("recording_stop")
        val d = dir
        synchronized(lock) { active = false }
        d?.let { runCatching { writeTranscript(it) }.onFailure { e -> Log.w(TAG, "transcript failed", e) } }
        return d
    }

    fun nextId(): Int = synchronized(lock) { ++counter }

    /** Append one event. Values may be String, Number, Boolean, Map, List or null. */
    fun event(type: String, vararg fields: Pair<String, Any?>) {
        if (!active) return
        val d = dir ?: return
        val o = JSONObject().put("t", now()).put("type", type)
        for ((k, v) in fields) o.put(k, toJson(v))
        synchronized(lock) {
            runCatching { File(d, "events.jsonl").appendText(o.toString() + "\n") }
        }
    }

    /** A file inside the recording folder, e.g. file("audio", "user_3.wav"). */
    fun file(sub: String, name: String): File? = dir?.let { File(File(it, sub), name) }

    /** Relative path for events ("audio/user_3.wav"). */
    fun rel(f: File): String = dir?.let { f.relativeTo(it).path } ?: f.name

    private fun toJson(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
        is List<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> v
        else -> v.toString()
    }

    // ---------------------------------------------------------------- transcript

    private fun writeTranscript(d: File) {
        val lines = File(d, "events.jsonl").readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        fun ts(ms: Long) = "%02d:%05.2f".format(Locale.US, ms / 60000, (ms % 60000) / 1000.0)
        val out = StringBuilder("# Demo session ${d.name}\n\n")
        out.append("Times are mm:ss since recording start. Video: video/*.ts (see `video_start` events).\n\n")
        for (e in lines) {
            val t = ts(e.optLong("t"))
            val line = when (e.optString("type")) {
                "recording_start" -> "**recording started**"
                "recording_stop" -> "**recording stopped**"
                "video_start" -> "video segment `${e.optString("file")}` starts"
                "wake" -> "WAKE by ${e.optString("by")}"
                "listen_start" -> "listening (beep: speak now)"
                "user_speech" -> "USER speaks ${"%.1f".format(Locale.US, e.optDouble("seconds"))}s -> `${e.optString("file")}`"
                "ai_speech_start" -> "AI says: \"${e.optString("text")}\""
                "tool" -> "tool `${e.optString("name")}` ${e.optJSONObject("args") ?: ""}"
                "bind" -> "CONTROLS NOW [${e.optString("label")}]: ${e.optJSONObject("mapping")} - announced: \"${e.optString("announcement")}\""
                "gesture" -> "GESTURE ${e.optString("gesture")}" +
                    (if (e.has("action")) " -> ${e.optString("action")}" else " (does nothing now)")
                "frames_sent" -> "IMAGES SENT TO AI (${e.optString("tool")}): ${e.optJSONArray("files")}"
                "model_text" -> "model: ${e.optString("text")}"
                "gemini" -> "gemini round trip ${e.optLong("ms")} ms"
                "search" -> "WEB SEARCH: ${e.optJSONArray("queries")}"
                else -> null
            } ?: continue
            out.append("- `$t` $line\n")
        }
        File(d, "transcript.md").writeText(out.toString())
    }
}
