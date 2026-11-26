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
 * Root (Magisk substitute): our daemon in the official Magisk app's clothes (see
 * SubstituteSupport) — no file to pick, just the per-VM switch. Shown for every guest.
 * Where [SubstituteSupport.supported] holds (the native engine, Android 6.0, arm64) it is
 * the working switch; anywhere else the row stays visible with an honest not-supported
 * label in place of a switch that would do nothing. While this switch is on, the classic
 * Superuser plant stays out: the substitute owns the guest's su names.
 */
@Composable
internal fun SubstituteSection(img: GuestImage, enabled: Boolean, setEnabled: (Boolean) -> Unit) {
    if (remember(img.id) { SubstituteSupport.supported(img) }) {
        Toggle(stringResource(R.string.vm_magisksub_title), stringResource(R.string.vm_magisksub_sub), enabled, setEnabled)
    } else {
        ListItem(
            headlineContent = { Text(stringResource(R.string.vm_magisksub_title)) },
            supportingContent = { Text(stringResource(R.string.vm_magisksub_sub_unsupported)) },
            trailingContent = { Text(stringResource(R.string.vm_magisksub_unsupported)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
