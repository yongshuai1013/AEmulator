/* AEmulator addition, 2026-11-26. GPL-3.0; see LICENSE. */
package app.aemu.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.aemu.R
import app.aemu.core.*

/**
 * Zygisk (spike): our own bridge inside zygote driving modules through the
 * Zygisk API v4 table (see ZygiskSpikeSupport) — the landing spike, not the
 * module-management surface. Shown for every guest; where
 * [ZygiskSpikeSupport.supported] holds (the native engine, Android 6.0,
 * arm64) it is the working switch, anywhere else the row stays visible
 * with an honest not-supported label. The strings next to the switch state
 * the spike's edges: no companion, PLT/JNI hook APIs unsupported, no
 * 32-bit, no system_server path.
 */
@Composable
internal fun ZygiskSpikeSection(img: GuestImage, enabled: Boolean, setEnabled: (Boolean) -> Unit) {
    if (remember(img.id) { ZygiskSpikeSupport.supported(img) }) {
        Toggle(stringResource(R.string.vm_zygiskspike_title), stringResource(R.string.vm_zygiskspike_sub), enabled, setEnabled)
    } else {
        ListItem(
            headlineContent = { Text(stringResource(R.string.vm_zygiskspike_title)) },
            supportingContent = { Text(stringResource(R.string.vm_zygiskspike_sub_unsupported)) },
            trailingContent = { Text(stringResource(R.string.vm_zygiskspike_unsupported)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
