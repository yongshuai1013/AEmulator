/* Offline Magisk-module host, 2026-11-26. GPL-3.0; see LICENSE. */
package app.aemu.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aemu.R
import app.aemu.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modules (offline host) for one VM: install a Magisk module zip while the VM is off, then
 * enable, disable or uninstall it. Shown only where [ModuleHostSupport.offered] says the host
 * exists (API 23 on the native engine). The install report lists what was placed and calls out
 * what the host does not support — it never just says "success".
 */
@Composable
internal fun ModulesSection(img: GuestImage) {
    if (!remember(img.id) { ModuleHostSupport.offered(img) }) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running = vmIsRunning(ctx)
    var modules by remember(img.id) { mutableStateOf(ModuleHostSupport.list(ctx, img.id)) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    fun act(block: () -> Unit, done: String? = null) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            modules = ModuleHostSupport.list(ctx, img.id)
            busy = false
            message = result.fold({ done }, { ctx.getString(R.string.vm_modules_failed, it.message ?: it.toString()) })
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri != null) {
            busy = true
            message = ctx.getString(R.string.vm_modules_working)
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { ModuleHostSupport.install(ctx, img.id, uri) } }
                modules = ModuleHostSupport.list(ctx, img.id)
                busy = false
                message = result.fold({ report ->
                    buildString {
                        append("${report.info.name} ${report.info.version} (${report.info.id}) installed:")
                        for (p in report.placed) append("\n• $p")
                        if (report.warnings.isNotEmpty()) {
                            append("\nWarnings:")
                            for (w in report.warnings) append("\n• $w")
                        }
                    }
                }, { ctx.getString(R.string.vm_modules_failed, it.message ?: it.toString()) })
            }
        }
    }

    Text(stringResource(R.string.vm_modules_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.vm_modules_sub), style = MaterialTheme.typography.bodySmall)
    if (running) Text(stringResource(R.string.vm_modules_stop_first), style = MaterialTheme.typography.bodySmall)
    if (modules.isEmpty()) Text(stringResource(R.string.vm_modules_none), style = MaterialTheme.typography.bodySmall)
    for (m in modules) {
        Text("${m.name} ${m.version}".trim(), style = MaterialTheme.typography.bodyMedium)
        Text("${m.id} · ${stringResource(if (m.enabled) R.string.vm_modules_enabled_state else R.string.vm_modules_disabled_state)}",
            style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(enabled = !busy && !running, onClick = {
                act({ ModuleHostSupport.setEnabled(ctx, img.id, m.id, !m.enabled) })
            }) { Text(stringResource(if (m.enabled) R.string.vm_modules_disable else R.string.vm_modules_enable)) }
            TextButton(enabled = !busy && !running, onClick = {
                act({ ModuleHostSupport.uninstall(ctx, img.id, m.id) })
            }) { Text(stringResource(R.string.vm_modules_uninstall)) }
        }
    }
    TextButton(enabled = !busy && !picking && !running, modifier = Modifier.fillMaxWidth(), onClick = {
        picking = true; pick.launch(arrayOf("*/*"))
    }) { Text(stringResource(R.string.vm_modules_install)) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
