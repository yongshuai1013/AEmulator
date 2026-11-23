/* AEmulator Plus addition, 2026-11-21. GPL-3.0; see LICENSE. */
package app.aemu.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
 * Root (Magisk) for the native engine: pick the Magisk APK once, then switch it on per VM.
 * Shown only for an arm64 firmware with a 64-bit side; see [MagiskSupport.offered].
 * (The file picker is this tree's OpenDocument launcher, where upstream has a rememberSingleFilePicker helper.)
 */
@Composable
internal fun MagiskSection(img: GuestImage, enabled: Boolean, busy: Boolean, setBusy: (Boolean) -> Unit, setEnabled: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = VmPaths(ctx, img.id).root
    if (!remember(img.id) { MagiskSupport.offered(img, root) }) return
    var info by remember { mutableStateOf(MagiskCore.info(root)) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri != null) {
            setBusy(true)
            message = ctx.getString(R.string.vm_magisk_working)
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { MagiskSupport.install(ctx, img.id, uri) } }
                info = MagiskCore.info(root)
                setBusy(false)
                message = result.fold({ ctx.getString(R.string.vm_magisk_done, it.version) },
                    { ctx.getString(R.string.vm_import_failed, it.message ?: it.toString()) })
            }
        }
    }
    info?.let { Toggle(stringResource(R.string.vm_magisk_title), stringResource(R.string.vm_magisk_sub), enabled, setEnabled) }
    info?.let { Text(stringResource(R.string.vm_magisk_installed, it.version, it.code), style = MaterialTheme.typography.bodySmall) }
    TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = {
        picking = true; pick.launch(arrayOf("*/*"))
    }) { Text(stringResource(if (info != null) R.string.vm_magisk_replace else R.string.vm_magisk_install)) }
    if (info != null) TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = {
        setBusy(true)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { MagiskSupport.remove(ctx, img.id) } }
            info = MagiskCore.info(root)
            setBusy(false)
            if (result.isSuccess) { setEnabled(false); message = ctx.getString(R.string.vm_magisk_removed) }
            else message = ctx.getString(R.string.vm_import_failed, result.exceptionOrNull()?.message ?: "")
        }
    }) { Text(stringResource(R.string.vm_magisk_remove)) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
