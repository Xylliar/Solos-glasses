package com.solos.relay

import android.util.Log
import com.solosglasses.solosairgosdk.core.SolosGlasses
import com.solosglasses.solosairgosdk.core.gesture.GestureConfiguration
import com.solosglasses.solosairgosdk.core.gesture.GestureListener
import com.solosglasses.solosairgosdk.core.gesture.GestureStorageType
import com.solosglasses.solosairgosdk.core.gesture.GestureType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ephemeral gesture remapping.
 *
 * The SDK cannot map a gesture to an arbitrary action in firmware. What it CAN do is
 * disable the glasses' built-in behaviours (slide=volume, double-tap=voice assistant, ...)
 * so those gestures stop being swallowed and arrive here as raw events instead.
 *
 * So remapping is two layers:
 *   1. ONE firmware call at session start to "claim" the gestures  (slow, ~BLE round trip)
 *   2. An in-memory table that says what each gesture currently means  (instant, free)
 *
 * Layer 2 is what the agent rewrites, so rebinding costs nothing and can happen
 * on every step of a task.
 *
 * Bindings are a STACK. A sub-task pushes a context ("yes/no?"), gets its answer, and
 * pops back to whatever was underneath. That keeps an agent from having to remember and
 * restore the previous mapping by hand.
 *
 * Safety: the firmware config is written with GestureStorageType.VOLATILE, so even if this
 * process dies without cleaning up, the user's glasses return to normal on reboot.
 */
class GestureBinder(
    private val glasses: SolosGlasses,
    private val onAction: (action: String, gesture: String, label: String) -> Unit,
    private val onUnboundGesture: (gesture: String) -> Unit = {},
) : GestureListener {

    data class Context(
        val label: String,
        /** gesture name (see [GESTURES]) -> action name the agent will receive back */
        val map: Map<String, String>,
    )

    private val stack = ArrayDeque<Context>()
    private val lock = Mutex()
    private var claimed = false

    val current: Context? get() = stack.lastOrNull()

    /** Gesture names accepted in a binding map. Matches SDK [GestureType] names. */
    companion object {
        val GESTURES = listOf(
            "SINGLE_TAP",
            "DOUBLE_TAP",
            "FORWARD_SLIDE",
            "REVERSE_SLIDE",
            "SLIDE_PRESSED",
            "SLIDE_RELEASED",
            "VB_PRESSED",
            "VB_RELEASED",
        )
        private const val TAG = "GestureBinder"
    }

    // ---------------------------------------------------------------- claim / release

    /**
     * Take the gestures away from the glasses' built-in behaviours.
     *
     * Call once when a session starts. Until this runs, a slide will change the volume
     * and a double-tap will fire the phone's voice assistant instead of reaching us.
     *
     * We deliberately leave VB (the power button) alone by default - stealing power-off
     * from a user is hostile, and we don't need it.
     */
    suspend fun claim(stealPowerButton: Boolean = false) = lock.withLock {
        if (claimed) return@withLock
        glasses.gesture?.let { g ->
            // Remove first so a re-claim after a link loss never doubles every gesture.
            runCatching { g.removeGestureListener(this) }
            g.addGestureListener(this)
            val config = GestureConfiguration(
                storageType = GestureStorageType.VOLATILE,
                enableVbForPowerOff = if (stealPowerButton) false else null,
                enableSlideForVolumeControl = false,
                enableSingleTapForMusicControl = false,
                enableDoubleTapForVoiceAssistant = false,
                enableLongPressForVoiceAssistant = false,
            )
            g.setGestureConfiguration(config)
            claimed = true
            Log.i(TAG, "claimed gestures (volatile)")
        } ?: Log.w(TAG, "no gesture component - glasses may not support it")
    }

    /** Idle "launcher" mode: only double-tap is taken, so it can start the agent. */
    @Volatile var launcherArmed = false
        private set

    /**
     * Between tasks, take ONLY the double-tap (normally: the phone's voice assistant) so
     * it can start our agent. Volume on slide and music on single tap keep working - a
     * null field in GestureConfiguration means "leave as is". Volatile like the claim.
     * A no-op while the agent owns the controls.
     */
    suspend fun armLauncher() = lock.withLock {
        if (claimed || launcherArmed) return@withLock
        glasses.gesture?.let { g ->
            runCatching { g.removeGestureListener(this) }
            g.addGestureListener(this)
            g.setGestureConfiguration(
                GestureConfiguration(
                    storageType = GestureStorageType.VOLATILE,
                    enableDoubleTapForVoiceAssistant = false,
                )
            )
            launcherArmed = true
            Log.i(TAG, "launcher armed (double-tap starts the agent)")
        }
    }

    /** Give the double-tap back to the phone's voice assistant. */
    suspend fun disarmLauncher() = lock.withLock {
        if (!launcherArmed || claimed) { launcherArmed = false; return@withLock }
        glasses.gesture?.let { g ->
            runCatching { g.clearVolatileGestureConfiguration() }
            g.removeGestureListener(this)
        }
        launcherArmed = false
        Log.i(TAG, "launcher disarmed")
    }

    /**
     * Re-send the claim after a link loss, keeping the binding stack. A no-op if we never
     * claimed. The config is volatile, so if the glasses rebooted it is gone - resend it.
     */
    suspend fun reclaim() {
        val was = lock.withLock { claimed.also { claimed = false } }
        if (was) claim()
    }

    /**
     * Give the gestures back and drop every binding.
     *
     * clearVolatileGestureConfiguration() restores whatever the user had configured
     * persistently, which is cleaner than us guessing and writing `true` everywhere.
     */
    suspend fun release() = lock.withLock {
        stack.clear()
        if (!claimed) return@withLock
        glasses.gesture?.let { g ->
            runCatching { g.clearVolatileGestureConfiguration() }
                .onFailure { Log.e(TAG, "clearVolatile failed: ${it.message}", it) }
            g.removeGestureListener(this)
        }
        claimed = false
        launcherArmed = false   // clearing the volatile config dropped it too
        Log.i(TAG, "released gestures")
    }

    // ---------------------------------------------------------------- binding stack

    /** Replace the top of the stack. This is the common case: "this step needs these controls". */
    fun bind(label: String, map: Map<String, String>) {
        val clean = validate(map)
        if (stack.isEmpty()) stack.addLast(Context(label, clean))
        else stack[stack.size - 1] = Context(label, clean)
        Log.i(TAG, "bind [$label] ${clean.entries.joinToString { "${it.key}=${it.value}" }}")
    }

    /** Push a temporary context (a confirmation, a numeric pick) over the current one. */
    fun push(label: String, map: Map<String, String>) {
        stack.addLast(Context(label, validate(map)))
        Log.i(TAG, "push [$label] depth=${stack.size}")
    }

    /** Pop back to the previous context. */
    fun pop(): Context? {
        val popped = stack.removeLastOrNull()
        Log.i(TAG, "pop [${popped?.label}] depth=${stack.size}")
        return popped
    }

    fun clearBindings() {
        stack.clear()
        Log.i(TAG, "bindings cleared")
    }

    /**
     * Drop anything that isn't a real gesture rather than failing the whole call - an LLM
     * will occasionally invent a gesture name, and losing one binding beats losing the turn.
     */
    private fun validate(map: Map<String, String>): Map<String, String> {
        val (ok, bad) = map.entries.partition { it.key.uppercase() in GESTURES }
        if (bad.isNotEmpty()) Log.w(TAG, "ignoring unknown gestures: ${bad.map { it.key }}")
        return ok.associate { it.key.uppercase() to it.value }
    }

    // ---------------------------------------------------------------- events in

    override fun onGestureDetected(gestureType: GestureType) {
        val name = gestureType.name
        // WAKE_UP_WORD is not a touch gesture and is never bindable - pass it straight through.
        if (name == "WAKE_UP_WORD") {
            onUnboundGesture(name)
            return
        }
        val ctx = stack.lastOrNull()
        val action = ctx?.map?.get(name)
        if (action != null) {
            onAction(action, name, ctx.label)
        } else {
            // Unbound: still report it. The agent may want to react ("you tapped, but
            // there's nothing to confirm right now").
            onUnboundGesture(name)
        }
    }
}
