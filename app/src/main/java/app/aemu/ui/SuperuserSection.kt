/* AEmulator addition, 2026-11-25. GPL-3.0; see LICENSE. */
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
 * Root (Superuser): the classic su stack, built from source with this app (see SuperuserSupport)
 * — no file to pick, just the per-VM switch. Shown for every guest. Where
 * [SuperuserSupport.supported] holds it is the working switch; anywhere else — the 32-bit guests
 * (the KK/GB engines and 32-bit firmwares), whose supervisors have no exec elevation point and
 * which could not run the arm64 su anyway — the row stays visible with an honest
 * not-supported label in place of a switch that would do nothing.
 */
@Composable
internal fun SuperuserSection(img: GuestImage, enabled: Boolean, setEnabled: (Boolean) -> Unit) {
    if (remember(img.id) { SuperuserSupport.supported(img) }) {
        Toggle(stringResource(R.string.vm_superuser_title), stringResource(R.string.vm_superuser_sub), enabled, setEnabled)
    } else {
        ListItem(
            headlineContent = { Text(stringResource(R.string.vm_superuser_title)) },
            supportingContent = { Text(stringResource(R.string.vm_superuser_sub_unsupported)) },
            trailingContent = { Text(stringResource(R.string.vm_superuser_unsupported)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
