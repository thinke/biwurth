package io.github.thinke.biwurth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

@Composable
private fun ChangeList(r: Release) {
    if (r.changes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        r.changes.forEach { change ->
            Text("What's new in ${change.version}", color = MUTED, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            change.lines.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * The Updates part of the tablet's Settings screen, the only place on the tablet that shows
 * updates: the panel pages stay free of prompts.
 */
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
        when (val s = state) {
            is UpdateState.Available -> ChangeList(s.release)
            is UpdateState.Downloading -> LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
            is UpdateState.Installing -> Text("Confirm on Android's install screen if it asks; biwurth restarts with the new version.", color = MUTED)
            is UpdateState.NeedsPermission -> Text(
                "Android needs to allow biwurth to install apps: turn on the switch for biwurth, come back and install again.",
                color = MUTED,
            )
            else -> {}
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
