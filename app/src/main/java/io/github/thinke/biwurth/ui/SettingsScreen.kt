package io.github.thinke.biwurth.ui

import io.github.thinke.biwurth.FrameStats

import android.app.Activity
import android.net.ConnectivityManager
import androidx.activity.compose.BackHandler
import io.github.thinke.biwurth.Provisioning
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thinke.biwurth.BuildConfig
import io.github.thinke.biwurth.Kiosk
import io.github.thinke.biwurth.SleepMode
import io.github.thinke.biwurth.WebServer
import io.github.thinke.biwurth.panel
import java.net.Inet4Address

private val IDLE_CHOICES = listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min", 600 to "10 min", 0 to "Never")

@Composable
fun SettingsScreen(activity: Activity, onClose: () -> Unit) {
    val app = activity.panel
    val prefs = app.prefs
    var idle by remember { mutableIntStateOf(prefs.idleSeconds) }
    var mode by remember { mutableStateOf(prefs.sleepMode) }
    var kiosk by remember { mutableStateOf(prefs.kiosk) }
    val isAdmin = Kiosk.isAdmin(activity)
    val isOwner = Kiosk.isDeviceOwner(activity)

    BackHandler(onBack = onClose)

    // The Surface sets the text colour; plain Text on the bare activity would be black on black.
    Surface(Modifier.fillMaxSize(), color = Color(0xF0101010), contentColor = Color.White) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "biwurth ${BuildConfig.VERSION_NAME}${if (BuildConfig.DEBUG) " (debug)" else ""}",
                        color = Color(0xFF909090),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                Section("Sleep after") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IDLE_CHOICES.forEach { (seconds, label) ->
                            FilterChip(selected = idle == seconds, onClick = {
                                idle = seconds
                                prefs.idleSeconds = seconds
                                app.screen.restartIdleTimer()
                            }, label = { Text(label) })
                        }
                    }
                }

                Section("Sleep mode") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == SleepMode.BACKLIGHT_OFF, onClick = {
                            mode = SleepMode.BACKLIGHT_OFF
                            prefs.sleepMode = mode
                        }, label = { Text("Backlight off (touch wakes)") })
                        FilterChip(selected = mode == SleepMode.SCREEN_OFF, enabled = isAdmin, onClick = {
                            mode = SleepMode.SCREEN_OFF
                            prefs.sleepMode = mode
                        }, label = { Text("Screen off (Home Assistant wakes)") })
                    }
                    if (!isAdmin) {
                    Hint("Screen off needs biwurth to be a device admin.")
                    OutlinedButton(onClick = { Kiosk.requestAdmin(activity) }) { Text("Allow screen off") }
                } else if (!isOwner) {
                    OutlinedButton(onClick = {
                        Kiosk.removeAdmin(activity)
                        mode = SleepMode.BACKLIGHT_OFF
                        prefs.sleepMode = mode
                    }) { Text("Remove device admin") }
                    Hint("Needed before biwurth can be uninstalled. Screen off sleep stops working.")
                }
                }

                Section("Presence") { PresenceSettings() }

                Section("Performance") {
                    val showFps by FrameStats.showCounter.collectAsState()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = showFps, onCheckedChange = {
                            prefs.showFps = it
                            FrameStats.showCounter.value = it
                        })
                        Text("FPS counter")
                    }
                    Hint("Shows the frames drawn each second and how long they take, in the top-right corner.")
                }

                Section("Kiosk mode") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = kiosk, enabled = isOwner, onCheckedChange = {
                            kiosk = it
                            prefs.kiosk = it
                            if (it) Kiosk.enter(activity) else Kiosk.leave(activity)
                        })
                        Text(if (kiosk && isOwner) "Locked to this app" else "Off")
                    }
                    if (!isOwner) Hint("Kiosk mode needs device owner: see README.")
                if (isOwner) {
                    OutlinedButton(onClick = {
                        Kiosk.releaseDeviceOwner(activity)
                        kiosk = false
                    }) { Text("Release device owner") }
                    Hint("Ends kiosk mode and lets biwurth be uninstalled. Setting it again needs adb.")
                }
                }

                Section("Tablet") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { Kiosk.openSystemSettings(activity, wifiOnly = true) }) { Text("Wi-Fi…") }
                        OutlinedButton(onClick = { Kiosk.openSystemSettings(activity, wifiOnly = false) }) { Text("Android settings…") }
                    }
                    if (isOwner) Hint("Home or Back returns to biwurth, which locks the tablet again.")
                }

                UpdatesSection(app.updater, prefs)

                Section("Website") {
                    val address = "http://${ipAddress(activity) ?: "<tablet IP>"}:${WebServer.PORT}"
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Scanning opens the website already signed in, so nobody types the key.
                        val qr = remember(address, prefs.token) {
                            Provisioning.qrBitmap("$address/#key=${prefs.token}", 360).asImageBitmap()
                        }
                        Image(qr, contentDescription = "Website QR code", modifier = Modifier.size(160.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Scan with your phone to open the website, already signed in.")
                            Mono(address)
                            Mono("Key: ${prefs.token}")
                            Hint("Anyone with this code or key can change the panel: show it only to people you trust.")
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onClose) { Text("Close") }
                    OutlinedButton(onClick = {
                        onClose()
                        app.screen.sleep()
                    }) { Text("Sleep now") }
                }
            }
            // Always in reach, however far down the page is scrolled.
            FilledTonalButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(20.dp)) { Text("✕  Close") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = Color(0xFF909090), style = MaterialTheme.typography.bodySmall)

@Composable
private fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)

private fun ipAddress(activity: Activity): String? {
    val cm = activity.getSystemService(ConnectivityManager::class.java)
    return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
        ?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
}

/**
 * Wake on motion: on or off, how sensitive, and the two calibration steps with a countdown each,
 * so whoever starts one can step out of view (or into it) first. Shows the plan made.
 */
@Composable
private fun PresenceSettings() {
    val app = LocalContext.current.panel
    val presence = app.presence
    val plan by presence.plan.collectAsState()
    val motion by presence.motion.collectAsState()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun run(step: String, prompt: String) {
        busy = true
        scope.launch {
            for (s in 5 downTo 1) {
                status = "$prompt in $s…"
                delay(1_000)
            }
            status = if (step == "still") "Measuring: stay out of view (15 s)…" else "Measuring: keep moving in front of the panel (15 s)…"
            status = runCatching { presence.calibrate(step, 15) }.fold(
                { "Done. ${if (step == "still") "Now the second step." else "Calibrated."}" },
                { "Couldn't calibrate: ${it.message}" },
            )
            busy = false
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = plan.enabled, onCheckedChange = { on -> presence.update { it.copy(enabled = on) } })
        Text(if (plan.enabled) (if (motion) "Wake on motion · motion now" else "Wake on motion") else "Off")
    }
    if (app.prefs.sleepMode == SleepMode.SCREEN_OFF) {
        Hint("Works with the Backlight off sleep mode: with Screen off, Android stops the camera.")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Sensitivity")
        listOf(-2 to "Low", 0 to "Normal", 2 to "High").forEach { (value, label) ->
            FilterChip(selected = plan.sensitivity == value, onClick = { presence.update { it.copy(sensitivity = value) } }, label = { Text(label) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = { run("still", "Step out of view") }) { Text("1. Calibrate: nobody there") }
        OutlinedButton(enabled = !busy, onClick = { run("moving", "Walk in front of the panel") }) { Text("2. Calibrate: moving") }
    }
    if (status.isNotEmpty()) Text(status)
    plan.notes.forEach { Hint(it) }
    if (plan.calibrated == null) Hint("Not calibrated yet: it works with general settings until then.")
}
