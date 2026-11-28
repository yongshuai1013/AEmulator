/* AEmulator addition, 2026-11-28. GPL-3.0; see LICENSE. */
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
 * Root (self-built Magisk, plan mode): the genuine Magisk daemon, built by this
 * project from the unmodified v30.7 sources, woken by the host at the plan boot's
 * milestones (see MagiskPlanSupport) — no file to pick, just the per-VM switch.
 * Shown for every guest. Where [MagiskPlanSupport.supported] holds (the native
 * engine, Android 8.1, arm64) it is the working switch; anywhere else the row
 * stays visible with an honest not-supported label in place of a switch that
 * would do nothing. Mutually exclusive with the Magisk substitute and with the
 * partition seat — SettingsSheet clears the other two when this one turns on —
 * and while it is on, the classic Superuser plant stays out: the daemon owns
 * the guest's su names.
 */
@Composable
internal fun MagiskPlanSection(img: GuestImage, enabled: Boolean, setEnabled: (Boolean) -> Unit) {
    if (remember(img.id) { MagiskPlanSupport.supported(img) }) {
        Toggle(stringResource(R.string.vm_magiskplan_title), stringResource(R.string.vm_magiskplan_sub), enabled, setEnabled)
    } else {
        ListItem(
            headlineContent = { Text(stringResource(R.string.vm_magiskplan_title)) },
            supportingContent = { Text(stringResource(R.string.vm_magiskplan_sub_unsupported)) },
            trailingContent = { Text(stringResource(R.string.vm_magiskplan_unsupported)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
