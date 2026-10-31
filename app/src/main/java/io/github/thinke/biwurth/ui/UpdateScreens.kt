package io.github.thinke.biwurth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.thinke.biwurth.Prefs
import io.github.thinke.biwurth.update.Release
import io.github.thinke.biwurth.update.UpdateState
import io.github.thinke.biwurth.update.Updater
import kotlinx.coroutines.launch

private val MUTED = Color.White.copy(alpha = 0.65f)

/**
 * Shown over the panel when an update is found, as in SnapTV: what's new, then download,
 * install permission and failure in the same box. [onClose] hides it until the next release.
 */
@Composable
fun UpdatePrompt(updater: Updater, onClose: () -> Unit) {
    val state by updater.state.collectAsState()
    Box(Modifier.fillMaxSize().background(Color(0xA0000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).heightIn(max = 520.dp).padding(24.dp)
                .background(Color(0xFF1C1C1E), RoundedCornerShape(20.dp)).padding(24.dp),
        ) {
            UpdatePanel(state, updater, onClose)
        }
    }
}

@Composable
private fun ColumnScope.UpdatePanel(state: UpdateState, updater: Updater, onClose: () -> Unit) {
    when (state) {
        is UpdateState.Available -> {
            val r = state.release
            Text("biwurth ${r.version} is available", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("You have ${updater.installed}", color = MUTED, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            ChangeList(r, Modifier.weight(1f, fill = false))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { updater.install(r) }) { Text("Update") }
                OutlinedButton(onClick = onClose) { Text("Later") }
                OutlinedButton(onClick = {
                    updater.skip(r)
                    onClose()
                }) { Text("Skip this version") }
            }
        }
        is UpdateState.Downloading -> {
            Text("Downloading biwurth ${state.release.version}…", style = MaterialTheme.typography.titleLarge, color = Color.White)
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            Text("${(state.fraction * 100).toInt()} %", color = MUTED, modifier = Modifier.padding(top = 8.dp))
        }
        is UpdateState.Installing -> {
            Text("Installing biwurth ${state.release.version}", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                "Confirm on Android's install screen if it asks. biwurth restarts with the new version.",
                color = MUTED, modifier = Modifier.padding(top = 12.dp),
            )
        }
        is UpdateState.NeedsPermission -> {
            Text("One more step", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                "Android needs to allow biwurth to install apps. Turn on the switch for biwurth, come back, and tap Update again.",
                color = MUTED, modifier = Modifier.padding(top = 12.dp),
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = updater::openInstallPermissionSettings) { Text("Open settings") }
                OutlinedButton(onClick = { updater.install(state.release) }) { Text("Update") }
                OutlinedButton(onClick = onClose) { Text("Later") }
            }
        }
        is UpdateState.Failed -> {
            Text("Update failed", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(state.message, color = MUTED, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.release?.let { r -> Button(onClick = { updater.install(r) }) { Text("Try again") } }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
        }
        else -> {
            Text(statusText(state, updater), color = Color.White)
            OutlinedButton(onClick = onClose, modifier = Modifier.padding(top = 16.dp)) { Text("Close") }
        }
    }
}

@Composable
private fun ChangeList(r: Release, modifier: Modifier) {
    if (r.changes.isEmpty()) return
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        r.changes.forEach { change ->
            Text("What's new in ${change.version}", color = MUTED, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            change.lines.forEach { Text("•  $it", color = Color.White, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** The Updates part of the tablet's Settings screen. */
@Composable
fun UpdatesSection(updater: Updater, prefs: Prefs) {
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    var check by remember { mutableStateOf(prefs.updateCheck) }
    var autoInstall by remember { mutableStateOf(prefs.updateAutoInstall) }
    var prerelease by remember { mutableStateOf(prefs.updatePrerelease) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Text("Installed: biwurth ${updater.installed}  ·  ${statusText(state, updater)}", color = MUTED)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { scope.launch { updater.check() } }, enabled = updater.canUpdate) { Text("Check now") }
            when (val s = state) {
                is UpdateState.Available -> Button(onClick = { updater.install(s.release) }) { Text("Install ${s.release.version}") }
                is UpdateState.NeedsPermission -> Button(onClick = updater::openInstallPermissionSettings) { Text("Allow installing") }
                is UpdateState.Failed -> s.release?.let { r -> Button(onClick = { updater.install(r) }) { Text("Try again") } }
                else -> {}
            }
        }
        Toggle("Check for updates daily", check) {
            check = it
            prefs.updateCheck = it
        }
        Toggle("Install updates automatically", autoInstall) {
            autoInstall = it
            prefs.updateAutoInstall = it
        }
        Toggle("Include pre-releases", prerelease) {
            prerelease = it
            prefs.updatePrerelease = it
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

private fun statusText(s: UpdateState, updater: Updater): String = when (s) {
    UpdateState.Idle -> if (updater.canUpdate) "not checked yet" else "debug build: no updates"
    UpdateState.Checking -> "checking…"
    is UpdateState.UpToDate -> "up to date"
    is UpdateState.Available -> "${s.release.version} available"
    is UpdateState.Downloading -> "downloading ${(s.fraction * 100).toInt()} %"
    is UpdateState.Installing -> "installing ${s.release.version}"
    is UpdateState.NeedsPermission -> "needs permission to install"
    is UpdateState.Failed -> s.message
}
