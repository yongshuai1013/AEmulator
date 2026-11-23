/* AEmulator Sunset addition, 2026-10-07. GPL-3.0. */
package app.aemu.core

import java.io.File

internal data class HostBatteryState(val percent: Int, val status: Int, val plugged: Int) {
    val ac get() = plugged and 1 != 0
    val usb get() = plugged and 2 != 0
    val wireless get() = plugged and 4 != 0
    val statusText get() = when (status) {
        2 -> "Charging"; 3 -> "Discharging"; 4 -> "Not charging"; 5 -> "Full"; else -> "Unknown"
    }
    fun command(api: Int, previous: HostBatteryState? = null): String {
        val acValue = if (ac || wireless && api < 19) 1 else 0
        val usbValue = if (usb) 1 else 0
        val wirelessValue = if (wireless) 1 else 0
        return buildList {
            if (previous == null || (if (previous.ac || previous.wireless && api < 19) 1 else 0) != acValue)
                add("dumpsys battery set ac $acValue")
            if (previous == null || (if (previous.usb) 1 else 0) != usbValue)
                add("dumpsys battery set usb $usbValue")
            if (api >= 19 && (previous == null || (if (previous.wireless) 1 else 0) != wirelessValue))
                add("dumpsys battery set wireless $wirelessValue")
            if (previous == null || previous.status != status) add("dumpsys battery set status $status")
            if (previous == null || previous.percent != percent) add("dumpsys battery set level $percent")
        }.joinToString("; ")
    }

    /**
     * What the kernel sends over NETLINK_KOBJECT_UEVENT when a power_supply node changes (NUL separated, as on a phone). Android 2.3 - 4.3
     * BatteryService starts observing "SUBSYSTEM=power_supply" and re-reads every node on such a message: there is no
     * `dumpsys battery set` before 4.2 and no other trigger. libaemushim hands these to the guest (native/guestshim/uevent.h).
     */
    fun uevents(): List<ByteArray> {
        fun event(name: String, vararg more: String): ByteArray {
            val path = "/class/power_supply/$name"
            val parts = listOf("change@$path", "ACTION=change", "DEVPATH=$path", "SUBSYSTEM=power_supply", "POWER_SUPPLY_NAME=$name") + more
            return (parts.joinToString("\u0000") + "\u0000").toByteArray()
        }
        return listOf(
            event("ac", "POWER_SUPPLY_ONLINE=${if (ac || wireless) 1 else 0}"),
            event("usb", "POWER_SUPPLY_ONLINE=${if (usb) 1 else 0}"),
            event("battery", "POWER_SUPPLY_STATUS=$statusText", "POWER_SUPPLY_HEALTH=Good", "POWER_SUPPLY_PRESENT=1",
                "POWER_SUPPLY_TECHNOLOGY=Li-ion", "POWER_SUPPLY_CAPACITY=$percent"))
    }

    /** Only emulator-created nodes: never follow ROM symlinks into the host filesystem. */
    fun writeSysfs(root: File) {
        val base = root.canonicalFile
        val current = when (status) { 2 -> "500000"; 3 -> "-400000"; else -> "0" }
        val entries = mapOf("battery/capacity" to percent.toString(), "battery/status" to statusText,
            "battery/current_now" to current, "battery/current_avg" to current,
            "battery/charge_counter" to (40000L * percent).toString(),
            "battery/energy_now" to (40000L * percent).toString(),
            "battery/capacity_level" to when {
                percent >= 95 -> "Full"; percent >= 70 -> "High"; percent >= 30 -> "Normal"; percent >= 15 -> "Low"; else -> "Critical" },
            "ac/online" to if (ac) "1" else "0", "usb/online" to if (usb) "1" else "0",
            "wireless/type" to "Wireless", "wireless/online" to if (wireless) "1" else "0",
            // Samsung's own node: 0 none, 1 USB, 2 AC
            "battery/charging_source" to if (ac || wireless) "2" else if (usb) "1" else "0")
        for ((path, value) in entries) {
            val file = File(base, "sys/class/power_supply/$path")
            check(file.canonicalFile == file.absoluteFile) { "Battery node is a symlink: $path" }
            file.parentFile!!.mkdirs()
            file.writeText("$value\n")
        }
        writeAndroidPower(base)
    }

    /**
     * Pre-1.0 BatteryService reads /sys/android_power/battery_level, _scale, _raw and charging_state (made by
     * TreeFixer.preOnePowerNodes, which is the only thing that creates battery_level: other releases are left alone).
     * Same source as the power_supply nodes above, so host following, the manual level / charging switch of the VM
     * menu and the default (100 %, full, plugged) all reach the guest. The wording of charging_state is a best guess.
     */
    private fun writeAndroidPower(base: File) {
        val dir = File(base, "sys/android_power")
        if (!File(dir, "battery_level").isFile) return
        val charging = when {
            status == 5 && (ac || usb || wireless) -> "Maintaining"
            status == 2 || (status != 3 && status != 4 && (ac || usb || wireless)) -> "Charging"
            else -> "Discharging"
        }
        val entries = mapOf("battery_level" to percent.toString(), "battery_level_scale" to "100",
            "battery_level_raw" to percent.toString(), "charging_state" to charging)
        for ((name, value) in entries) {
            val file = File(dir, name)
            check(file.canonicalFile == file.absoluteFile) { "Battery node is a symlink: $name" }
            file.writeText("$value\n")
            file.setReadable(true, false); file.setWritable(true, false)
        }
    }
    companion object {
        fun from(level: Int, scale: Int, status: Int, plugged: Int): HostBatteryState? {
            if (level < 0 || scale <= 0) return null
            val percent = (level.toLong() * 100 / scale).coerceIn(0, 100).toInt()
            return HostBatteryState(percent, status.takeIf { it in 1..5 } ?: 1, plugged and 7)
        }
    }
}
