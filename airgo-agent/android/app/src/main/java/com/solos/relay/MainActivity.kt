package com.solos.relay

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.solosglasses.solosairgosdk.SolosSdkLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.NetworkInterface

/**
 * Host for both modes.
 *
 *   Relay mode  - the phone is a dumb bridge and a laptop runs the agent. Fast to
 *                 iterate on: edit a prompt, rerun, no rebuild.
 *   Agent mode  - everything runs here. This is the one you demo with, because you
 *                 cannot walk a venue carrying a laptop.
 *
 * Both share the same GlassesRelay and GestureBinder, so behaviour is identical.
 */
class MainActivity : ComponentActivity() {

    // The session lives in Session, not here: this Activity is rebuilt on rotation and
    // must never take the glasses link down with it.
    private val relay get() = Session.relay
    private val speech get() = Session.speech
    private val server get() = Session.server
    private val agent get() = Session.agent

    private val port = Session.PORT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SolosSdkLibrary.configure(this)
        Session.init(this)
        note("--- screen created, battery unrestricted=${batteryUnrestricted()} ---")
        // Check the licence up front: if the key is bad, nothing licensed will work and
        // the SDK itself only ever says "Server error".
        lifecycleScope.launch(Dispatchers.IO) {
            val key = packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
                .metaData?.getString("com.solosglasses.solosairgosdk.API_KEY").orEmpty()
            note(SolosKey.check(key, packageName))
        }

        setContent {
            // The Activity window uses a dark platform theme, and Compose defaults to a
            // LIGHT colour scheme - which is what made the text black on black.
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Deliberately NOT shutting the session down: a rotation or a trip to Settings
        // destroys this Activity, and the glasses link, agent and log must survive it.
    }


    private fun note(line: String) = Session.note(line)

    private fun batteryUnrestricted(): Boolean =
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        var lines by remember { mutableStateOf(listOf<String>()) }
        var connected by remember { mutableStateOf(false) }
        var reconnecting by remember { mutableStateOf(false) }
        var agentRunning by remember { mutableStateOf(false) }
        var granted by remember { mutableStateOf(false) }
        var goal by remember { mutableStateOf("") }
        var devices by remember { mutableStateOf(listOf<String>()) }
        var scanning by remember { mutableStateOf(false) }
        var camera by remember { mutableStateOf(false) }
        var keyStatus by remember { mutableStateOf<String?>(null) }
        var batteryUnrestricted by remember { mutableStateOf(true) }
        var keyOk by remember { mutableStateOf(false) }
        var heading by remember { mutableStateOf<String?>(null) }
        var liveOn by remember { mutableStateOf(false) }
        var liveStatus by remember { mutableStateOf("off") }
        var lang by remember { mutableStateOf(Lang.current) }
        var launcher by remember { mutableStateOf(Session.launcherEnabled) }
        var wifiState by remember { mutableStateOf("off") }
        var photoSummary by remember { mutableStateOf("") }
        var photoLevel by remember { mutableStateOf(2) }
        var wifiDetails by remember { mutableStateOf("") }
        var authFailed by remember { mutableStateOf(false) }
        var confirmReset by remember { mutableStateOf(false) }
        var wifiOn by remember { mutableStateOf(false) }
        var phoneSsid by remember { mutableStateOf<String?>(null) }
        var wifiBusy by remember { mutableStateOf(false) }
        val prefs = remember { getSharedPreferences("airgo", MODE_PRIVATE) }
        var wifiPassword by remember { mutableStateOf(prefs.getString("wifi_password", "").orEmpty()) }
        var wifiSsid by remember { mutableStateOf(prefs.getString("wifi_ssid", "").orEmpty()) }
        val ip = remember { localIp() }

        val permissions = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result -> granted = result.values.all { it } }

        LaunchedEffect(Unit) { permissions.launch(requiredPermissions()) }

        LaunchedEffect(Unit) {
            while (true) {
                val relayLog = server?.let { srv -> synchronized(srv.log) { srv.log.toList() } }.orEmpty()
                val own = Session.log
                lines = (relayLog + own).takeLast(300)
                connected = relay.isConnected
                reconnecting = relay.hasGlasses && !relay.isConnected
                agentRunning = agent?.running == true
                devices = relay.discovered
                scanning = relay.scanning
                camera = relay.cameraAvailable
                keyStatus = SolosKey.status
                batteryUnrestricted = batteryUnrestricted()
                keyOk = SolosKey.ok
                heading = relay.head?.fresh?.describe()
                liveOn = Session.live.running
                liveStatus = if (liveOn) "live view: ${"%.1f".format(Session.live.fps)} fps" else "live view: ${Session.live.status}"
                lang = Lang.current   // the agent can switch it with set_language
                wifiState = relay.wifiState
                relay.photoLinkSummary().let { (text, level) -> photoSummary = text; photoLevel = level }
                wifiDetails = relay.wifiDetails()
                authFailed = relay.wifiAuthFailed
                wifiOn = relay.wifiConnected
                phoneSsid = relay.phoneSsid
                if (wifiSsid.isBlank()) phoneSsid?.let { wifiSsid = it }
                delay(400)
            }
        }

        Column(Modifier.fillMaxSize().padding(16.dp).systemBarsPadding()) {
            Text("AirGo Agent", style = MaterialTheme.typography.headlineSmall)
            // Two tabs: the controls, and the log on its own full screen. The log used to
            // share the screen with every button and ended up a few unscrollable lines.
            var tab by remember { mutableStateOf(0) }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Controls") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Log (${lines.size})") })
            }
            Spacer(Modifier.height(8.dp))

            if (tab == 0) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                when {
                    connected -> "glasses: CONNECTED"
                    reconnecting -> "glasses: link lost - RECONNECTING..."
                    else -> "glasses: not connected"
                },
                color = when {
                    connected -> MaterialTheme.colorScheme.primary
                    reconnecting -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
            )
            // The camera is a separate Bluetooth device - it can be off while the
            // glasses are connected, which is what makes captures fail.
            Text(
                if (camera) "camera: CONNECTED" else "camera: not connected",
                color = if (camera) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
            )
            Text("relay: ws://$ip:$port", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (!batteryUnrestricted) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("battery: optimized - Android may cut the glasses on battery",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        runCatching {
                            startActivity(android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:$packageName")))
                        }
                    }) { Text("Allow") }
                }
            }
            Text(
                keyStatus ?: "Solos key: checking...",
                fontSize = 12.sp,
                color = if (keyOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (connected) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(heading ?: "heading: off", fontSize = 12.sp,
                         fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    // Lets you check the compass (and do the figure-eight) before a demo.
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val h = relay.head ?: return@launch
                            if (h.running) h.stop() else relay.ensureHeading()
                                ?: note("heading: ${relay.lastHeadingError}")
                        }
                    }) { Text(if (heading != null) "Compass off" else "Compass on") }
                    TextButton(enabled = camera, onClick = {
                        scope.launch(Dispatchers.IO) {
                            if (Session.live.running) { Session.keepLive = false; Session.live.stop() }
                            else { Session.keepLive = true; Session.live.start() }
                        }
                    }) { Text(if (liveOn) "Live off" else "Live view") }
                    // Measures the real frame rate of the photo stream on these glasses.
                    TextButton(enabled = camera && !agentRunning, onClick = {
                        scope.launch(Dispatchers.IO) {
                            note("look around: turn your head slowly for 6 s...")
                            relay.speak(if (Lang.current == Lang.FR) "Tournez lentement la tête."
                                        else "Turn your head slowly.", true)
                            relay.awaitSpeechDone()
                            // Same choice as the agent: video on Wi-Fi, photos otherwise.
                            if (relay.wifiConnected) relay.recordClip(6) else relay.lookAround(6, 8)
                        }
                    }) { Text("Look around") }
                }

                // Photos over Wi-Fi. The summary is based on MEASURED speed, because the
                // SDK only takes a preferred transfer method and never says what it used.
                val (summaryColor, summaryWeight) = when (photoLevel) {
                    0 -> MaterialTheme.colorScheme.primary to androidx.compose.ui.text.font.FontWeight.Bold
                    1 -> MaterialTheme.colorScheme.tertiary to androidx.compose.ui.text.font.FontWeight.Bold
                    else -> MaterialTheme.colorScheme.error to androidx.compose.ui.text.font.FontWeight.Bold
                }
                Spacer(Modifier.height(6.dp))
                Text(photoSummary, fontSize = 13.sp, color = summaryColor, fontWeight = summaryWeight)
                Text(wifiDetails, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(liveStatus, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = if (liveOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)

                if (!wifiOn) {
                    Button(
                        enabled = !wifiBusy,
                        onClick = {
                            wifiBusy = true
                            scope.launch(Dispatchers.IO) {
                                note("wifi: connecting phone to the glasses' hotspot ...")
                                note(if (relay.wifiHotspot()) "wifi: hotspot up - press Test Wi-Fi"
                                     else "wifi: hotspot failed - ${relay.wifiState}")
                                wifiBusy = false
                            }
                        },
                    ) { Text(if (wifiBusy) "Connecting..." else "Wi-Fi: glasses hotspot") }
                    Text("Phone joins the glasses' Wi-Fi. Internet for Gemini then goes over mobile data.",
                        fontSize = 11.sp)
                    // Glasses join a network: the phone's own hotspot (phone keeps mobile
                    // data), or any Wi-Fi. In hotspot mode the phone is on no Wi-Fi, so the
                    // name can't be detected - type it.
                    Text("Or: glasses join a network (e.g. this phone's hotspot)", fontSize = 11.sp)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = wifiSsid,
                            onValueChange = { wifiSsid = it },
                            label = { Text("Network name", fontSize = 11.sp) },
                            singleLine = true,
                            enabled = !wifiBusy,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = wifiPassword,
                            onValueChange = { wifiPassword = it },
                            label = { Text("Password", fontSize = 11.sp) },
                            singleLine = true,
                            enabled = !wifiBusy,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            enabled = !wifiBusy && wifiSsid.isNotBlank(),
                            onClick = {
                                val ssid = wifiSsid.trim()
                                prefs.edit().putString("wifi_ssid", ssid)
                                    .putString("wifi_password", wifiPassword).apply()
                                wifiBusy = true
                                scope.launch(Dispatchers.IO) {
                                    note("wifi: glasses joining $ssid ...")
                                    note(if (relay.wifiJoin(ssid, wifiPassword)) "wifi: joined - press Test Wi-Fi"
                                         else "wifi: join failed - ${relay.wifiState}")
                                    wifiBusy = false
                                }
                            },
                        ) { Text("Join") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !wifiBusy && camera,
                            onClick = {
                                wifiBusy = true
                                scope.launch(Dispatchers.IO) {
                                    note("testing Wi-Fi photo transfer ...")
                                    note(relay.testWifi())
                                    wifiBusy = false
                                }
                            },
                        ) { Text(if (wifiBusy) "Testing..." else "Test Wi-Fi") }
                        OutlinedButton(enabled = !wifiBusy, onClick = {
                            scope.launch(Dispatchers.IO) { relay.wifiOff(); note("wifi: off") }
                        }) { Text("Wi-Fi off") }
                    }
                }
                if (authFailed) {
                    TextButton(enabled = !wifiBusy, onClick = { confirmReset = true }) {
                        Text("Reset glasses file password...", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (confirmReset) {
                    AlertDialog(
                        onDismissRequest = { confirmReset = false },
                        title = { Text("Reset file-server password?") },
                        text = { Text("The glasses rejected the Wi-Fi file password. Resetting it to " +
                            "the factory default fixes that, but the Solos SDK warns it DELETES ALL " +
                            "PHOTOS AND VIDEOS stored on the glasses and turns off low-power mode. " +
                            "The official Solos app may need to re-pair its Wi-Fi afterwards.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmReset = false
                                wifiBusy = true
                                scope.launch(Dispatchers.IO) {
                                    note(relay.resetFilePassword())
                                    wifiBusy = false
                                }
                            }) { Text("Delete files and reset", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
                    )
                }
            }

            if (connected && !camera) {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        note("connecting camera ...")
                        note(if (relay.ensureCamera()) "camera connected"
                             else "camera NOT found - is it powered on?")
                    }
                }) { Text("Connect camera") }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = granted && !agentRunning && !connected && !reconnecting,
                    onClick = { scope.launch(Dispatchers.IO) { relay.startScan() } },
                ) { Text(if (scanning) "Scanning..." else "Scan") }
                OutlinedButton(
                    enabled = scanning,
                    onClick = { scope.launch(Dispatchers.IO) { relay.stopScan() } },
                ) { Text("Stop scan") }
                OutlinedButton(
                    enabled = (connected || reconnecting) && !agentRunning,
                    onClick = { scope.launch(Dispatchers.IO) {
                        relay.disconnect()
                        KeepAliveService.stop(this@MainActivity)
                    } },
                ) { Text("Disconnect") }
            }

            // Tap a device to connect. Nothing happens implicitly.
            if (!connected && !reconnecting) {
                if (devices.isEmpty()) {
                    Text(
                        if (scanning) "Scanning for glasses..."
                        else "Press Scan. Pair the glasses in system Bluetooth settings first.",
                        fontSize = 12.sp,
                    )
                } else {
                    devices.forEach { id ->
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    note("connecting to $id ...")
                                    val ok = relay.connect(id)
                                    note(if (ok) "connected to $id" else "failed to connect to $id")
                                    if (ok) KeepAliveService.start(this@MainActivity)
                                }
                            },
                        ) { Text("Connect to  $id") }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            Text("On-device agent", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Language", fontSize = 13.sp)
                Lang.entries.forEach { l ->
                    FilterChip(
                        selected = lang == l,
                        enabled = !agentRunning,
                        onClick = {
                            Lang.current = l
                            lang = l
                            relay.setLanguage(l)
                        },
                        label = { Text(l.displayName) },
                    )
                }
            }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Double-tap or \"Hey Solos\" to start", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = launcher, onCheckedChange = {
                    launcher = it
                    Session.setLauncher(it)
                })
            }

            OutlinedTextField(
                value = goal,
                onValueChange = { goal = it },
                label = { Text("Goal (leave blank and it will ask)") },
                singleLine = true,
                enabled = !agentRunning,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = connected && !agentRunning && BuildConfig.GEMINI_API_KEY.isNotBlank(),
                    onClick = { startAgent(goal) },
                ) { Text("Start agent") }

                OutlinedButton(
                    enabled = agentRunning,
                    onClick = { Session.stopAgent() },
                ) { Text("Stop") }
            }

            if (BuildConfig.GEMINI_API_KEY.isBlank()) {
                Text(
                    "No Gemini key: add GEMINI_API_KEY to gradle.properties and rebuild.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
            Text(
                "model: ${BuildConfig.GEMINI_MODEL} (thinking: ${BuildConfig.GEMINI_THINKING.ifBlank { "default" }})" +
                    if (speech.available) "" else "  (no speech recognition on this device)",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(12.dp))
                }
            } else {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                var follow by remember { mutableStateOf(true) }
                // Stick to the newest line unless the user scrolled up to read.
                LaunchedEffect(lines.size, follow) {
                    if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Follow newest", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(checked = follow, onCheckedChange = { follow = it })
                }
                androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
                    androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(lines.size) { i ->
                            val line = lines[i]
                            Text(
                                line, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = if (line.contains("ERROR") || line.contains("failed", true))
                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startAgent(goal: String) = Session.startAgent(goal)

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        // Photo transfer over Wi-Fi (the SDK demo requests the same).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)   // the keep-alive notification
        }
    }.toTypedArray()

    private fun localIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "unknown"
    }.getOrDefault("unknown")
}
