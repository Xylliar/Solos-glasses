package com.solos.relay

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import com.solosglasses.solosairgosdk.core.BrandConfiguration
import com.solosglasses.solosairgosdk.core.GlassesStatus
import com.solosglasses.solosairgosdk.core.Manager
import com.solosglasses.solosairgosdk.core.ScanCallbackListener
import com.solosglasses.solosairgosdk.core.SolosGlasses
import com.solosglasses.solosairgosdk.core.StatusChangeListener
import com.solosglasses.solosairgosdk.core.actions.CameraActions
import com.solosglasses.solosairgosdk.core.actions.supports
import com.solosglasses.solosairgosdk.core.camera.CameraAvailabilityListener
import com.solosglasses.solosairgosdk.core.camera.CameraBusyException
import com.solosglasses.solosairgosdk.core.camera.CameraComponent
import com.solosglasses.solosairgosdk.core.camera.CameraReconnectionResultType
import com.solosglasses.solosairgosdk.core.camera.CameraReference
import com.solosglasses.solosairgosdk.core.camera.CameraScanCallbackListener
import com.solosglasses.solosairgosdk.core.camera.PhotoConfiguration
import com.solosglasses.solosairgosdk.core.camera.PhotoResolution
import com.solosglasses.solosairgosdk.core.camera.PhotoTransferMethod
import com.solosglasses.solosairgosdk.core.camera.Photo
import com.solosglasses.solosairgosdk.core.camera.VideoCodec
import com.solosglasses.solosairgosdk.core.camera.VideoConfiguration
import com.solosglasses.solosairgosdk.core.camera.VideoResolution
import kotlinx.coroutines.CompletableDeferred
import com.solosglasses.solosairgosdk.core.camera.PhotoStreamListener
import com.solosglasses.solosairgosdk.core.audio.VoiceCommandListener
import com.solosglasses.solosairgosdk.core.audio.VoiceCommandState
import com.solosglasses.solosairgosdk.core.audio.VoiceCommandType
import com.solosglasses.solosairgosdk.core.wifi.WifiError
import com.solosglasses.solosairgosdk.core.wifi.WifiListener
import com.solosglasses.solosairgosdk.core.wifi.WifiStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Owns the connection to the glasses and turns the SDK into a handful of flat operations
 * the agent can call over the wire.
 *
 * Everything the agent can do to the glasses goes through here.
 */
class GlassesRelay(
    private val context: Context,
    /** Called with every event that should reach the agent. */
    private val emit: (Map<String, Any?>) -> Unit,
) : ScanCallbackListener, StatusChangeListener, CameraScanCallbackListener,
    CameraAvailabilityListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager = Manager(config = BrandConfiguration.solosAirGoV2, context = context)
    private val scanner = manager.getScanner()

    private val found = linkedMapOf<String, SolosGlasses>()
    var glasses: SolosGlasses? = null
        private set
    var binder: GestureBinder? = null
        private set
    /** Head direction (e-compass). Exists once connected; started on first use. */
    var head: HeadTracker? = null
        private set

    private var tts: TextToSpeech? = null
    /** Second engine: renders each sentence to a WAV for the demo edit. */
    private var fileTts: TextToSpeech? = null
    /** utterance id -> (text, wav path) for the demo timeline */
    private val spoken = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String?>>()
    private var ttsReady = false

    /**
     * Link state. A drop ("link loss" in the SDK's words) is NOT a disconnect: the glasses
     * object, the gesture bindings and the agent all survive it, and we reconnect under
     * them. Only [disconnect] - the user's button - really lets go.
     */
    @Volatile var linkUp = false
        private set
    @Volatile private var userDisconnected = false
    @Volatile var reconnecting = false
        private set
    private var reconnectJob: kotlinx.coroutines.Job? = null

    val isConnected: Boolean get() = glasses != null && linkUp
    /** Glasses chosen and not given up on - connected, or reconnecting. */
    val hasGlasses: Boolean get() = glasses != null

    /** Devices seen so far in this scan, newest last. Keys are what connect() takes. */
    val discovered: List<String> get() = synchronized(found) { found.keys.toList() }

    var scanning: Boolean = false
        private set

    // The camera is a SEPARATE Bluetooth device from the glasses. glasses.camera stays
    // null until it is scanned for and connected on its own - which is why captures
    // silently did nothing before this existed.
    private val cameras = mutableListOf<CameraReference>()
    private val cameraLock = Mutex()

    val cameraAvailable: Boolean get() = glasses?.camera != null

    companion object {
        private const val TAG = "GlassesRelay"
    }

    init {
        scanner.addScanCallbackListener(this)
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) setLanguage(Lang.current)
            // Demo recording: exact moments the AI voice starts and stops.
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String) {
                    val (text, file) = spoken[id] ?: return
                    DemoRecorder.event("ai_speech_start", "id" to id, "text" to text, "file" to file)
                }
                override fun onDone(id: String) { DemoRecorder.event("ai_speech_end", "id" to id) }
                override fun onStop(id: String, interrupted: Boolean) {
                    DemoRecorder.event("ai_speech_end", "id" to id, "interrupted" to true)
                }
                @Deprecated("") override fun onError(id: String) { DemoRecorder.event("ai_speech_end", "id" to id, "error" to true) }
            })
            Log.i(TAG, "tts ready=$ttsReady")
        }
        // A second engine renders each sentence to a WAV for the demo edit, without
        // delaying the voice the user hears.
        fileTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) runCatching { fileTts?.language = Lang.current.locale }
        }
    }

    // ---------------------------------------------------------------- scan & connect

    suspend fun startScan() {
        synchronized(found) { found.clear() }
        scanning = true
        scanner.startScan()
        emit(mapOf("type" to "scanning", "on" to true))
    }

    fun stopScan() {
        scanning = false
        scanner.stopScan()
        emit(mapOf("type" to "scanning", "on" to false))
    }

    override fun onScanResult(glasses: SolosGlasses) {
        // getSerialNumber() is suspend and this callback is not, so key on the name.
        val key = glasses.name ?: "device-${glasses.hashCode()}"
        val isNew = synchronized(found) { found.put(key, glasses) == null }
        if (isNew) {
            emit(mapOf("type" to "device", "id" to key, "name" to glasses.name))
        }
    }

    /** Connect to a device seen during the scan. Pass null to take the first one found. */
    suspend fun connect(id: String?): Boolean {
        val target = synchronized(found) {
            if (id == null) found.values.firstOrNull() else found[id]
        }
        if (target == null) {
            emit(mapOf("type" to "error", "where" to "connect", "message" to "no such device: $id"))
            return false
        }
        runCatching { target.removeStatusChangeListener(this) }   // never twice: doubles every event
        target.addStatusChangeListener(this)
        userDisconnected = false
        stopScan()   // the SDK demo stops scanning before connecting
        try {
            target.connect()
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            emit(mapOf("type" to "error", "where" to "connect", "message" to "${e.message}"))
            return false
        }
        // cameraScanner only exists once the glasses are connected, so wire it up now.
        runCatching { target.removeCameraAvailabilityListener(this); target.addCameraAvailabilityListener(this) }
        runCatching {
            target.cameraScanner?.removeScanCallbackListener(this)
            target.cameraScanner?.addScanCallbackListener(this)
        }
        runCatching { target.wifi?.removeWifiListener(wifiListener); target.wifi?.addWifiListener(wifiListener) }
        // Wi-Fi photos are fetched from an SFTP server on the glasses. Without this the SDK
        // has no password and every Wi-Fi transfer fails with "Auth fail".
        runCatching { target.wifi?.fileServerPassword = BuildConfig.SOLOS_FILE_PASSWORD }
        glasses = target
        linkUp = true
        head = HeadTracker(target)
        binder = GestureBinder(
            glasses = target,
            onAction = { action, gesture, label ->
                DemoRecorder.event("gesture", "gesture" to gesture, "action" to action, "context" to label)
                beep(ToneGenerator.TONE_PROP_ACK, 90)   // "got it" - the tap did something
                emit(
                    mapOf(
                        "type" to "action",
                        "action" to action,
                        "gesture" to gesture,
                        "context" to label,
                    )
                )
            },
            onUnboundGesture = { gesture ->
                DemoRecorder.event("gesture", "gesture" to gesture)
                emit(mapOf("type" to "gesture", "gesture" to gesture, "bound" to false))
            },
        )
        emit(mapOf("type" to "connected", "id" to id, "name" to target.name))
        // Bring the camera up now rather than on the agent's first look(), so the first
        // capture doesn't pay for a reconnect.
        scope.launch {
            if (ensureCamera()) clearStaleStream("on connect")
        }
        scope.launch { holdAwake(target) }
        return true
    }

    // ---------------------------------------------------------------- camera

    override fun onScanResult(camera: CameraReference) {
        synchronized(cameras) { if (!cameras.contains(camera)) cameras.add(camera) }
        Log.i(TAG, "camera found: ${camera.name}")
    }

    override fun onCameraAvailabilityChanged(available: Boolean) {
        Log.i(TAG, "camera available=$available")
        emit(mapOf("type" to "camera", "available" to available))
    }

    /** Why the last camera operation failed, for the agent and the on-screen log. */
    @Volatile var lastCameraError: String? = null
        private set

    private fun cameraError(message: String): Boolean {
        lastCameraError = message
        Log.w(TAG, message)
        emit(mapOf("type" to "error", "where" to "camera", "message" to message))
        return false
    }

    /**
     * Make sure the camera is connected, connecting it if not.
     *
     * Safe to call before every capture: it returns immediately when the camera is
     * already up. Concurrent callers wait for the same attempt instead of failing.
     *
     * Order matches the SDK: reconnectCamera() first - it uses the camera stored on the
     * glasses and needs no scan - and only fall back to a camera scan when nothing is
     * stored (first pairing) or the reconnect times out.
     */
    suspend fun ensureCamera(scanTimeoutMs: Long = 30_000): Boolean = cameraLock.withLock {
        val g = glasses ?: return@withLock cameraError("glasses not connected")
        if (g.camera != null) return@withLock true
        // With the link down the SDK answers CAMERA_NOT_CONFIGURED, which reads as "these
        // glasses have no camera". Don't ask until the link is back.
        if (!linkUp) return@withLock cameraError("glasses link down - camera will reconnect with it")

        emit(mapOf("type" to "camera", "available" to false, "state" to "connecting"))

        // 1. Fast path: the camera the glasses already know about.
        val r = runCatching { g.reconnectCamera() }
            .onFailure { Log.w(TAG, "reconnectCamera threw", it) }
            .getOrNull()
        Log.i(TAG, "reconnectCamera -> ${r?.type} ${r?.message}")
        if (r?.type == CameraReconnectionResultType.CAMERA_NOT_CONFIGURED) {
            return@withLock cameraError("this brand configuration has no camera")
        }
        if (r?.isCameraConnected() == true && awaitCamera(g)) {
            emit(mapOf("type" to "camera", "available" to true))
            return@withLock true
        }

        // 2. Slow path: scan for it, exactly as the SDK demo's camera dialog does.
        val scanner = g.cameraScanner
            ?: return@withLock cameraError("no camera scanner - these glasses may have no camera")
        synchronized(cameras) { cameras.clear() }
        scanner.startScan()
        val found = withTimeoutOrNull(scanTimeoutMs) {
            while (synchronized(cameras) { cameras.isEmpty() }) delay(300)
            synchronized(cameras) { cameras.first() }
        }
        scanner.stopScan()   // the SDK advises stopping the scan before connect()
        if (found == null) {
            return@withLock cameraError(
                "no camera found in ${scanTimeoutMs / 1000}s (reconnect: ${r?.type}) - " +
                    "is the camera module powered on?"
            )
        }
        Log.i(TAG, "connecting camera ${found.name}")
        runCatching { found.connect() }.onFailure {
            return@withLock cameraError("camera connect failed: ${SolosKey.explain(it)}")
        }
        if (!awaitCamera(g)) return@withLock cameraError("camera connected but never became available")
        lastCameraError = null
        emit(mapOf("type" to "camera", "available" to true))
        true
    }

    /** connect() returning does not mean the component is live yet. */
    private suspend fun awaitCamera(g: SolosGlasses): Boolean =
        withTimeoutOrNull(10_000) { while (g.camera == null) delay(200); true } == true

    /**
     * Pick a resolution the camera actually supports, closest to what was asked for.
     * Hardcoding 320x240 fails on cameras that don't offer it.
     */
    private fun pickResolution(cam: CameraComponent, detail: String): PhotoResolution {
        val targetPixels = when (detail) {
            "quick" -> 320 * 240
            "medium" -> 640 * 480
            else -> 1280 * 960
        }
        val supported = runCatching { cam.supportedPhotoResolutions }.getOrNull().orEmpty()
        if (supported.isEmpty()) {
            return when (detail) {
                "quick" -> PhotoResolution.RESOLUTION_320_240
                "medium" -> PhotoResolution.RESOLUTION_640_480
                else -> PhotoResolution.RESOLUTION_1280_960
            }
        }
        return supported.minBy { abs(pixels(it) - targetPixels) }
    }

    private fun pixels(r: PhotoResolution): Int {
        // Entries are named RESOLUTION_<w>_<h>.
        val parts = r.name.split('_')
        return runCatching { parts[1].toInt() * parts[2].toInt() }.getOrDefault(Int.MAX_VALUE)
    }

    /**
     * V1 and V2 cameras take different configuration shapes, and building the wrong one
     * does not throw - it just fails at capture. Ask the SDK which one it supports, the
     * same way the SDK demo does.
     */
    private fun photoConfig(g: SolosGlasses, resolution: PhotoResolution): PhotoConfiguration? {
        // isLEDIndicationEnabled: the camera's own capture LED. Default is on; said
        // explicitly so nobody "optimises" it away - people around should see it.
        val v1 = runCatching { PhotoConfiguration.v1(resolution, isLEDIndicationEnabled = true) }.getOrNull()
        if (v1 != null && runCatching { g.supports(CameraActions.GetPhoto(v1)) }.getOrDefault(false)) {
            return v1
        }
        // shutterSound = the glasses click when the photo is taken: the user's cue that
        // the camera fired, well before the transfer finishes.
        val v2 = runCatching {
            PhotoConfiguration.v2(resolution, isLEDIndicationEnabled = true, shutterSound = true)
        }.getOrNull()
        if (v2 != null && runCatching { g.supports(CameraActions.GetPhoto(v2)) }.getOrDefault(false)) {
            return v2
        }
        Log.w(TAG, "neither v1 nor v2 photo config reported as supported for $resolution")
        return v2 ?: v1
    }

    override fun onStatusChanged(status: GlassesStatus) {
        emit(mapOf("type" to "status", "status" to status.name))
        val g = glasses ?: return
        when (status) {
            GlassesStatus.DISCONNECTED -> {
                if (userDisconnected || !linkUp) return
                linkUp = false
                Log.w(TAG, "link lost - reconnecting")
                // Out loud: with Bluetooth gone this comes from the phone's speaker, which
                // is exactly the cue the user needs.
                speak(if (Lang.current == Lang.FR) "Connexion perdue, je me reconnecte."
                      else "Lost the glasses. Reconnecting.", true)
                startReconnect(g)
            }
            GlassesStatus.CONNECTED -> {
                if (linkUp) return
                linkUp = true
                reconnecting = false
                reconnectJob?.cancel()
                Log.i(TAG, "link back")
                scope.launch { restoreSession(g) }
            }
        }
    }

    /**
     * The SDK retries on its own after a link loss (QccAutoConnectManager in the logs), so
     * give it a head start before calling connect() ourselves - two reconnect attempts at
     * once fight each other.
     */
    private fun startReconnect(g: SolosGlasses) {
        reconnectJob?.cancel()
        reconnecting = true
        reconnectJob = scope.launch {
            delay(12_000)
            var attempt = 0
            while (!linkUp && !userDisconnected) {
                attempt++
                emit(mapOf("type" to "status", "status" to "RECONNECTING (attempt $attempt)"))
                runCatching { g.connect() }
                    .onFailure { Log.w(TAG, "reconnect attempt $attempt failed: ${it.message}") }
                if (g.status == GlassesStatus.CONNECTED && !linkUp) onStatusChanged(GlassesStatus.CONNECTED)
                if (!linkUp) delay(8_000)
            }
        }
    }

    /** Put back everything a link loss may have dropped on the glasses' side. */
    private suspend fun restoreSession(g: SolosGlasses) {
        speak(if (Lang.current == Lang.FR) "Reconnecté." else "Reconnected.", false)
        runCatching { binder?.reclaim() }.onFailure { Log.w(TAG, "re-claim failed", it) }
        runCatching { head?.restart() }.onFailure { Log.w(TAG, "compass restart failed", it) }
        if (g.camera == null) ensureCamera()
        emit(mapOf("type" to "status", "status" to "RESTORED"))
    }

    /** Wait for a dropped link to come back. True if it's up. */
    suspend fun awaitLink(timeoutMs: Long = 30_000): Boolean {
        if (linkUp) return true
        if (glasses == null) return false
        return withTimeoutOrNull(timeoutMs) { while (!linkUp) delay(200); true } == true
    }

    suspend fun disconnect() {
        runCatching { live?.stop() }
        restoreVoiceCommand()
        userDisconnected = true
        reconnectJob?.cancel()
        reconnecting = false
        restorePowerOff(glasses)
        runCatching { binder?.release() }
        runCatching { head?.stop() }
        head = null
        runCatching { glasses?.disconnect() }
        glasses = null
        binder = null
        linkUp = false
    }

    // ---------------------------------------------------------------- tools

    /**
     * Grab a frame from the glasses camera.
     *
     * "quick"    - smallest supported (~320x240). About a second over BLE. Enough to tell
     *              whether something is there. Cannot read text at any distance.
     * "medium"   - ~640x480.
     * "detailed" - ~1280x960. Readable. Several seconds over BLE.
     *
     * Returns base64 JPEG so it can ride the same JSON channel as everything else. On
     * null, [lastCameraError] says why.
     */
    suspend fun look(detail: String, transfer: String?): String? {
        // Live view running: the newest stream frame IS what they see - no capture, no
        // transfer. (The camera is busy streaming anyway, so getPhoto would fight it.)
        live?.takeIf { it.running && !transfer.equals("WIFI_ONLY", true) }?.let { lv ->
            val f = lv.latest() ?: withTimeoutOrNull(2_500) {
                var x: LiveVision.LiveFrame? = null
                while (x == null) { delay(100); x = lv.latest() }
                x
            }
            if (f != null) {
                lastPhoto = PhotoStat(asked = "live", bytes = f.jpeg.size, ms = 0, wifiFailure = null, ok = true)
                emit(mapOf("type" to "captured", "bytes" to f.jpeg.size, "ms" to 0, "detail" to detail,
                           "resolution" to "live 640", "via" to "live stream"))
                return Base64.encodeToString(f.jpeg, Base64.NO_WRAP)
            }
            Log.w(TAG, "live view running but no fresh frame - falling back to a photo")
        }
        val g = glasses ?: run { cameraError("glasses not connected"); return null }
        if (!awaitLink()) { cameraError("glasses link lost and did not come back in 30s"); return null }
        if (g.camera == null) {
            Log.i(TAG, "camera not connected - connecting now")
            if (!ensureCamera()) return null
        }
        val cam = g.camera ?: run { cameraError("camera dropped before capture"); return null }
        val resolution = pickResolution(cam, detail)
        val config = photoConfig(g, resolution)
            ?: run { cameraError("could not build a photo configuration for $resolution"); return null }
        // Wi-Fi whenever the glasses are on a network: BLE tops out around tens of kB/s,
        // which is what made every look() take seconds. BLE stays as the fallback.
        val useWifi = when (transfer?.uppercase()) {
            "WIFI", "WI_FI", "WIFI_ONLY" -> true
            "BLE" -> false
            else -> wifiConnected
        }
        val methods = when {
            transfer.equals("WIFI_ONLY", true) -> listOf(PhotoTransferMethod.WiFi)   // the test button
            useWifi -> listOf(PhotoTransferMethod.WiFi, PhotoTransferMethod.BLE)
            else -> listOf(PhotoTransferMethod.BLE, PhotoTransferMethod.BLE)
        }
        var wifiFailure: String? = null
        var streamCleared = false
        // Budget scales with size: a large JPEG over BLE genuinely takes tens of seconds.
        val budget = if (pixels(resolution) <= 640 * 480) 25_000L else 90_000L

        // "Photo taken" is a separate event from "photo arrived" - it fires as soon as
        // the shutter does, so the user hears the click before the slow transfer.
        // V2 cameras click on their own (shutterSound); V1 ones get a beep from us.
        val v1 = config.javaClass.simpleName.contains("V1", ignoreCase = true)
        val shutter = CameraComponent.PhotoListener { if (v1) beep(ToneGenerator.TONE_PROP_BEEP, 80) }
        runCatching { cam.addPhotoListener(shutter) }

        val t0 = System.currentTimeMillis()
        var failure: Throwable? = null
        var used = methods.first()
        val bytes = try {
            withTimeoutOrNull(budget) {
                for ((attempt, m) in methods.withIndex()) {
                    used = m
                    try {
                        return@withTimeoutOrNull cam.getPhoto(config, preferredTransferMethod = m).data
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = e
                        if (m == PhotoTransferMethod.WiFi) wifiFailure = SolosKey.explain(e)
                        Log.e(TAG, "getPhoto attempt ${attempt + 1} via $m failed: ${e.message}", e)
                        // A camera still streaming (left over by a dead process) answers
                        // photos with a timeout: stop the stream and try again once.
                        if (e.message?.contains("TIMEOUT", true) == true && !streamCleared) {
                            streamCleared = true
                            clearStaleStream("photo timed out")
                            delay(1_500)
                            runCatching {
                                return@withTimeoutOrNull cam.getPhoto(config, preferredTransferMethod = m).data
                            }.onFailure { failure = it }
                        }
                        // Retry on a busy camera, or fall back from Wi-Fi to BLE.
                        if (e !is CameraBusyException && m != PhotoTransferMethod.WiFi) {
                            return@withTimeoutOrNull null
                        }
                        if (e is CameraBusyException) delay(1_000)
                    }
                }
                null
            }
        } finally {
            runCatching { cam.removePhotoListener(shutter) }
        }
        val ms = System.currentTimeMillis() - t0
        if (bytes == null) {
            val why = failure?.let { SolosKey.explain(it) }
                ?: "timed out after ${budget / 1000}s"
            lastPhoto = PhotoStat(asked = if (useWifi) "wifi" else "ble", bytes = 0, ms = ms,
                wifiFailure = wifiFailure ?: if (useWifi) why else null, ok = false)
            cameraError("capture failed after ${ms}ms ($resolution, ${config.javaClass.simpleName}): $why")
            return null
        }
        lastCameraError = null
        beep(ToneGenerator.TONE_PROP_ACK, 150)   // "got it" - the image is in hand
        lastPhotoResolution = resolution.name
        val stat = PhotoStat(asked = if (used == PhotoTransferMethod.WiFi) "wifi" else "ble",
            bytes = bytes.size, ms = ms, wifiFailure = wifiFailure, ok = true)
        lastPhoto = stat
        // "via" is what we ASKED for - the SDK only takes a preference and never says what
        // it actually used. The measured speed is the proof.
        val via = "${stat.asked} (${stat.kbps} KB/s = ${stat.linkBySpeed})"
        Log.i(TAG, "captured ${bytes.size} bytes in ${ms}ms ($resolution, asked ${stat.asked}, " +
            "${stat.kbps} KB/s => ${stat.linkBySpeed})" + (wifiFailure?.let { ", wifi failed: $it" } ?: ""))
        emit(mapOf("type" to "captured", "bytes" to bytes.size, "ms" to ms, "detail" to detail,
                   "resolution" to resolution.name, "via" to via))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Live video frames, when the stream is running (set by Session). */
    @Volatile var live: LiveVision? = null
    val liveRunning: Boolean get() = live?.running == true

    /**
     * If this app died while live view was on (install, crash, force-stop), the glasses keep
     * streaming - and a streaming camera times out every photo ("nc: TIMEOUT"). Stop any
     * stream that isn't ours.
     */
    suspend fun clearStaleStream(why: String) {
        if (liveRunning) return
        val cam = glasses?.camera ?: return
        runCatching { cam.stopVideoStream() }
            .onSuccess { Log.i(TAG, "stopped any leftover video stream ($why)") }
    }

    /** A line for the log, from components that don't own an event type. */
    fun notify(message: String) = emit(mapOf("type" to "power", "message" to message))

    // ---------------------------------------------------------------- video clip

    /** A short clip from the glasses plus where the head pointed during it. */
    data class VideoClip(
        val mp4: ByteArray, val mime: String, val seconds: Int, val resolution: String,
        /** (seconds since recording start, reading) every ~0.5 s */
        val compass: List<Pair<Double, HeadTracker.Reading>>,
    ) {
        fun compassTimeline(): String =
            if (compass.isEmpty()) "no compass data"
            else compass.joinToString("; ") { (t, r) ->
                "t=${"%.1f".format(t)}s ${r.yaw.toInt()} deg (${r.compass})"
            } + if (compass.any { !it.second.reliable }) " [compass UNRELIABLE for part of it]" else ""
    }

    /**
     * Record a few seconds of video on the glasses, pull it over Wi-Fi, delete it from the
     * glasses, and return it with a compass timeline. Semi-live: Gemini sees motion (at
     * ~1 frame/s by default) and hears the scene, and every moment has a direction.
     * Needs Wi-Fi: the clip is fetched from the glasses' file server.
     */
    suspend fun recordClip(seconds: Int): VideoClip? {
        val g = glasses ?: run { cameraError("glasses not connected"); return null }
        if (!awaitLink()) { cameraError("glasses link lost"); return null }
        if (!wifiConnected) { cameraError("video needs Wi-Fi - connect the glasses to Wi-Fi first"); return null }
        if (g.camera == null && !ensureCamera()) return null
        val cam = g.camera ?: return null
        val wifi = g.wifi ?: run { cameraError("no Wi-Fi component"); return null }
        val supported = runCatching { cam.supportedVideoResolutions }.getOrNull().orEmpty()
        if (supported.isEmpty()) { cameraError("this camera does not record video"); return null }
        // Smallest supported: Gemini samples ~1 fps anyway, and small = fast download.
        val res = supported.minBy { r ->
            r.name.split('_').let { runCatching { it[1].toInt() * it[2].toInt() }.getOrDefault(Int.MAX_VALUE) }
        }
        val secs = seconds.coerceIn(2, 15)
        ensureHeading()

        val started = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Throwable?>()
        val listener = object : CameraComponent.RecordingStatusListener {
            override fun onRecordingStart() { started.complete(Unit) }
            override fun onRecordingEnd() { ended.complete(null) }
            override fun onRecordingFailed(reason: Throwable) { ended.complete(reason) }
        }
        val before = runCatching { wifi.getAllFiles().map { it.filename }.toSet() }.getOrDefault(emptySet())
        val compass = mutableListOf<Pair<Double, HeadTracker.Reading>>()
        val t0 = System.currentTimeMillis()
        try {
            cam.addRecordingStatusListener(listener)
            cam.startRecording(
                VideoConfiguration(res, VideoCodec.H264, isLEDIndicationEnabled = true, isSoundIndicationEnabled = true),
                secs,
            )
            if (withTimeoutOrNull(8_000) { started.await() } == null) {
                cameraError("video recording never started")
                return null
            }
            val tStart = System.currentTimeMillis()
            // Sample the compass until the glasses say the clip is done.
            val failure = withTimeoutOrNull((secs + 15) * 1000L) {
                while (!ended.isCompleted) {
                    head?.fresh?.let { compass.add((System.currentTimeMillis() - tStart) / 1000.0 to it) }
                    delay(500)
                }
                ended.await()
            }
            if (failure != null) { cameraError("recording failed: ${SolosKey.explain(failure)}"); return null }
        } catch (e: CancellationException) {
            runCatching { cam.stopRecording() }
            throw e
        } catch (e: Exception) {
            cameraError("recording failed: ${SolosKey.explain(e)}")
            return null
        } finally {
            runCatching { cam.removeRecordingStatusListener(listener) }
        }
        val tRecorded = System.currentTimeMillis()

        // The new file shows up on the glasses' server a moment after the recording ends.
        var file: com.solosglasses.solosairgosdk.core.camera.MediaFile? = null
        repeat(5) {
            if (file == null) {
                file = runCatching { wifi.getAllFiles() }.getOrNull().orEmpty()
                    .filter { it.isVideo && it.filename !in before }
                    .maxByOrNull { it.lastModified?.time ?: 0L }
                if (file == null) delay(1_000)
            }
        }
        val f = file ?: run { cameraError("recorded, but the clip never appeared on the glasses"); return null }
        val tListed = System.currentTimeMillis()

        val bytes = runCatching {
            val uri = wifi.downloadFile(f, deleteAfterDownload = true)
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                .also { runCatching { uri.path?.let { p -> java.io.File(p).delete() } } }
        }.getOrElse { cameraError("clip download failed: ${SolosKey.explain(it)}"); return null }
        val tDone = System.currentTimeMillis()

        val msg = "clip: ${secs}s ${res.name}, ${bytes.size / 1024} KB - recorded in " +
            "${"%.1f".format((tRecorded - t0) / 1000.0)}s, found in ${"%.1f".format((tListed - tRecorded) / 1000.0)}s, " +
            "downloaded in ${"%.1f".format((tDone - tListed) / 1000.0)}s " +
            "(${if (tDone > tListed) bytes.size / 1024 * 1000 / (tDone - tListed) else 0} KB/s), " +
            "total ${"%.1f".format((tDone - t0) / 1000.0)}s, ${compass.size} compass samples"
        Log.i(TAG, msg)
        emit(mapOf("type" to "power", "message" to msg))
        beep(ToneGenerator.TONE_PROP_ACK, 150)
        return VideoClip(bytes, f.mimeType.ifBlank { "video/mp4" }, secs, res.name, compass)
    }

    // ---------------------------------------------------------------- look around

    /** One frame of a sweep: the image and which way the head pointed when it arrived. */
    data class Frame(val jpegB64: String, val bytes: Int, val atMs: Long, val facing: HeadTracker.Reading?) {
        fun label(): String = "t=${"%.1f".format(atMs / 1000.0)}s, " +
            (facing?.let { "facing ${it.yaw.toInt()} degrees (${it.compass})" +
                (if (it.reliable) "" else ", compass UNRELIABLE") } ?: "direction unknown")
    }

    data class Sweep(val frames: List<Frame>, val received: Int, val seconds: Double, val resolution: String) {
        val fps: Double get() = if (seconds > 0) received / seconds else 0.0
        /** Degrees of horizon covered by the kept frames. */
        val span: Int get() {
            val yaws = frames.mapNotNull { it.facing?.yaw }.sorted()
            if (yaws.size < 2) return 0
            // largest gap on the circle; coverage is what's left
            val gaps = yaws.zipWithNext { a, b -> b - a } + (360 - yaws.last() + yaws.first())
            return (360 - (gaps.maxOrNull() ?: 360.0)).toInt()
        }
    }

    /**
     * "Look around": stream frames from the camera while the user slowly turns their head,
     * tag each one with the compass heading, and keep the frames that best cover the
     * directions seen. Semi-live: a few seconds of looking, then one Gemini turn with all
     * of it - and every frame says which way it faces, so the answer can be a direction.
     */
    suspend fun lookAround(seconds: Int, maxFrames: Int, detail: String = "medium"): Sweep? {
        val g = glasses ?: run { cameraError("glasses not connected"); return null }
        if (!awaitLink()) { cameraError("glasses link lost"); return null }
        if (g.camera == null && !ensureCamera()) return null
        val cam = g.camera ?: return null
        ensureHeading()   // tags are the point; start the compass if it isn't running

        // Live view: just watch the stream while they turn. Cost = the seconds of turning.
        live?.takeIf { it.running }?.let { lv ->
            val t0 = System.currentTimeMillis()
            delay(seconds.coerceIn(2, 20) * 1000L)
            val got = lv.since(t0).map {
                Frame(Base64.encodeToString(it.jpeg, Base64.NO_WRAP), it.jpeg.size, it.atMs - t0, it.facing)
            }
            if (got.isNotEmpty()) {
                val elapsed = (System.currentTimeMillis() - t0) / 1000.0
                val sweep = Sweep(spread(got, maxFrames), got.size, elapsed, "live 640")
                val msg = "look_around (live): ${got.size} frames in ${"%.1f".format(elapsed)}s " +
                    "(${"%.1f".format(sweep.fps)} fps), kept ${sweep.frames.size} covering ${sweep.span} degrees"
                Log.i(TAG, msg); notify(msg)
                beep(ToneGenerator.TONE_PROP_ACK, 150)
                return sweep
            }
            Log.w(TAG, "live view gave no frames - falling back")
        }
        // The photo stream is a capability not every camera has. On AirGo V2 the SDK
        // doesn't throw - it logs "No camera connected" and sends nothing - so ask first.
        val streamOk = runCatching { g.supports(com.solosglasses.solosairgosdk.core.camera.PhotoStream) }
            .getOrDefault(false)
        val stream = if (streamOk) runCatching { cam.photoStream }.getOrNull() else null
        if (stream == null) {
            Log.i(TAG, "photo stream not supported - sweeping with repeated photos")
            return sweepWithPhotos(seconds, maxFrames, if (detail == "detailed") "medium" else detail)
        }

        val resolution = pickResolution(cam, detail)
        val got = java.util.Collections.synchronizedList(mutableListOf<Frame>())
        val t0 = System.currentTimeMillis()
        val listener = PhotoStreamListener { photo: Photo ->
            val data = photo.data
            got.add(Frame(Base64.encodeToString(data, Base64.NO_WRAP), data.size,
                System.currentTimeMillis() - t0, head?.fresh))
        }
        try {
            stream.addListener(listener)
            stream.start(resolution)
            delay(seconds.coerceIn(2, 20) * 1000L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cameraError("photo stream failed: ${SolosKey.explain(e)}")
        } finally {
            runCatching { stream.stop() }
            runCatching { stream.removeListener(listener) }
        }
        val elapsed = (System.currentTimeMillis() - t0) / 1000.0
        val all = synchronized(got) { got.toList() }
        if (all.isEmpty()) {
            cameraError("photo stream: no frames in ${"%.1f".format(elapsed)}s")
            return null
        }
        val kept = spread(all, maxFrames)
        val sweep = Sweep(kept, all.size, elapsed, resolution.name)
        val msg = "look_around: ${all.size} frames in ${"%.1f".format(elapsed)}s " +
            "(${"%.1f".format(sweep.fps)} fps, avg ${all.sumOf { it.bytes } / all.size / 1024} KB, " +
            "${resolution.name}), kept ${kept.size} covering ${sweep.span} degrees"
        Log.i(TAG, msg)
        emit(mapOf("type" to "power", "message" to msg))
        beep(ToneGenerator.TONE_PROP_ACK, 150)
        return sweep
    }

    /**
     * Fallback sweep for cameras without a photo stream: take photos back to back (over
     * Wi-Fi when available, ~2.5 s each) for the given time, tagging each with the heading
     * at the shutter. Fewer frames than a stream, same labels.
     */
    private suspend fun sweepWithPhotos(seconds: Int, maxFrames: Int, detail: String): Sweep? {
        val t0 = System.currentTimeMillis()
        val frames = mutableListOf<Frame>()
        val until = t0 + seconds.coerceIn(3, 20) * 1000L
        var resolution = ""
        while (System.currentTimeMillis() < until && frames.size < maxFrames) {
            val facing = head?.fresh
            val at = System.currentTimeMillis() - t0
            val b64 = look(detail, null) ?: break
            resolution = lastPhotoResolution
            frames.add(Frame(b64, lastPhoto?.bytes ?: 0, at, facing))
        }
        val elapsed = (System.currentTimeMillis() - t0) / 1000.0
        if (frames.isEmpty()) return null
        val sweep = Sweep(frames, frames.size, elapsed, resolution)
        val msg = "look_around (photo loop): ${frames.size} photos in ${"%.1f".format(elapsed)}s " +
            "(${"%.1f".format(sweep.fps)} fps, $resolution), covering ${sweep.span} degrees"
        Log.i(TAG, msg)
        emit(mapOf("type" to "power", "message" to msg))
        return sweep
    }

    /**
     * Pick up to [n] frames that cover the most directions: greedily add the frame whose
     * heading is farthest from every heading already kept. Falls back to even spacing in
     * time when there is no compass.
     */
    private fun spread(all: List<Frame>, n: Int): List<Frame> {
        if (all.size <= n) return all
        if (all.any { it.facing == null }) {
            return (0 until n).map { all[it * (all.size - 1) / (n - 1).coerceAtLeast(1)] }
        }
        val kept = mutableListOf(all.first())
        while (kept.size < n) {
            val next = all.filter { it !in kept }.maxByOrNull { f ->
                kept.minOf { k -> kotlin.math.abs(HeadTracker.turn(k.facing!!.yaw, f.facing!!.yaw)) }
            } ?: break
            kept.add(next)
        }
        return kept.sortedBy { it.atMs }
    }

    /**
     * Speak through the glasses.
     *
     * This uses Android's TextToSpeech, which lands on the glasses because they are the
     * active Bluetooth audio output. That is deliberately NOT the SDK's PcmAudioSource
     * path: the SDK cannot play and record at the same time, and routing TTS as ordinary
     * media audio keeps that restriction out of the way.
     */
    fun speak(text: String, flush: Boolean): Boolean {
        val t = tts ?: return false
        if (!ttsReady) return false
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val n = DemoRecorder.nextId()
        val id = "ai_$n"
        var rel: String? = null
        if (DemoRecorder.active) {
            DemoRecorder.file("audio", "$id.wav")?.let { f ->
                runCatching { fileTts?.synthesizeToFile(text, null, f, "${id}_file") }
                rel = DemoRecorder.rel(f)
            }
        }
        spoken[id] = text to rel
        t.speak(text, mode, null, id)
        return true
    }


    fun stopSpeaking() { tts?.stop() }

    /** Suspend until the TTS queue has drained (or 30s, so a stuck engine can't hang us). */
    suspend fun awaitSpeechDone() {
        delay(150)   // speak() is async; give the engine a moment to report isSpeaking
        withTimeoutOrNull(30_000) { while (tts?.isSpeaking == true) delay(100) }
    }

    // ---------------------------------------------------------------- auto power-off

    /**
     * The glasses switch themselves off after a few idle minutes - which, mid-demo, looks
     * like the connection dropping (the logs showed the glasses closing the link, GATT
     * status 19). While we're connected, stretch the timeout; put it back on disconnect.
     *
     * Stretched rather than disabled: the setting is persistent, so if this app dies
     * without restoring it the glasses still turn off eventually instead of draining.
     */
    private var savedPowerOff: Pair<Boolean, Int>? = null
    private val awakeMinutes = 60

    private suspend fun holdAwake(g: SolosGlasses) {
        val sys = g.system ?: return
        runCatching {
            val enabled = sys.getAutoPowerOffEnabled()
            val minutes = sys.getAutoPowerOffTimeout()
            Log.i(TAG, "auto power-off: enabled=$enabled timeout=${minutes}min")
            emit(mapOf("type" to "power", "message" to
                "auto power-off was ${if (enabled) "on, $minutes min" else "off"}"))
            if (enabled && minutes < awakeMinutes) {
                savedPowerOff = enabled to minutes
                sys.setAutoPowerOffTimeout(awakeMinutes)
                emit(mapOf("type" to "power", "message" to
                    "auto power-off stretched to $awakeMinutes min while connected"))
            }
        }.onFailure {
            Log.w(TAG, "auto power-off check failed", it)
            emit(mapOf("type" to "error", "where" to "power", "message" to SolosKey.explain(it)))
        }
    }

    // ---------------------------------------------------------------- wake word

    /**
     * "Hey Solos" is detected by the glasses' own voice-command engine and reaches us as
     * the WAKE_UP_WORD gesture event. The engine has three modes: DEFAULT (the glasses act
     * on commands themselves), CUSTOM (they only report them) and DISABLED. While our
     * agent is the assistant we want CUSTOM; the previous mode is put back on disconnect.
     * Unlike the gesture claim this setting is not volatile, hence the restore.
     */
    private var savedVoiceMode: VoiceCommandState? = null

    private val voiceListener = object : VoiceCommandListener {
        override fun onVoiceCommandDetected(voiceCommandType: VoiceCommandType) {
            Log.i(TAG, "voice command detected: $voiceCommandType")
            emit(mapOf("type" to "voice", "command" to voiceCommandType.name))
        }
    }

    suspend fun enableWakeWord(): Boolean {
        val vc = glasses?.voiceCommand ?: run {
            emit(mapOf("type" to "power", "message" to "no voice-command engine on these glasses"))
            return false
        }
        return runCatching {
            runCatching { vc.removeVoiceCommandListener(voiceListener) }
            vc.addVoiceCommandListener(voiceListener)
            val mode = vc.state()
            if (mode != VoiceCommandState.CUSTOM) {
                if (savedVoiceMode == null) savedVoiceMode = mode
                vc.enableCustomVoiceCommand()
            }
            emit(mapOf("type" to "power", "message" to
                "voice commands: was $mode, now CUSTOM - say \"Hey Solos\" to start the agent"))
            true
        }.getOrElse {
            Log.w(TAG, "enable wake word failed", it)
            emit(mapOf("type" to "error", "where" to "voice", "message" to SolosKey.explain(it)))
            false
        }
    }

    suspend fun restoreVoiceCommand() {
        val mode = savedVoiceMode ?: return
        val vc = glasses?.voiceCommand ?: return
        runCatching {
            when (mode) {
                VoiceCommandState.DEFAULT -> vc.enableDefaultVoiceCommand()
                VoiceCommandState.DISABLED -> vc.disableVoiceCommand()
                VoiceCommandState.CUSTOM -> Unit
            }
            vc.removeVoiceCommandListener(voiceListener)
        }.onSuccess {
            savedVoiceMode = null
            Log.i(TAG, "voice commands restored to $mode")
        }.onFailure { Log.w(TAG, "voice command restore failed", it) }
    }

    private suspend fun restorePowerOff(g: SolosGlasses?) {
        val (_, minutes) = savedPowerOff ?: return
        runCatching { g?.system?.setAutoPowerOffTimeout(minutes) }
            .onSuccess { Log.i(TAG, "auto power-off restored to ${minutes}min"); savedPowerOff = null }
    }

    /** Switch the voice. Returns false if the phone has no voice for that language. */
    fun setLanguage(lang: Lang): Boolean {
        runCatching { fileTts?.language = lang.locale }
        val r = tts?.setLanguage(lang.locale) ?: return false
        val ok = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        Log.i(TAG, "tts language ${lang.code} -> $r (ok=$ok)")
        if (!ok) emit(mapOf("type" to "error", "where" to "tts",
            "message" to "no ${lang.displayName} voice installed - add it in Settings > Text-to-speech"))
        return ok
    }

    // Plays as media audio, so it comes out of the glasses like the TTS does.
    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()

    private fun beep(tone: Int, ms: Int) {
        runCatching { tones?.startTone(tone, ms) }
        DemoRecorder.event("sound", "sound" to when (tone) {
            ToneGenerator.TONE_PROP_ACK -> "ack"
            ToneGenerator.TONE_SUP_ERROR -> "nothing"
            ToneGenerator.TONE_PROP_BEEP2 -> "wake"
            ToneGenerator.TONE_PROP_BEEP -> "shutter"
            else -> "tone_$tone"
        }, "ms" to ms)
    }

    /** A tap that does nothing right now (nothing bound to it): a low "nope". */
    fun beepNothing() = beep(ToneGenerator.TONE_SUP_ERROR, 150)

    /** Instant "I heard you" - the agent's first sentence is a network round trip away. */
    fun beepWake() = beep(ToneGenerator.TONE_PROP_BEEP2, 120)

    // ---------------------------------------------------------------- wi-fi

    /**
     * Photos over Wi-Fi instead of BLE. Two SDK modes:
     *
     *   join(ssid)  - the glasses join the network the phone is on (station mode). The
     *                 phone keeps its internet, so Gemini keeps working. Needs a network
     *                 that lets devices reach each other (phone hotspots do; some venue
     *                 Wi-Fi isolates clients).
     *   hotspot()   - the phone joins the glasses' own access point (soft AP). Works
     *                 anywhere, but the phone's Wi-Fi is then the glasses, so Gemini goes
     *                 over mobile data. Fails with NO_MOBILE_DATA without a SIM.
     */
    @Volatile var wifiState: String = "off"
        private set
    /** "hotspot" (phone joined the glasses) or "join" (glasses joined a network). */
    @Volatile var wifiMode: String? = null
        private set
    @Volatile private var wifiListenerUp = false

    /**
     * One photo's transfer. Measured on these glasses, shutter and session setup included:
     * Bluetooth ~8 KB/s (48 KB in 6.2 s), Wi-Fi ~14 KB/s for a small photo (37 KB in 2.6 s -
     * mostly fixed overhead, so bigger photos gain more). Over 11 KB/s cannot be Bluetooth.
     */
    data class PhotoStat(val asked: String, val bytes: Int, val ms: Long,
                         val wifiFailure: String?, val ok: Boolean) {
        val kbps: Int get() = if (ms > 0) (bytes * 1000L / 1024 / ms).toInt() else 0
        val linkBySpeed: String get() = if (kbps > 11) "WI-FI" else "BLUETOOTH SPEED"
    }

    @Volatile var lastPhoto: PhotoStat? = null
        private set
    @Volatile var lastPhotoResolution: String = ""
        private set

    /** One line that answers "are photos going over Wi-Fi?" without interpretation. */
    fun photoLinkSummary(): Pair<String, Int> {
        // Int: 0 = good, 1 = unknown/untested, 2 = bad
        val p = lastPhoto
        return when {
            liveRunning -> "PHOTOS: LIVE STREAM - instant frames (${"%.1f".format(live?.fps ?: 0.0)} fps)" to 0
            !wifiConnected && p?.wifiFailure != null ->
                "PHOTOS: BLUETOOTH (slow) - Wi-Fi failed: ${p.wifiFailure}" to 2
            !wifiConnected -> "PHOTOS: BLUETOOTH (slow) - Wi-Fi not connected" to 2
            p == null || p.asked == "ble" && p.wifiFailure == null ->
                "Wi-Fi connected - not proven yet, press Test Wi-Fi" to 1
            !p.ok -> "PHOTOS: last Wi-Fi photo FAILED - ${p.wifiFailure}" to 2
            p.wifiFailure != null ->
                "PHOTOS: fell back to BLUETOOTH - Wi-Fi failed: ${p.wifiFailure}" to 2
            p.linkBySpeed == "WI-FI" ->
                "PHOTOS: WI-FI OK - last ${p.bytes / 1024} KB in ${"%.1f".format(p.ms / 1000.0)} s (${p.kbps} KB/s)" to 0
            else -> "PHOTOS: Wi-Fi connected but last photo came at Bluetooth speed " +
                "(${p.kbps} KB/s) - the SDK likely fell back" to 2
        }
    }

    /** Details under the summary: which mode, which network, which address. */
    fun wifiDetails(): String {
        if (!wifiConnected) return "wifi: $wifiState"
        val w = glasses?.wifi
        val mode = when (wifiMode) { "hotspot" -> "glasses hotspot"; "join" -> "joined network"; else -> "connected" }
        return "wifi: $mode - ${w?.ssid?.get() ?: "?"} ${w?.ipAddress?.get() ?: ""} ${w?.band?.get() ?: ""}".trim()
    }

    val wifiConnected: Boolean
        get() = glasses != null && (wifiListenerUp ||
            runCatching { glasses?.wifi?.status?.get() == WifiStatus.CONNECTED }.getOrDefault(false))

    /** The Wi-Fi network the phone is on, as the SDK sees it. */
    val phoneSsid: String?
        get() = runCatching { glasses?.wifi?.currentDeviceSSID?.value }.getOrNull()
            ?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

    private val wifiListener = object : WifiListener {
        override fun onWifiConnected() {
            wifiListenerUp = true
            val w = glasses?.wifi
            wifiState = "connected to ${w?.ssid?.get()} (${w?.ipAddress?.get()}, ${w?.band?.get()})"
            emit(mapOf("type" to "wifi", "state" to wifiState))
        }
        override fun onWifiConnecting() {
            wifiState = "connecting..."
            emit(mapOf("type" to "wifi", "state" to wifiState))
        }
        override fun onWifiDisconnected() {
            wifiListenerUp = false
            wifiState = "off"
            emit(mapOf("type" to "wifi", "state" to wifiState))
        }
        override fun onWifiConnectionError(error: WifiError) {
            wifiListenerUp = false
            wifiState = "error: $error" + when (error) {
                WifiError.NO_MOBILE_DATA -> " (hotspot mode needs mobile data for internet)"
                WifiError.SOFT_AP_JOIN_FAILED -> " (phone could not join the glasses' hotspot)"
                WifiError.TIMEOUT -> " (wrong password, or network out of range?)"
                else -> ""
            }
            emit(mapOf("type" to "error", "where" to "wifi", "message" to wifiState))
        }
    }

    suspend fun wifiJoin(ssid: String, password: String): Boolean {
        val w = glasses?.wifi ?: run { wifiState = "no wifi on these glasses"; return false }
        wifiMode = "join"
        runCatching { w.connect(ssid, password) }.onFailure {
            wifiState = "error: ${SolosKey.explain(it)}"
            emit(mapOf("type" to "error", "where" to "wifi", "message" to wifiState))
            return false
        }
        return withTimeoutOrNull(60_000) { while (!wifiConnected) delay(300); true } == true
    }

    suspend fun wifiHotspot(): Boolean {
        val w = glasses?.wifi ?: run { wifiState = "no wifi on these glasses"; return false }
        wifiMode = "hotspot"
        runCatching { w.connectAsSoftAP() }.onFailure {
            wifiState = "error: ${SolosKey.explain(it)}"
            emit(mapOf("type" to "error", "where" to "wifi", "message" to wifiState))
            return false
        }
        // connectAsSoftAP() can return before the status flips - wait for it.
        return withTimeoutOrNull(30_000) { while (!wifiConnected) delay(300); true } == true
    }

    /** True when the last Wi-Fi transfer was refused by the glasses' file server. */
    val wifiAuthFailed: Boolean
        get() = lastPhoto?.wifiFailure?.contains("Auth", ignoreCase = true) == true

    /**
     * Last resort for "Auth fail": put the file-server password back to the factory
     * "solos". The SDK warns this DELETES EVERY FILE on the glasses and turns off low-power
     * mode, so it only ever runs from a button the user confirms.
     */
    suspend fun resetFilePassword(): String {
        val w = glasses?.wifi ?: return "reset: glasses not connected"
        Log.i(TAG, "file-server password reset: starting (wifi connected=$wifiConnected)")
        val t0 = System.currentTimeMillis()
        val r = runCatching { withTimeoutOrNull(60_000) { w.resetFileServerPassword(); true } }
        val ms = System.currentTimeMillis() - t0
        val msg = r.fold(
            onSuccess = { done ->
                if (done == true) {
                    lastPhoto = null
                    "file-server password reset OK in ${ms}ms (SDK password now " +
                        "'${runCatching { w.fileServerPassword }.getOrNull()}') - press Test Wi-Fi"
                } else "reset TIMED OUT after 60s - the glasses never confirmed"
            },
            onFailure = { "reset FAILED after ${ms}ms: ${it.javaClass.simpleName}: ${it.message}" },
        )
        Log.i(TAG, "file-server password reset: $msg")
        r.exceptionOrNull()?.let { Log.e(TAG, "reset exception", it) }
        return msg
    }

    /** Take one medium photo over Wi-Fi only, no Bluetooth fallback, and say what happened. */
    suspend fun testWifi(): String {
        if (!wifiConnected) return "Wi-Fi test: not connected to Wi-Fi"
        if (liveRunning) return "Wi-Fi test skipped: live view is on - photos come from the stream (instant)"
        val img = look("medium", "WIFI_ONLY")
        val p = lastPhoto
        return when {
            img == null -> "Wi-Fi test FAILED: ${p?.wifiFailure ?: lastCameraError}"
            p != null && p.linkBySpeed == "WI-FI" ->
                "Wi-Fi test OK: ${p.bytes / 1024} KB in ${"%.1f".format(p.ms / 1000.0)} s (${p.kbps} KB/s)"
            p != null -> "Wi-Fi test: photo arrived but at Bluetooth speed (${p.kbps} KB/s)"
            else -> "Wi-Fi test: unknown result"
        }
    }

    suspend fun wifiOff() {
        runCatching { glasses?.wifi?.disconnect() }
        wifiListenerUp = false
        wifiMode = null
        wifiState = "off"
    }

    // ---------------------------------------------------------------- heading

    /**
     * Start the orientation stream if it isn't running, and wait briefly for a first
     * reading. Returns the reading, or null with [lastHeadingError] set.
     */
    suspend fun ensureHeading(): HeadTracker.Reading? {
        val h = head ?: run { lastHeadingError = "glasses not connected"; return null }
        if (!h.running) {
            runCatching { h.start() }.onFailure {
                lastHeadingError = "orientation sensor failed to start: ${SolosKey.explain(it)}"
                Log.e(TAG, "heading start failed", it)
                emit(mapOf("type" to "error", "where" to "heading", "message" to lastHeadingError))
                return null
            }
        }
        val r = withTimeoutOrNull(3_000) {
            while (h.fresh == null) delay(100)
            h.fresh
        }
        if (r == null) lastHeadingError = "orientation sensor started but sent no data"
        return r
    }

    @Volatile var lastHeadingError: String? = null
        private set

    suspend fun battery(): Int? = runCatching { glasses?.battery?.getBatteryLevel() }.getOrNull()

    suspend fun firmware(): String? =
        runCatching { glasses?.system?.getFirmwareVersion()?.toString() }.getOrNull()

    // ---------------------------------------------------------------- lifecycle

    fun shutdown() {
        scope.launch { runCatching { disconnect() } }
        scanner.removeScanCallbackListener(this)
        runCatching { scanner.release() }
        tts?.shutdown()
    }
}
