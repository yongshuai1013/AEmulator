/* AEmulator addition, 2026-11-28. GPL-3.0; see LICENSE. */
package app.aemu.ui

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import app.aemu.R
import app.aemu.core.*

/**
 * The partition seat switch for [VmSettings.bootSeat], applied in GuestVm (PartitionSeat):
 * the partitions are presented as devices in every boot mode, and a boot runs the boot
 * image ramdisk's own /init as the first guest process instead of the host's service plan.
 * Only offered on the native arm64 engine — the seat is its supervisor's mount table and
 * first-process launch; images whose boot image was not kept at import log the fallback
 * and boot the plan as before.
 */
@Composable
internal fun BootSeatSection(img: GuestImage, enabled: Boolean, setEnabled: (Boolean) -> Unit) {
    if (remember(img.id) { img.effective().engine != Engine.N64 }) return
    Toggle(stringResource(R.string.vm_boot_seat_title), stringResource(R.string.vm_boot_seat_sub), enabled, setEnabled)
}
