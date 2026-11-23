package app.aemu.ui

import app.aemu.R
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.aemu.core.BluetoothPermissions
import app.aemu.core.CarrierPresets
import app.aemu.core.CpuInfo
import app.aemu.core.Engine
import app.aemu.core.GuestImage
import app.aemu.core.HostWifi
import app.aemu.core.NetworkType
import app.aemu.core.StorageSpoof
import app.aemu.core.StorageType
import app.aemu.core.VmSettings
import app.aemu.core.deriveSerialNo
import app.aemu.core.WifiEmu
import kotlin.math.roundToInt

private data class Res(val label: String, val w: Int, val h: Int, val dpi: Int)

private val RESOLUTIONS = listOf(
    Res("480×800", 480, 800, 240),
    Res("540×960", 540, 960, 240),
    Res("720×1280", 720, 1280, 320),
)

private val RAM_STEPS = listOf(0, 256, 512, 768, 1024, 1536, 2048, 3072, 4096)

@Composable
fun SettingsSheet(img: GuestImage, onDismiss: () -> Unit, onSave: (VmSettings) -> Unit) {
    var s by remember { mutableStateOf(img.settings) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
            Text(img.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.vs_screen), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (s.legacyEngine && img.api < 14) {
                Text(stringResource(R.string.vs_fixed_2x), style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = Modifier.fillMaxWidth()) {
                    RESOLUTIONS.forEachIndexed { i, r ->
                        ToggleButton(
                            checked = s.width == r.w && s.height == r.h,
                            onCheckedChange = { s = s.copy(width = r.w, height = r.h, density = r.dpi) },
                            shapes = when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                RESOLUTIONS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        ) { Text(r.label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NumField(stringResource(R.string.vs_width), s.width, Modifier.weight(1f)) { s = s.copy(width = it.coerceIn(240, 2160) and 0x7ffffffe) }
                    NumField(stringResource(R.string.vs_height), s.height, Modifier.weight(1f)) { s = s.copy(height = it.coerceIn(320, 3840) and 0x7ffffffe) }
                    NumField(stringResource(R.string.vs_dpi), s.density, Modifier.weight(1f)) { s = s.copy(density = it.coerceIn(96, 640)) }
                }
                Text(stringResource(R.string.vs_screen_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.vs_perf), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.vs_gpu), stringResource(R.string.vs_gpu_sub), s.gpu) { s = s.copy(gpu = it) }
            if (img.api >= 14) Toggle(stringResource(R.string.vs_hwui), stringResource(R.string.vs_hwui_sub), s.hwui) { s = s.copy(hwui = it) }
            Toggle(stringResource(R.string.vs_jit), stringResource(R.string.vs_jit_sub), s.jit) { s = s.copy(jit = it) }
            if (img.api >= 21) Toggle(stringResource(R.string.vs_fulldex), stringResource(R.string.vs_fulldex_sub), s.fullDexopt) { s = s.copy(fullDexopt = it) }
            if (img.api < 14) Toggle(stringResource(R.string.vs_legacy), stringResource(R.string.vs_legacy_sub), s.legacyEngine) { s = s.copy(legacyEngine = it) }
            val has32 = CpuInfo.hasAArch32
            Toggle(
                stringResource(R.string.vs_native32),
                if (has32) stringResource(R.string.vs_native32_sub) else stringResource(R.string.vs_native32_unsupported),
                s.native32 && has32
            ) { if (has32) s = s.copy(native32 = it) }
            val ramIdx = RAM_STEPS.indexOf(s.ramMb).coerceAtLeast(0)
            ListItem(
                headlineContent = { Text(stringResource(R.string.vs_ram)) },
                supportingContent = {
                    Column {
                        Text(if (s.ramMb == 0) stringResource(R.string.vs_ram_auto) else "${s.ramMb} MB")
                        Slider(value = ramIdx.toFloat(), onValueChange = { s = s.copy(ramMb = RAM_STEPS[it.toInt()]) },
                            valueRange = 0f..RAM_STEPS.lastIndex.toFloat(), steps = RAM_STEPS.size - 2)
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            StoragePicker(s.storageGb, s.storageType, { s = s.copy(storageGb = it) }, { s = s.copy(storageType = it) })
            Toggle(stringResource(R.string.vs_lowram), stringResource(R.string.vs_lowram_sub), s.lowRam) { s = s.copy(lowRam = it) }
            Toggle(stringResource(R.string.vs_proxy), stringResource(R.string.vs_proxy_sub), s.netProxy) { s = s.copy(netProxy = it) }
            Toggle(stringResource(R.string.vs_vibration), stringResource(R.string.vs_vibration_sub), s.vibration) { s = s.copy(vibration = it) }
            Toggle(stringResource(R.string.vs_host_battery), stringResource(R.string.vs_host_battery_sub), s.hostBattery) { s = s.copy(hostBattery = it) }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_radio_title), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.vs_radio), stringResource(R.string.vs_radio_sub), s.radio) { s = s.copy(radio = it) }
            if (s.radio) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = s.imei, onValueChange = { v -> s = s.copy(imei = v.filter(Char::isDigit).take(15)) },
                        label = { Text("IMEI") }, placeholder = { Text(VmSettings.DEFAULT_IMEI) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    TextButton(onClick = { s = s.copy(imei = VmSettings.randomImei()) }) { Text(stringResource(R.string.vs_random)) }
                }
                OperatorSection(s) { s = it }
                Toggle(stringResource(R.string.vs_sim_present), stringResource(R.string.vs_sim_present_sub), s.simPresent) { s = s.copy(simPresent = it) }
                if (s.simPresent) {
                    OutlinedTextField(value = s.simNumber, onValueChange = { v -> s = s.copy(simNumber = VmSettings.cleanSimNumber(v)) },
                        label = { Text(stringResource(R.string.vs_sim_number)) }, supportingText = { Text(stringResource(R.string.vs_sim_number_sub)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    OutlinedTextField(value = s.simIccid, onValueChange = { v -> s = s.copy(simIccid = VmSettings.cleanSimIccid(v)) },
                        label = { Text("ICCID") }, supportingText = { Text(stringResource(R.string.vs_sim_iccid_sub)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                NetworkTypePicker(s.networkType, img.api) { s = s.copy(networkType = it) }
            }

            val wifiOffered = WifiEmu.offered(img.copy(settings = s))
            if (wifiOffered) {
                Toggle(stringResource(R.string.vs_wifi), stringResource(R.string.vs_wifi_sub), s.wifi) { s = s.copy(wifi = it) }
                if (s.wifi) WifiFields(s) { s = it }
            }
            if (img.api in 9..25) BluetoothRow(s) { s = it }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_advanced), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = s.qemuArgs, onValueChange = { s = s.copy(qemuArgs = it) },
                label = { Text(stringResource(R.string.vs_qemu_args)) }, supportingText = { Text(stringResource(R.string.vs_qemu_args_sub)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            OutlinedTextField(value = s.kernelBanner, onValueChange = { s = s.copy(kernelBanner = it) },
                label = { Text(stringResource(R.string.vs_kernel)) }, supportingText = { Text(stringResource(R.string.vs_kernel_sub)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            OutlinedTextField(value = s.bootloaderName, onValueChange = { s = s.copy(bootloaderName = it) },
                label = { Text(stringResource(R.string.vs_bootloader)) }, supportingText = { Text(stringResource(R.string.vs_bootloader_sub)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            OutlinedTextField(value = s.serialNo, onValueChange = { s = s.copy(serialNo = it) },
                label = { Text(stringResource(R.string.vs_serialno)) }, placeholder = { Text(deriveSerialNo(img.id)) },
                supportingText = { Text(stringResource(R.string.vs_serialno_sub)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            val effBaseband = s.basebandOverride.ifBlank { img.baseband }.ifBlank { VmSettings.DEFAULT_BASEBAND }
            OutlinedTextField(value = s.basebandOverride, onValueChange = { s = s.copy(basebandOverride = it) },
                label = { Text(stringResource(R.string.vs_baseband)) }, placeholder = { Text(effBaseband) },
                supportingText = { Text(stringResource(R.string.vs_baseband_sub, effBaseband)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            HardwarePicker(s.hardware) { s = s.copy(hardware = it) }

            Spacer(Modifier.height(8.dp))
            RecoverySection(img)

            var magiskBusy by remember { mutableStateOf(false) }
            MagiskSection(img, s.magisk, magiskBusy, { magiskBusy = it }) { s = s.copy(magisk = it) }
            AgentSection(img, s) { s = it }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_archive), style = MaterialTheme.typography.titleMedium)
            var archiveBusy by remember { mutableStateOf(false) }
            VmExportSection(img, s, archiveBusy) { archiveBusy = it }
            VmImportSection(img, archiveBusy, { archiveBusy = it }) { s = it }
            EraseDataSection(img)

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_controls), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.vs_nav), stringResource(R.string.vs_nav_sub), s.showNavBar) { s = s.copy(showNavBar = it) }
            if (s.showNavBar) NavBarSetup(s) { s = it }
            Toggle(stringResource(R.string.vs_awake), stringResource(R.string.vs_awake_sub), s.keepScreenOn) { s = s.copy(keepScreenOn = it) }
            Toggle(stringResource(R.string.as_led), stringResource(R.string.as_led_sub), s.showLed) { s = s.copy(showLed = it) }
            Toggle(stringResource(R.string.vs_single), stringResource(R.string.vs_single_sub), s.mtMode == 4) { s = s.copy(mtMode = if (it) 4 else 0) }

            Spacer(Modifier.height(16.dp))
            val wifiOk = !(s.wifi && wifiOffered) || (WifiEmu.cleanSsid(s.wifiSsid).isNotEmpty() && WifiEmu.passwordValid(s.wifiPassword))
            Button(enabled = wifiOk, onClick = { onSave(s) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.save)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Operator identity of the fake modem: pick a real carrier preset or type a custom name/code. */
@Composable
private fun OperatorSection(s: VmSettings, set: (VmSettings) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val effName = s.operatorName.ifBlank { VmSettings.DEFAULT_OPERATOR_NAME }
    val effNumeric = s.operatorNumeric.ifBlank { VmSettings.DEFAULT_OPERATOR_NUMERIC }
    val preset = CarrierPresets.presets.firstOrNull { it.displayName == s.operatorName && it.plmn == s.operatorNumeric }
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_operator)) },
        supportingContent = { Text(if (preset != null) "${preset.displayName} · ${preset.region} · ${preset.plmn}" else "$effName · $effNumeric") },
        trailingContent = {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.vs_operator_hide else R.string.vs_operator_choose))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
    if (expanded) {
        CarrierPresets.presets.forEach { p ->
            ListItem(
                headlineContent = { Text("${p.displayName} · ${p.region}") },
                supportingContent = { Text("${p.name} · ${p.plmn}") },
                trailingContent = if (p == preset) ({ Text("✓") }) else null,
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().clickable {
                    set(s.copy(operatorName = p.displayName, operatorNumeric = p.plmn)); expanded = false
                },
            )
        }
    }
    OutlinedTextField(value = s.operatorName, onValueChange = { set(s.copy(operatorName = it)) },
        label = { Text(stringResource(R.string.vs_operator_name)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    OutlinedTextField(value = s.operatorNumeric, onValueChange = { v -> set(s.copy(operatorNumeric = v.filter(Char::isDigit).take(6))) },
        label = { Text(stringResource(R.string.vs_operator_numeric)) }, supportingText = { Text(stringResource(R.string.vs_operator_sub)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    if (s.operatorName.isNotBlank() || s.operatorNumeric.isNotBlank()) {
        TextButton(onClick = { set(s.copy(operatorName = "", operatorNumeric = "")) }) { Text(stringResource(R.string.vs_operator_reset)) }
    }
}

/** ro.hardware given to the guest: free text plus a menu of common values; blank = the firmware's own. */
@Composable
private fun HardwarePicker(value: String, set: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(value = value, onValueChange = { set(VmSettings.cleanHardware(it)) },
            label = { Text(stringResource(R.string.vs_hardware)) }, placeholder = { Text(stringResource(R.string.vs_hardware_default)) },
            supportingText = { Text(stringResource(R.string.vs_hardware_sub)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.vs_hardware_pick)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.vs_hardware_default)) }, onClick = { open = false; set("") })
                VmSettings.HARDWARE_PRESETS.forEach { hw ->
                    DropdownMenuItem(text = { Text(hw) }, onClick = { open = false; set(hw) })
                }
            }
        }
    }
}

/** Radio technology the fake modem reports (2G … LTE); only the values the firmware's API level knows are offered. */
@Composable
private fun NetworkTypePicker(tech: Int, api: Int, set: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_network)) },
        supportingContent = { Text(stringResource(R.string.vs_network_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) { Text(NetworkType.label(NetworkType.sanitize(tech, api))) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    NetworkType.available(api).forEach { e ->
                        DropdownMenuItem(text = { Text(e.label) }, onClick = { open = false; set(e.tech) })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Storage size the guest is told it has, and the eMMC/UFS identity it sees (AEmulator Plus's picker). */
@Composable
private fun StoragePicker(gb: Int, type: String, setGb: (Int) -> Unit, setType: (String) -> Unit) {
    val steps = listOf(0) + StorageSpoof.PRESETS_GB
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_storage)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.vs_storage_sub))
                Slider(value = steps.indexOf(StorageSpoof.sanitizeGb(gb)).toFloat(),
                    onValueChange = { setGb(steps[it.roundToInt().coerceIn(0, steps.lastIndex)]) },
                    valueRange = 0f..steps.lastIndex.toFloat(), steps = steps.size - 2,
                    modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vs_storage_type), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { open = true }) {
                            Text(StorageType.parse(type)?.label ?: stringResource(R.string.vs_storage_type_auto))
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.vs_storage_type_auto)) }, onClick = { open = false; setType("") })
                            StorageType.entries.forEach { e ->
                                DropdownMenuItem(text = { Text(e.label) }, onClick = { open = false; setType(e.id) })
                            }
                        }
                    }
                }
                Text(stringResource(R.string.vs_storage_type_sub), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            Text(if (gb > 0) "$gb GB" else stringResource(R.string.vs_storage_auto), style = MaterialTheme.typography.bodyMedium)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Which buttons the bar has (tap to add or remove; added ones go to the end) and how big it is. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NavBarSetup(s: VmSettings, set: (VmSettings) -> Unit) {
    val keys = s.navKeys.split(',').filter { it in VmSettings.NAV_ALL }
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in keys + VmSettings.NAV_ALL.filter { it !in keys }) {
            val on = k in keys
            androidx.compose.material3.FilterChip(selected = on, label = { Text(navKeyLabel(k)) },
                onClick = { set(s.copy(navKeys = (if (on) keys - k else keys + k).joinToString(","))) })
        }
    }
    Toggle(stringResource(R.string.vs_nav_compact), stringResource(R.string.vs_nav_compact_sub), s.navSize == 0) { set(s.copy(navSize = if (it) 0 else 1)) }
}

@Composable
internal fun navKeyLabel(k: String) = stringResource(when (k) {
    "back" -> R.string.back; "home" -> R.string.home; "recents" -> R.string.recents; "menu" -> R.string.menu
    "search" -> R.string.nav_search; "volup" -> R.string.vol_up; "voldown" -> R.string.vol_down
    "power" -> R.string.power; else -> R.string.nav_dpad
})

@Composable
internal fun Toggle(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        trailingContent = { Switch(checked = on, onCheckedChange = set) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumField(label: String, value: Int, modifier: Modifier, set: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(value = text, onValueChange = { v -> text = v.filter(Char::isDigit).take(4); text.toIntOrNull()?.let(set) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

/** Recovery of this firmware: stock one from the import, or a custom recovery.img (TWRP, OrangeFox, CWM…). */
/** Name and optional password of the emulated Wi-Fi network (blank password = open network). (AEmulator Plus) */
@Composable
private fun WifiFields(s: VmSettings, set: (VmSettings) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(value = s.wifiSsid, onValueChange = { set(s.copy(wifiSsid = WifiEmu.cleanSsid(it))) },
        label = { Text(stringResource(R.string.vs_wifi_ssid)) }, singleLine = true,
        isError = s.wifiSsid.isEmpty(),
        supportingText = { Text(stringResource(if (s.wifiSsid.isEmpty()) R.string.vs_wifi_ssid_error else R.string.vs_wifi_ssid_sub)) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    OutlinedTextField(value = s.wifiPassword, onValueChange = { set(s.copy(wifiPassword = WifiEmu.cleanPassword(it))) },
        label = { Text(stringResource(R.string.vs_wifi_password)) }, singleLine = true,
        isError = !WifiEmu.passwordValid(s.wifiPassword),
        supportingText = { Text(stringResource(if (WifiEmu.passwordValid(s.wifiPassword)) R.string.vs_wifi_password_sub else R.string.vs_wifi_password_error)) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(stringResource(if (show) R.string.vs_wifi_hide else R.string.vs_wifi_show)) } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    var open by remember { mutableStateOf(false) }
    fun bars(n: Int) = "▂▄▆█".take(n) + "·".repeat(WifiEmu.MAX_BARS - n)
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_wifi_signal)) },
        supportingContent = { Text(stringResource(R.string.vs_wifi_signal_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) { Text(bars(s.wifiSignal)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (n in WifiEmu.MAX_BARS downTo 1) DropdownMenuItem(text = { Text(bars(n)) }, onClick = { open = false; set(s.copy(wifiSignal = n)) })
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
    Toggle(stringResource(R.string.vs_wifi_neighbors), stringResource(R.string.vs_wifi_neighbors_sub), s.wifiNeighbors) { set(s.copy(wifiNeighbors = it)) }
    // this fork's addition: the phone's own connection instead of the typed network, when it can be read
    val ctx = LocalContext.current
    var asked by remember { mutableStateOf(0) }
    val granted = remember(asked) { HostWifi.hasPermission(ctx) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked++ }
    Toggle(stringResource(R.string.vs_wifi_mirror), stringResource(R.string.vs_wifi_mirror_sub), s.wifiMirrorHost) { on ->
        set(s.copy(wifiMirrorHost = on))
        if (on && !HostWifi.hasPermission(ctx)) ask.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (s.wifiMirrorHost && !granted) {
        Text(stringResource(R.string.vs_wifi_mirror_noperm), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
    }
}

/** Host Bluetooth bridge (AEmulator-Plus #18): the toggle plus the runtime permissions the bridge needs. */
@Composable
private fun BluetoothRow(s: VmSettings, set: (VmSettings) -> Unit) {
    val ctx = LocalContext.current
    var asked by remember { mutableStateOf(0) }
    val missing = remember(asked) { BluetoothPermissions.missing(ctx) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked++ }
    Toggle(stringResource(R.string.vs_bluetooth), stringResource(R.string.vs_bluetooth_sub), s.bluetooth) { on ->
        set(s.copy(bluetooth = on))
        if (on) BluetoothPermissions.missing(ctx).takeIf { it.isNotEmpty() }?.let { ask.launch(it.toTypedArray()) }
    }
    if (s.bluetooth && missing.isNotEmpty()) {
        Text(stringResource(R.string.vs_bluetooth_noperm), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun RecoverySection(img: GuestImage) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val paths = remember(img.id) { app.aemu.core.VmPaths(ctx, img.id) }
    var status by remember { mutableStateOf(recoveryStatus(paths)) }
    var busy by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.vs_recovery_failed)
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        Thread {
            val ok = runCatching {
                val data = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                app.aemu.core.RecoveryImage.install(paths, data) {}
            }.getOrDefault(false)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false
                status = recoveryStatus(paths)
                if (!ok) android.widget.Toast.makeText(ctx, failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }
    Text(stringResource(R.string.vs_recovery), style = MaterialTheme.typography.titleMedium)
    ListItem(
        headlineContent = { Text(status ?: stringResource(R.string.vs_recovery_none)) },
        supportingContent = { Text(stringResource(R.string.vs_recovery_sub)) },
        trailingContent = {
            TextButton(enabled = !busy, onClick = { pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.vs_recovery_install)) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun recoveryStatus(paths: app.aemu.core.VmPaths): String? {
    if (!app.aemu.core.RecoveryImage.installed(paths)) return null
    val dir = app.aemu.core.RecoveryImage.dir(paths)
    val prop = runCatching { java.io.File(dir, "default.prop").readText() }.getOrDefault("")
    val twrp = Regex("""ro\.twrp\.version=(\S+)""").find(prop)?.groupValues?.get(1)
    return when {
        twrp != null -> "TWRP $twrp"
        java.io.File(dir, "twres").isDirectory -> "TWRP"
        java.io.File(dir, "sbin/orangefox.sh").exists() || prop.contains("orangefox", true) -> "OrangeFox"
        prop.contains("cwm", true) || java.io.File(dir, "res/images/icon_clockwork.png").exists() -> "ClockworkMod"
        else -> "Stock recovery"
    }
}

/**
 * Full erase of this machine's data (AEmulator-Plus VmDataReset): the whole /data and /cache go,
 * including the dalvik-cache the library's factory reset keeps; the owners table is pruned of the
 * erased inodes so recycled ones do not inherit stale UIDs. No backup is made.
 */
@Composable
private fun EraseDataSection(img: GuestImage) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { confirm = true }) {
        Text(stringResource(R.string.vs_erase))
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.vs_erase)) },
        text = { Text(stringResource(R.string.vs_erase_info)) },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = {
            confirm = false
            if (vmIsRunning(ctx)) { message = ctx.getString(R.string.vs_archive_running); return@TextButton }
            busy = true
            Thread {
                val ok = runCatching {
                    val paths = app.aemu.core.VmPaths(ctx, img.id)
                    app.aemu.core.VmDataReset.reset(paths.dir.parentFile!!, img.id) { f ->
                        val st = android.system.Os.lstat(f.path)
                        String.format(java.util.Locale.ROOT, "%016x %016x", st.st_dev, st.st_ino)
                    }
                    app.aemu.core.ImageStore.update(ctx, img.id) { it.copy(sizeBytes = app.aemu.core.ImageStore.du(paths.dir)) }
                }.isSuccess
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    busy = false
                    message = ctx.getString(if (ok) R.string.vs_erase_done else R.string.vs_erase_failed)
                }
            }.start()
        }) { Text(stringResource(R.string.vs_erase)) } },
    )
}
