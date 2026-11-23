package app.aemu.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build

/**
 * The phone's own Wi-Fi connection, read once at VM start for the emulated Wi-Fi mirror
 * ([VmSettings.wifiMirrorHost]): the guest then shows the network the phone is really connected
 * to — its name, address, signal and, when a scan can be read, the networks around it.
 *
 * Reading any of this needs the location permission (on Android 13+ the nearby-Wi-Fi one also
 * counts): without it, or with location off, the system hands back redacted values
 * ("<unknown ssid>", the placeholder BSSID, -127 dBm) — those are recognised here and the whole
 * read counts as "no mirror", so the guest falls back to the network name typed in the settings.
 * Nothing here ever invents a value.
 */
object HostWifi {

    /** One read of the phone's connection; [neighbors] is null when no scan could be read. */
    data class State(
        val ssid: String,
        val bssid: String,
        /** signal in dBm, as WifiInfo reports it */
        val rssi: Int,
        /** MHz; 0 = not reported */
        val frequency: Int,
        /** Mb/s; 0 = not reported */
        val speed: Int,
        /** scan lines in the guest service's format ("ssid|bssid|caps|rssi|frequency") */
        val neighbors: List<String>?,
    )

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED)

    /** The phone's current connection, or null when there is none to mirror. */
    fun current(ctx: Context): State? {
        if (!hasPermission(ctx)) return null
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return runCatching {
            @Suppress("DEPRECATION") val info = wm.connectionInfo ?: return null
            val ssid = info.ssid?.removeSurrounding("\"")?.let { WifiEmu.cleanSsid(it) }.orEmpty()
            if (ssid.isEmpty() || ssid == "<unknown ssid>") return null
            val bssid = info.bssid?.lowercase() ?: return null
            if (bssid == "02:00:00:00:00:00") return null
            if (info.rssi <= -127 || info.networkId == -1) return null
            // a fresh scan for the next start; the results read below are the system's last one
            runCatching { @Suppress("DEPRECATION") wm.startScan() }
            val neighbors = runCatching {
                @Suppress("DEPRECATION") val scan = wm.scanResults ?: return@runCatching null
                scan.filter { r -> !r.BSSID.isNullOrEmpty() && !r.BSSID.equals(bssid, true) && !r.SSID.isNullOrEmpty() }
                    .sortedByDescending { it.level }
                    .take(10)
                    .map { r -> "${WifiEmu.cleanSsid(r.SSID)}|${r.BSSID.lowercase()}|${r.capabilities}|${r.level}|${r.frequency}" }
            }.getOrNull()
            State(
                ssid = ssid,
                bssid = bssid,
                rssi = info.rssi,
                frequency = if (info.frequency > 0) info.frequency else 0,
                speed = if (info.linkSpeed > 0) info.linkSpeed else 0,
                neighbors = neighbors,
            )
        }.getOrNull()
    }
}
