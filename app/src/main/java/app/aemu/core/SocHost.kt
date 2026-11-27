/* Added for AEmulator Plus, 2026-11-19. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File

/** The phone the emulator runs on, as [SocProfiles.resolve] needs it for the "Host" chip. Every read can fail (SELinux); then it says nothing. */
object SocHost : HostSocSource {
    override fun cpuinfo(): String? = runCatching { File("/proc/cpuinfo").readText().takeIf { it.isNotBlank() } }.getOrNull()

    override fun soc0(name: String): String? = runCatching { File("/sys/devices/soc0/$name").readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()

    override fun prop(name: String): String? = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, name) as String).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    override fun cpuFreqKhz(n: Int): Pair<Int, Int>? = runCatching {
        fun read(f: String) = File("/sys/devices/system/cpu/cpu$n/cpufreq/$f").readText().trim().toInt()
        val min = read("cpuinfo_min_freq"); val max = read("cpuinfo_max_freq")
        if (max > 0 && min in 1..max) min to max else null
    }.getOrNull()

    override fun cpuCount(): Int = Runtime.getRuntime().availableProcessors()
}
