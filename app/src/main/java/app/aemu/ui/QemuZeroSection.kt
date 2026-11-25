/* AEmulator addition, 2026-11-25. GPL-3.0; see LICENSE. */
package app.aemu.ui

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import app.aemu.R
import app.aemu.core.*

/**
 * Trial flip of the ro.kernel.qemu marker: the per-VM switch for [VmSettings.qemuZero], applied
 * in GuestVm where the boot overrides are built. Not offered on the legacy (GB) engine, whose
 * guests already boot with ro.kernel.qemu=0 — there the switch would promise a change that
 * does not exist. On the KK and native engines the same write point serves both, so both get it.
 */
@Composable
internal fun QemuZeroSection(img: GuestImage, enabled: Boolean, setEnabled: (Boolean) -> Unit) {
    if (remember(img.id) { img.effective().engine == Engine.GB }) return
    Toggle(stringResource(R.string.vm_qemu_zero_title), stringResource(R.string.vm_qemu_zero_sub), enabled, setEnabled)
}
