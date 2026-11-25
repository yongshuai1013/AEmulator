/* AEmulator addition, 2026-11-25. GPL-3.0; see LICENSE. */
package app.aemu.ui

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import app.aemu.R
import app.aemu.core.*

/**
 * Root (Superuser) for the native engine: the classic su stack, built from source with this app
 * (see SuperuserSupport) — no file to pick, just the per-VM switch. Shown only where
 * [SuperuserSupport.offered] holds. Independent of the Magisk switch; both may be on.
 */
@Composable
internal fun SuperuserSection(img: GuestImage, enabled: Boolean, setEnabled: (Boolean) -> Unit) {
    if (!remember(img.id) { SuperuserSupport.offered(img) }) return
    Toggle(stringResource(R.string.vm_superuser_title), stringResource(R.string.vm_superuser_sub), enabled, setEnabled)
}
