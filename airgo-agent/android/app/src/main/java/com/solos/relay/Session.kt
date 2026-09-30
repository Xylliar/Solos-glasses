package com.solos.relay

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything that must outlive the screen: the glasses link, the relay server, the agent
 * and the log.
 *
 * This used to live in MainActivity, and Android destroys and rebuilds an Activity on a
 * rotation (among other things). Every rebuild shut the relay down - closing the glasses
 * link cleanly (GATT status 0), cancelling the agent mid-tool and forgetting the Wi-Fi
 * state. The logs showed exactly that while walking with the phone. Here, it lives for
 * the process, which the keep-alive service holds up while connected.
 */
object Session {

    lateinit var relay: GlassesRelay
        private set
    lateinit var speech: SpeechInput
        private set
    lateinit var voice: VoiceCapture
        private set
    lateinit var live: LiveVision
        private set
    var server: RelayServer? = null
        private set
    @Volatile var agent: GeminiAgent? = null
    var agentJob: Job? = null

    /** Process-wide: survives the Activity, unlike lifecycleScope. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    const val PORT = 8765
    private val logLines = ArrayDeque<String>()
    private lateinit var app: Context
    private var ready = false

    val log: List<String> get() = synchronized(logLines) { logLines.toList() }

    /** Live view started by hand stays on after tasks. */
    @Volatile var keepLive = false

    /** Double-tap on the glasses (or their wake word) starts the agent. */
    @Volatile var launcherEnabled = true
        private set

    private var lastLine = ""
    private var lastLineAt = 0L

    fun note(line: String) {
        synchronized(logLines) {
            // Same line again within 3 s (duplicate events): keep one.
            val now = System.currentTimeMillis()
            if (line == lastLine && now - lastLineAt < 3_000) { lastLineAt = now; return }
            lastLine = line; lastLineAt = now
            logLines.addLast(line)
            while (logLines.size > 300) logLines.removeFirst()
        }
        // Also to a file, so a test done unplugged from the PC can be read afterwards
        // (adb shell run-as com.solos.relay cat files/session.log).
        runCatching {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            java.io.File(app.filesDir, "session.log").appendText("$ts $line\n")
        }
    }

    /** Idempotent: the first Activity creates the session, every later one reuses it. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        ready = true
        launcherEnabled = prefs().getBoolean("launcher", true)

        note("--- session start ---")
        registerPowerEvents()

        speech = SpeechInput(app)
        voice = VoiceCapture(app)

        // Events fan out to both consumers: the laptop (if connected) and the on-device
        // agent (if running). Neither knows about the other.
        relay = GlassesRelay(app) { event ->
            server?.broadcastEvent(event)
            agent?.onEvent(event)
            onLauncherEvent(event)
            when (event["type"]) {
                "error" -> note("ERROR [${event["where"]}]: ${event["message"]}")
                "wifi" -> note("wifi: ${event["state"]}")
                "power" -> note("power: ${event["message"]}")
                "voice" -> note("glasses heard voice command: ${event["command"]}")
                "status" -> note("glasses status: ${event["status"]}")
                "camera" -> note("camera: " + (event["state"] ?: if (event["available"] == true) "available" else "unavailable"))
                "captured" -> note("captured ${event["bytes"]} bytes in ${event["ms"]}ms (${event["resolution"]}, via ${event["via"]})")
            }
        }

        live = LiveVision(app, relay)
        relay.live = live

        server = runCatching {
            RelayServer(PORT, relay).also { it.isReuseAddr = true; it.start() }
        }.onFailure { note("relay server failed to start: ${it.message}") }.getOrNull()
    }

    private fun prefs() = app.getSharedPreferences("airgo", Context.MODE_PRIVATE)

    /** Between the wake beep and the agent starting, while we listen for the request. */
    @Volatile private var waking = false

    private val agentActive: Boolean
        get() = agent?.running == true || agentJob?.isActive == true

    private fun onLauncherEvent(event: Map<String, Any?>) {
        when {
            // Idle double-tap, or the glasses' own wake word: start a task.
            // During a task, a tap with no meaning right now: say so with a sound, or the
            // user can't tell a missed tap from one the agent is still processing.
            event["type"] == "gesture" && event["bound"] == false && agentActive &&
                event["gesture"] in setOf("SINGLE_TAP", "DOUBLE_TAP", "FORWARD_SLIDE", "REVERSE_SLIDE") ->
                relay.beepNothing()

            // "Hey Solos" in the middle of a task: barge in. Cut the AI's voice, record what
            // they say, and hand it to the running agent. If the agent is already listening
            // it hears them anyway - don't open a second mic.
            event["type"] == "gesture" && event["gesture"] == "WAKE_UP_WORD" && agentActive -> {
                val a = agent
                if (a != null && !a.listening && !waking) {
                    waking = true
                    relay.stopSpeaking()
                    note("interrupted by \"Hey Solos\" - listening")
                    scope.launch {
                        try {
                            val clip = runCatching { voice.capture(waitForSpeechMs = 4_000) }.getOrNull()
                            if (clip != null) {
                                a.interject(clip)
                                note("interruption captured: ${"%.1f".format(clip.seconds)}s -> agent")
                            } else note("interruption: nothing said")
                        } finally { waking = false }
                    }
                }
            }

            event["type"] == "gesture" && event["bound"] == false &&
                event["gesture"] in setOf("DOUBLE_TAP", "WAKE_UP_WORD") -> {
                if (launcherEnabled && !agentActive && !waking && relay.isConnected) {
                    waking = true
                    note("woken by ${event["gesture"]} - listening for the request")
                    // Listen first, then start the agent WITH the request: one Gemini turn
                    // saved and no greeting to sit through. The request goes to Gemini as
                    // raw audio from the glasses mic. Silence (just "Hey Solos") = the agent
                    // asks what they want. capture() beeps when it is ready to hear.
                    scope.launch {
                        try {
                            val clip = runCatching { voice.capture(waitForSpeechMs = 4_000) }
                                .onFailure { note("voice capture failed: ${it.message}") }
                                .getOrNull()
                            note(if (clip == null) "no request heard - the agent will ask"
                                 else "request captured: ${"%.1f".format(clip.seconds)}s (${clip.source})")
                            startAgent("", clip)
                        } finally {
                            waking = false
                        }
                    }
                }
            }
            // A fresh connection, or a link back after a drop: be ready for the tap.
            event["type"] == "connected" ||
                (event["type"] == "status" && event["status"] == "RESTORED") -> armLauncher()
        }
    }

    fun armLauncher() {
        if (!launcherEnabled || agentActive) return
        scope.launch {
            runCatching { relay.binder?.armLauncher() }
                .onFailure { note("could not arm double-tap: ${SolosKey.explain(it)}") }
            relay.enableWakeWord()
        }
    }

    fun setLauncher(enabled: Boolean) {
        launcherEnabled = enabled
        prefs().edit().putBoolean("launcher", enabled).apply()
        if (enabled) armLauncher()
        else scope.launch {
            runCatching { relay.binder?.disarmLauncher() }
            relay.restoreVoiceCommand()
        }
        note(if (enabled) "double-tap to start: ON" else "double-tap to start: OFF (back to the phone's assistant)")
    }

    fun startAgent(goal: String, request: VoiceCapture.Clip? = null) {
        if (agentActive) return
        val a = GeminiAgent(
            relay = relay,
            speech = speech,
            voice = voice,
            apiKey = BuildConfig.GEMINI_API_KEY,
            model = BuildConfig.GEMINI_MODEL,
            useSearch = BuildConfig.GEMINI_USE_SEARCH,
            thinking = BuildConfig.GEMINI_THINKING,
            onLog = ::note,
        )
        agent = a
        note("agent starting...")
        // Live view for the length of the task when the glasses are on Wi-Fi: looks become
        // instant and look_around costs only the turning. Started in parallel - the stream
        // takes a few seconds to come up and the first turn doesn't need it yet.
        if (relay.wifiConnected && !live.running) scope.launch { live.start() }
        agentJob = scope.launch {
            runCatching { a.run(goal.ifBlank { null }, request) }
                .onFailure {
                    // Stop button = cancellation: that's a normal end, not a crash.
                    if (it is kotlinx.coroutines.CancellationException) return@onFailure
                    android.util.Log.e("Session", "agent crashed", it)
                    note("AGENT CRASHED (${it.javaClass.simpleName}): ${it.message}")
                    it.cause?.let { c -> note("  caused by: ${c.javaClass.simpleName}: ${c.message}") }
                    runCatching { relay.binder?.release() }
                }
            agentJob = null
            if (!keepLive) runCatching { live.stop() }   // the stream drains the battery
            armLauncher()   // task over: the next double-tap starts another
        }
    }

    fun stopAgent() {
        agent?.stop()          // cancels the request and flushes queued speech
        agentJob?.cancel()
        relay.stopSpeaking()
        // Cancelling mid-turn skips the agent's own cleanup, so release the controls here.
        scope.launch {
            runCatching { relay.binder?.release() }
            agentJob = null
            armLauncher()
        }
        note("agent stopped by user")
    }

    /** Screen, charger and Doze transitions - the suspects when things only break on battery. */
    private fun registerPowerEvents() {
        val pm = app.getSystemService(PowerManager::class.java)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                note("phone: " + when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> "screen OFF"
                    Intent.ACTION_SCREEN_ON -> "screen ON"
                    Intent.ACTION_POWER_CONNECTED -> "charger/USB CONNECTED"
                    Intent.ACTION_POWER_DISCONNECTED -> "charger/USB DISCONNECTED (on battery)"
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "doze=${pm.isDeviceIdleMode}"
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "power saving=${pm.isPowerSaveMode}"
                    else -> i.action
                })
            }
        }
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        })
    }
}
