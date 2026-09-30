package com.solos.relay

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The agent loop, running on the phone.
 *
 * This is the demo path: no laptop, no WebSocket, nothing else on the network. The relay
 * server stays available for development because editing a prompt on a laptop and
 * rerunning beats rebuilding an APK - but you cannot walk a venue carrying a laptop, so
 * the same loop lives here too.
 *
 * Web research is Gemini's own: we declare googleSearch and urlContext and the model
 * uses them server-side. There is no search code here.
 */
class GeminiAgent(
    private val relay: GlassesRelay,
    private val speech: SpeechInput,
    /** Raw-audio input for Gemini; null = old path (Android speech-to-text). */
    private val voice: VoiceCapture? = null,
    private val apiKey: String,
    private val model: String,
    private val useSearch: Boolean = true,
    private val thinking: String = "",
    private val onLog: (String) -> Unit,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // a search-heavy turn can be slow
        .build()

    /** Bound gestures land here while the agent is waiting in await_gesture. */
    private val actions = Channel<String>(Channel.UNLIMITED)

    @Volatile var running = false
        private set
    @Volatile private var finished = false

    private val endpoint: String
        get() {
            val m = model.removePrefix("models/")
            return "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent"
        }

    companion object {
        private const val TAG = "GeminiAgent"
        private const val MAX_TURNS = 40
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    /** Fed from GlassesRelay's event stream so await_gesture can wake up. */
    /** "Hey Solos" mid-task: what the user said, waiting to be heard by the agent. */
    private val interjections = Channel<VoiceCapture.Clip>(Channel.UNLIMITED)

    /** True while listen() is recording - a mid-task wake then needs no second mic. */
    @Volatile var listening = false
        private set

    /** Hand the agent something the user said after interrupting with "Hey Solos". */
    fun interject(clip: VoiceCapture.Clip) { interjections.trySend(clip) }

    fun onEvent(event: Map<String, Any?>) {
        if (event["type"] == "action") {
            (event["action"] as? String)?.let { actions.trySend(it) }
        }
    }

    /**
     * Stop now: no further turns, the in-flight Gemini request cancelled (its tool calls
     * would otherwise still run when it returns), and queued speech dropped - sentences are
     * queued, not awaited, so without a flush the agent keeps talking after Stop.
     */
    fun stop() {
        running = false
        currentCall?.cancel()
        relay.stopSpeaking()
    }

    @Volatile private var currentCall: okhttp3.Call? = null

    // ---------------------------------------------------------------- the loop

    suspend fun run(goal: String?, request: VoiceCapture.Clip? = null) = withContext(Dispatchers.IO) {
        running = true
        finished = false
        while (actions.tryReceive().isSuccess) { /* drop stale gestures */ }

        // The spoken request, if the wake-up captured one, rides along as audio: Gemini
        // hears it itself instead of reading a speech-to-text guess.
        val first = JSONArray()
        if (request != null) {
            first.put(JSONObject().put("text", Prompt.kickoffAudio(Lang.current)))
            first.put(audioPart(request))
            onLog("request sent as audio (${"%.1f".format(request.seconds)}s, ${request.source})")
        } else {
            first.put(JSONObject().put("text", Prompt.kickoff(goal, Lang.current)))
        }
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", first))

        try {
            if (relay.binder == null || !relay.hasGlasses) {
                onLog("ERROR: not connected to glasses - connect first")
                return@withContext
            }
            runCatching { relay.binder?.claim() }
                .onFailure {
                    Log.e(TAG, "claim failed", it)
                    onLog("WARN: could not claim gestures: ${SolosKey.explain(it)}")
                }
            // Start the compass now so the first heading()/look() already has a reading.
            relay.ensureHeading()?.let { onLog("heading: ${it.describe()}") }
                ?: onLog("WARN: no heading - ${relay.lastHeadingError}")

            for (turn in 0 until MAX_TURNS) {
                if (!running) { onLog("stopped"); break }

                val resp = try {
                    post(contents)
                } catch (e: Exception) {
                    if (!running) break   // cancelled by Stop: not an error, say nothing
                    Log.e(TAG, "request failed", e)
                    onLog("API ERROR (${e.javaClass.simpleName}): ${e.message}")
                    relay.speak(Lang.current.steer("error"), true)
                    break
                }

                val candidate = resp.optJSONArray("candidates")?.optJSONObject(0)
                val content = candidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    val reason = candidate?.optString("finishReason").orEmpty()
                    onLog("empty response" + if (reason.isNotBlank()) " (finishReason=$reason)" else "")
                    Log.w(TAG, "no parts: ${resp.toString().take(800)}")
                    break
                }

                contents.put(content)

                // What did Gemini look up on its own? Useful for judging the prompt.
                candidate.optJSONObject("groundingMetadata")
                    ?.optJSONArray("webSearchQueries")?.let { q ->
                        if (q.length() > 0) onLog("searched: $q")
                    }

                val calls = mutableListOf<JSONObject>()
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    p.optJSONObject("functionCall")?.let { calls.add(it) }
                    if (!p.optBoolean("thought", false)) {
                        p.optString("text").takeIf { it.isNotBlank() }?.let {
                            onLog("model: ${it.take(140)}")
                        }
                    }
                }

                if (calls.isEmpty()) { onLog("model ended the turn"); break }

                val responseParts = JSONArray()
                for (call in calls) {
                    if (!running) break   // stopped while Gemini was answering
                    val name = call.optString("name")
                    val args = call.optJSONObject("args") ?: JSONObject()
                    onLog("tool: $name $args")
                    dispatch(name, args, responseParts)
                }
                // "Hey Solos" while Gemini was answering or tools were running: deliver it now.
                while (true) {
                    val c = interjections.tryReceive().getOrNull() ?: break
                    responseParts.put(JSONObject().put("text", "INTERRUPTION: meanwhile the user said " +
                        "\"Hey Solos\" and spoke - their words are attached as audio. This comes " +
                        "first: respond to it, then continue or change the plan."))
                    responseParts.put(audioPart(c))
                    onLog("interruption delivered to the agent")
                }
                contents.put(JSONObject().put("role", "user").put("parts", responseParts))

                if (finished) { onLog("task complete"); break }
            }
        } finally {
            // Never leave the user's controls hijacked, whatever happened.
            runCatching { relay.binder?.release() }
            // The orientation stream shares the BLE link with photo transfers; stop it.
            runCatching { relay.head?.stop() }
            running = false
            onLog("agent finished")
        }
    }

    // ---------------------------------------------------------------- tools

    private suspend fun dispatch(name: String, a: JSONObject, out: JSONArray) {
        var result = "ok"
        var image: String? = null
        var audio: VoiceCapture.Clip? = null
        var frames: List<GlassesRelay.Frame> = emptyList()
        var clip: GlassesRelay.VideoClip? = null

        try {
            if (name in setOf("look", "heading", "steer_to") && !relay.linkUp) {
                onLog("$name: link down, waiting for reconnection...")
                relay.awaitLink()
            }
            when (name) {
                "speak" -> {
                    val text = a.optString("text")
                    relay.speak(text, false)
                    result = "spoken"
                }

                "look_around" -> {
                    val secs = a.optInt("seconds", 6).coerceIn(3, 15)
                    val n = a.optInt("max_frames", 8).coerceIn(2, 12)
                    // Video when the glasses are on Wi-Fi: real motion + sound, with a compass
                    // timeline. Otherwise a sweep of compass-tagged photos.
                    // Live view already running: frames come from the stream (instant).
                    val video = if (!relay.liveRunning && relay.wifiConnected) relay.recordClip(secs) else null
                    if (video != null) {
                        clip = video
                        result = "A ${video.seconds}s video clip from the user's glasses is attached " +
                            "(with sound). While it recorded, the head pointed: ${video.compassTimeline()}. " +
                            "Match moments in the video to those headings to say where things are " +
                            "relative to where they face now, and steer_to() to point them."
                        onLog("look_around: sent ${video.seconds}s video, ${video.mp4.size / 1024} KB")
                    } else {
                    val sweep = relay.lookAround(secs, n, a.optString("detail", "medium"))
                    if (sweep == null) {
                        result = "look_around failed: ${relay.lastCameraError}. Fall back to look()."
                    } else {
                        frames = sweep.frames
                        result = "${sweep.frames.size} frames attached, in time order, each labelled " +
                            "with the compass heading it faced (0 north, 90 east). They cover about " +
                            "${sweep.span} degrees. Use the headings to tell the user where things " +
                            "are relative to where they face now, and steer_to() to point them."
                        onLog("look_around: ${sweep.received} frames, ${"%.1f".format(sweep.fps)} fps, " +
                            "sent ${sweep.frames.size} covering ${sweep.span} degrees")
                    }
                    }
                }

                "look" -> {
                    val detail = a.optString("detail", "detailed")
                    // The shutter fires at the start; the transfer is what takes time.
                    val facing = relay.head?.fresh
                    val t0 = System.currentTimeMillis()
                    val b64 = relay.look(detail, null)
                    val ms = System.currentTimeMillis() - t0
                    if (b64 == null) {
                        val why = relay.lastCameraError ?: "unknown"
                        onLog("look FAILED after ${ms}ms (detail=$detail): $why")
                        result = "the camera did not return an image ($why). Tell the user the " +
                            "camera did not respond, then either try once more or ask them " +
                            "to describe what they see."
                    } else {
                        image = b64
                        onLog("look ok: ${b64.length / 1365}kB in ${ms}ms (detail=$detail)")
                        result = "camera frame attached (detail=$detail). " +
                            (facing?.let { "Camera was facing: ${it.describe()}." }
                                ?: "Camera direction unknown.")
                    }
                }

                "listen" -> {
                    val secs = a.optInt("seconds", 6)
                    // Speech is queued, not awaited - so don't open the mic on our own voice.
                    relay.awaitSpeechDone()
                    val pending = interjections.tryReceive().getOrNull()
                    if (voice != null) {
                        listening = pending == null
                        val clip = try {
                            pending ?: voice.capture(waitForSpeechMs = secs.coerceIn(3, 15) * 1000L)
                        } finally { listening = false }
                        if (clip != null) {
                            audio = clip
                            result = "The user's reply is attached as audio " +
                                "(${"%.1f".format(clip.seconds)}s). Listen to it directly - that is " +
                                "what they said."
                            onLog("heard ${"%.1f".format(clip.seconds)}s of audio (${clip.source})")
                        } else {
                            result = "(the user said nothing)"
                            onLog("heard: nothing")
                        }
                    } else {
                        result = speech.listen(secs) ?: "(the user said nothing)"
                        onLog("heard: $result")
                    }
                }

                "bind_gestures", "push_gestures" -> {
                    val label = a.optString("label", "task")
                    val map = parseMapping(a.optJSONArray("mapping"))
                    if (name == "bind_gestures") relay.binder?.bind(label, map)
                    else relay.binder?.push(label, map)
                    val announcement = a.optString("announcement")
                    if (announcement.isNotBlank()) {
                        relay.speak(announcement, false)
                    }
                    result = "bound and announced: $map"
                }

                "pop_gestures" -> {
                    relay.binder?.pop()
                    result = "popped back to the previous mapping"
                }

                "await_gesture" -> {
                    val timeout = a.optInt("timeout", 120).coerceIn(1, 600)
                    var spoke: VoiceCapture.Clip? = null
                    val got = withTimeoutOrNull(timeout * 1000L) {
                        kotlinx.coroutines.selects.select<String> {
                            actions.onReceive { it }
                            interjections.onReceive { spoke = it; "" }
                        }
                    }
                    result = when {
                        spoke != null -> {
                            audio = spoke
                            onLog("await_gesture: interrupted by voice")
                            "The user didn't tap - they said \"Hey Solos\" and spoke instead. " +
                                "Their words are attached as audio. Respond to that."
                        }
                        got != null -> "the user triggered: $got"
                        else -> "timeout - the user did nothing"
                    }
                }

                "unbind_gestures" -> {
                    relay.binder?.release()
                    finished = true
                    result = "controls handed back to the user"
                }

                "get_battery" -> result = "${relay.battery()}%"

                "heading" -> {
                    val r = relay.ensureHeading()
                    result = r?.describe() ?: "no heading: ${relay.lastHeadingError}"
                }

                "steer_to" -> result = steerTo(a)

                "set_language" -> {
                    val lang = Lang.from(a.optString("language"))
                    result = if (lang == null) "unsupported language - only en and fr" else {
                        Lang.current = lang
                        val voice = relay.setLanguage(lang)
                        onLog("language -> ${lang.code}")
                        "now ${lang.displayName}: listening in ${lang.displayName}" +
                            (if (voice) " and speaking with a ${lang.displayName} voice. "
                             else ", but this phone has no ${lang.displayName} voice installed. ") +
                            "Speak ${lang.displayName} from now on."
                    }
                }

                else -> result = "unknown tool: $name"
            }
        } catch (e: Exception) {
            Log.e(TAG, "tool $name failed", e)
            result = "error: ${e.message}"
        }

        out.put(
            JSONObject().put(
                "functionResponse",
                JSONObject()
                    .put("name", name)
                    .put("response", JSONObject().put("result", result))
            )
        )
        // The image rides as its own part right after the function response. That shape
        // is simpler and better-travelled than nesting blobs inside functionResponse.
        if (image != null) {
            out.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", image)
                )
            )
        }
        audio?.let { out.put(audioPart(it)) }
        clip?.let {
            out.put(JSONObject().put("inlineData", JSONObject().put("mimeType", it.mime)
                .put("data", android.util.Base64.encodeToString(it.mp4, android.util.Base64.NO_WRAP))))
        }
        // A sweep: each frame preceded by its label, so the model can't mix up directions.
        frames.forEachIndexed { i, f ->
            out.put(JSONObject().put("text", "Frame ${i + 1}: ${f.label()}"))
            out.put(JSONObject().put("inlineData",
                JSONObject().put("mimeType", "image/jpeg").put("data", f.jpegB64)))
        }
    }

    private fun audioPart(clip: VoiceCapture.Clip) = JSONObject().put(
        "inlineData",
        JSONObject().put("mimeType", "audio/wav")
            .put("data", android.util.Base64.encodeToString(clip.wav, android.util.Base64.NO_WRAP)),
    )

    /**
     * Turn the user's head to a heading, with spoken left/right cues from a loop on the
     * phone. Takes either an absolute compass heading or a turn relative to where they
     * face now.
     */
    private suspend fun steerTo(a: JSONObject): String {
        val start = relay.ensureHeading() ?: return "cannot steer: ${relay.lastHeadingError}"
        val target = when {
            a.has("heading") -> HeadTracker.normalize(a.getDouble("heading"))
            a.has("turn") -> HeadTracker.normalize(start.yaw + a.getDouble("turn"))
            else -> return "error: give either heading or turn"
        }
        val tolerance = a.optDouble("tolerance", 15.0).coerceIn(5.0, 45.0)
        val timeout = a.optInt("timeout", 20).coerceIn(3, 90)
        val warning = if (start.reliable) "" else
            " WARNING: compass was unreliable during this (${start.describe()}) - " +
                "confirm the direction with look() before sending them far."
        relay.awaitSpeechDone()   // cues flush the queue; let the lead-in finish first
        val steering = Steering(relay.head!!) { relay.speak(it, true) }
        onLog("steer: ${start.yaw.toInt()} -> ${target.toInt()} (tol ${tolerance.toInt()})")
        return when (val o = steering.steer(target, tolerance, timeout * 1000L) {
            actions.tryReceive().getOrNull()
        }) {
            is Steering.Outcome.Aligned ->
                "aligned: user now faces ${o.reading.describe()}. The phone already told " +
                    "them to stop - now tell them what to walk towards.$warning"
            is Steering.Outcome.Interrupted ->
                "interrupted: the user triggered ${o.action} while facing " +
                    "${o.reading?.describe() ?: "unknown"}.$warning"
            is Steering.Outcome.TimedOut ->
                "timed out after ${timeout}s, user faces " +
                    "${o.reading?.describe() ?: "unknown"}, target was ${target.toInt()}.$warning"
            is Steering.Outcome.NoSignal -> "cannot steer: ${o.why}"
        }
    }

    /** mapping arrives as [{gesture, action}, ...] - see the schema note in tools(). */
    private fun parseMapping(arr: JSONArray?): Map<String, String> {
        if (arr == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val g = o.optString("gesture").uppercase()
            val act = o.optString("action")
            if (g.isNotBlank() && act.isNotBlank()) out[g] = act
        }
        return out
    }


    // ---------------------------------------------------------------- transport

    private fun post(contents: JSONArray): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", Prompt.SYSTEM))
            ))
            .put("contents", contents)
            .put("tools", tools())
        if (thinking.isNotBlank()) {
            body.put("generationConfig", JSONObject().put(
                "thinkingConfig", JSONObject().put("thinkingLevel", thinking)))
        }

        // Built-in tools (googleSearch, urlContext) may only be combined with our own
        // functionDeclarations when this is set - otherwise the API returns 400.
        if (useSearch) {
            body.put(
                "toolConfig",
                JSONObject().put("includeServerSideToolInvocations", true)
            )
        }

        val req = Request.Builder()
            .url(endpoint)
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()

        val call = http.newCall(req)
        currentCall = call
        call.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // Google puts the useful part in error.message; fall back to raw body.
                val detail = runCatching {
                    JSONObject(text).getJSONObject("error").optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: text
                throw RuntimeException("HTTP ${resp.code} ${resp.message}: ${detail.take(600)}")
            }
            return JSONObject(text)
        }
    }

    /**
     * Our tools, plus Gemini's own.
     *
     * Schema note: this uses the OpenAPI subset Gemini accepts for functionDeclarations,
     * which has no additionalProperties - so a free-form {gesture: action} object is not
     * expressible. The mapping is an ARRAY of {gesture, action} pairs instead, which is
     * well supported everywhere and just as easy for the model to produce.
     */
    private fun tools(): JSONArray {
        fun schema(props: JSONObject, required: List<String>) = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", JSONArray(required))

        // A zero-argument function gets no `parameters` at all - an empty object schema
        // is rejected by some API versions.
        fun decl(name: String, description: String, params: JSONObject?) =
            JSONObject().put("name", name).put("description", description)
                .also { if (params != null) it.put("parameters", params) }

        fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

        val mappingSchema = JSONObject()
            .put("type", "array")
            .put("description", "At most three controls.")
            .put(
                "items",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("gesture", JSONObject()
                                .put("type", "string")
                                .put("enum", JSONArray(GestureBinder.GESTURES)))
                            .put("action", str("Your name for what it does, e.g. next_step."))
                    )
                    .put("required", JSONArray(listOf("gesture", "action")))
            )

        val decls = JSONArray()

        decls.put(JSONObject()
            .put("name", "speak")
            .put("description", "Say something out loud through the glasses. Your only " +
                "way to communicate. One or two short sentences.")
            .put("parameters", schema(
                JSONObject().put("text", str("Spoken aloud, so no formatting.")),
                listOf("text"))))

        decls.put(JSONObject()
            .put("name", "look")
            .put("description", "Capture a photo from the camera on the user's face and " +
                "see it. 'quick' takes a second or two and is fine for identifying " +
                "objects, people or scenes. 'medium' is a good default. 'detailed' can " +
                "read small text and labels but can take up to a minute over Bluetooth - " +
                "only use it when you actually need to read something. " +
                "Always tell the user you are looking before calling this.")
            .put("parameters", schema(
                JSONObject()
                    .put("detail", JSONObject().put("type", "string")
                        .put("enum", JSONArray(listOf("quick", "medium", "detailed"))))
                    .put("reason", str("Why you need to look.")),
                listOf("detail", "reason"))))

        decls.put(JSONObject()
            .put("name", "look_around")
            .put("description", "Semi-live look: stream camera frames for a few seconds while the " +
                "user slowly turns their head, and get back several frames, each labelled with " +
                "the compass heading it faced. Use it to find something around the user, get the " +
                "lay of a room or hall, or check a wider scene than one photo shows. BEFORE calling " +
                "it, tell the user in one sentence to turn their head slowly (e.g. from left to " +
                "right). Then answer with directions and use steer_to to point them.")
            .put("parameters", schema(
                JSONObject()
                    .put("seconds", JSONObject().put("type", "integer")
                        .put("description", "How long to stream, 3-15. 6 is a good sweep."))
                    .put("max_frames", JSONObject().put("type", "integer")
                        .put("description", "Frames to keep, 2-12. 8 is a good default."))
                    .put("detail", JSONObject().put("type", "string")
                        .put("enum", JSONArray(listOf("quick", "medium", "detailed"))))
                    .put("reason", str("Why you need to look around.")),
                listOf("reason"))))

        decls.put(JSONObject()
            .put("name", "listen")
            .put("description", "Listen to the user. A beep tells them to speak; their " +
                "words come back as audio you hear directly (or as text on older setups). " +
                "`seconds` is how long to wait for them to START talking.")
            .put("parameters", schema(
                JSONObject().put("seconds", JSONObject().put("type", "integer")),
                listOf("seconds"))))

        decls.put(JSONObject()
            .put("name", "bind_gestures")
            .put("description", "Reassign what the glasses' physical controls mean, and " +
                "announce the change out loud in the same call. Use whenever the step " +
                "changes. Bind at most three.")
            .put("parameters", schema(
                JSONObject()
                    .put("label", str("Short name for this step, e.g. 'bloom'."))
                    .put("mapping", mappingSchema)
                    .put("announcement", str("Spoken immediately, describing the new " +
                        "controls in one sentence.")),
                listOf("label", "mapping", "announcement"))))

        decls.put(JSONObject()
            .put("name", "push_gestures")
            .put("description", "Layer a temporary mapping over the current one, for a " +
                "confirmation or quick choice. Call pop_gestures afterwards.")
            .put("parameters", schema(
                JSONObject()
                    .put("label", str("Short name."))
                    .put("mapping", mappingSchema)
                    .put("announcement", str("Spoken immediately.")),
                listOf("label", "mapping", "announcement"))))

        decls.put(decl("pop_gestures", "Return to the mapping that was active before the push.", null))

        decls.put(JSONObject()
            .put("name", "await_gesture")
            .put("description", "Wait for the user to trigger one of the controls you " +
                "bound. Returns your action name, or a timeout. This is how you hand the " +
                "turn back to the user.")
            .put("parameters", schema(
                JSONObject().put("timeout", JSONObject().put("type", "integer")),
                listOf("timeout"))))

        decls.put(decl("unbind_gestures", "Hand the controls back. Call when the task is complete.", null))

        decls.put(decl("get_battery", "Battery percentage of the glasses.", null))

        decls.put(JSONObject()
            .put("name", "set_language")
            .put("description", "Switch the conversation language: speech recognition, " +
                "your voice, and the phone's own cues. Call it as soon as the user speaks " +
                "another supported language or asks you to switch, then answer in it.")
            .put("parameters", schema(
                JSONObject().put("language", JSONObject().put("type", "string")
                    .put("enum", JSONArray(Lang.entries.map { it.code }))),
                listOf("language"))))

        decls.put(decl("heading", "Which way the user's head is pointing right now: " +
            "compass heading (0 north, 90 east), pitch, and whether the compass is " +
            "reliable. Direction only - never position.", null))

        val num = { d: String -> JSONObject().put("type", "number").put("description", d) }
        decls.put(JSONObject()
            .put("name", "steer_to")
            .put("description", "Guide the user to face a direction. The phone speaks " +
                "'left', 'right', 'a little left', and 'Stop. That way.' as they turn their " +
                "head, in real time - do not narrate the turn yourself. Returns when they " +
                "face it, when they fire a bound gesture, or on timeout. Give exactly one " +
                "of heading or turn. Say one short sentence first, e.g. 'Turn your head " +
                "slowly, I'll tell you when to stop.'")
            .put("parameters", schema(
                JSONObject()
                    .put("heading", num("Absolute compass heading to face, 0-360."))
                    .put("turn", num("Degrees relative to where they face now. " +
                        "Positive is right, negative left, 180 is behind them."))
                    .put("tolerance", num("Degrees either side that counts. Default 15."))
                    .put("timeout", JSONObject().put("type", "integer")
                        .put("description", "Seconds. Default 20.")),
                emptyList())))

        val tools = JSONArray().put(JSONObject().put("functionDeclarations", decls))
        if (useSearch) {
            // Gemini's own research. No handler, no schema - it runs these server-side.
            tools.put(JSONObject().put("googleSearch", JSONObject()))
            tools.put(JSONObject().put("urlContext", JSONObject()))
        }
        return tools
    }
}
