package app.aemu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.AgentSupport
import app.aemu.core.GuestImage
import app.aemu.core.MagiskCore
import app.aemu.core.MagiskSupport
import app.aemu.core.VmPaths
import app.aemu.core.VmSettings

/**
 * The guest agent and its hide list. The agent is a service of ours inside the guest (AgentSupport): it
 * resolves the listed packages to uids for the mount layer's per-app view and receives imported APKs.
 * The list is only offered when Magisk is installed for this image — without it there is nothing to
 * hide — and the Magisk app itself can never be listed: it always sees the full guest.
 */
@Composable
internal fun AgentSection(img: GuestImage, s: VmSettings, set: (VmSettings) -> Unit) {
    val ctx = LocalContext.current
    if (!AgentSupport.supported(img.engine, img.api, false)) return
    val root = VmPaths(ctx, img.id).root
    Toggle(stringResource(R.string.vs_agent), stringResource(R.string.vs_agent_sub), s.agent) { set(s.copy(agent = it)) }
    if (!s.agent) return
    val magiskOffered = remember(img.id) { MagiskSupport.offered(img, root) }
    if (!magiskOffered) return
    Text(stringResource(R.string.vs_agent_hidden), style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp))
    Text(stringResource(R.string.vs_agent_hidden_sub), style = MaterialTheme.typography.bodySmall)
    val installed = remember(img.id) { AgentSupport.installedPackages(root) }
    var draft by remember { mutableStateOf("") }
    if (installed == null) {
        Text(stringResource(R.string.vs_agent_hidden_abx), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp))
    } else {
        Column {
            for (pkg in installed) {
                if (pkg == MagiskCore.PACKAGE) continue
                val on = pkg in s.hiddenApps
                Row(Modifier.fillMaxWidth().clickable {
                    set(s.copy(hiddenApps = if (on) s.hiddenApps - pkg else s.hiddenApps + pkg))
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on, onCheckedChange = {
                        set(s.copy(hiddenApps = if (it) s.hiddenApps + pkg else s.hiddenApps - pkg))
                    })
                    Text(pkg, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    // names the picker does not know (not installed yet, or the list was not readable): shown with a
    // remove button; the agent resolves them in the guest and reports the ones it cannot find
    for (pkg in s.hiddenApps) {
        if (installed != null && pkg in installed) continue
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(pkg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { set(s.copy(hiddenApps = s.hiddenApps - pkg)) }) {
                Text(stringResource(R.string.vs_agent_remove))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = draft, onValueChange = { draft = it },
            label = { Text(stringResource(R.string.vs_agent_pkg_hint)) }, singleLine = true,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp))
        TextButton(onClick = {
            val pkg = draft.trim()
            if (pkg.isNotEmpty() && pkg != MagiskCore.PACKAGE && pkg !in s.hiddenApps) {
                set(s.copy(hiddenApps = s.hiddenApps + pkg))
                draft = ""
            }
        }) { Text(stringResource(R.string.vs_agent_add)) }
    }
}
