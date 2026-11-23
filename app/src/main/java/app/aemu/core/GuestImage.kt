package app.aemu.core

import org.json.JSONArray
import org.json.JSONObject

/** Настройки конкретного образа, которые пользователь может менять. */
data class VmSettings(
    val width: Int = 540,
    val height: Int = 960,
    val density: Int = 240,
    /** аппаратная отрисовка через GPU телефона (иначе программный растеризатор прошивки) */
    val gpu: Boolean = true,
    /** hwui в гостевых приложениях (4.x) */
    val hwui: Boolean = true,
    /** JIT Dalvik; без него стабильнее, но медленнее */
    val jit: Boolean = true,
    /** ART (5.0+): compile apps fully to machine code at install/first boot; off = interpret-only, fast first boot */
    val fullDexopt: Boolean = false,
    /** частота развёртки гостя, Гц */
    val fbHz: Int = 60,
    /** касаний в секунду, которые шлём гостю */
    val touchHz: Int = 60,
    /** HTTP/HTTPS-прокси с современным TLS для старых браузеров */
    val netProxy: Boolean = true,
    val lowRam: Boolean = false,
    val showFrame: Boolean = false,
    val showNavBar: Boolean = true,
    /** buttons of the bar under the screen, in order: back home recents menu search volup voldown power dpad */
    val navKeys: String = NAV_DEFAULT,
    /** size of the button bar: 0 compact, 1 large */
    val navSize: Int = 0,
    val keepScreenOn: Boolean = true,
    /** the guest phone's notification LED drawn above its screen */
    val showLed: Boolean = true,
    val mtMode: Int = 0,
    /** старый движок для 2.x (GL через pbuffer, только GLES 1.x) — запасной вариант */
    val legacyEngine: Boolean = false,
    /** guest RAM budget in MB, 0 = automatic */
    val ramMb: Int = 0,
    /** emulate the radio (RIL); off = tablet-like firmware without telephony */
    val radio: Boolean = true,
    /** hardware 32-bit (AArch32 EL0) bypass without translator on supported CPUs */
    val native32: Boolean = false,
    /** IMEI reported by the fake modem; blank = default sample IMEI */
    val imei: String = "",
    /** extra qemu options; KEY=VALUE tokens are passed as environment variables */
    val qemuArgs: String = "",
    /**
     * The guest kernel's version banner ("Linux version …"): written to /proc/version and
     * reported by uname. Filled in from the firmware's boot.img at import; blank = the
     * feature is off and the guest keeps the host's answers. Takes effect at the next boot.
     */
    val kernelBanner: String = "",
    /**
     * The boot loader version string the guest reports as ro.bootloader ("angler-03.84").
     * Derived at import from the factory package's bootloader file name; blank = not derivable,
     * the guest then keeps the template's answer. Takes effect at the next boot.
     */
    val bootloaderName: String = "",
    /**
     * The serial number the guest reports as ro.serialno. Filled in at import with the value
     * [deriveSerialNo] makes from the image id (stable per image, unique across images);
     * blank = derived from the image id at boot instead.
     */
    val serialNo: String = "",
    /** operator name the fake modem reports; blank = the generic sample name */
    val operatorName: String = "",
    /** operator PLMN (MCC+MNC digits) the fake modem reports; blank = the test network 00101 */
    val operatorNumeric: String = "",
    /**
     * Baseband version the fake modem reports, typed by hand; blank = the version implied
     * by the image's factory package, and the generic answer while that is unknown too.
     * Takes effect at the next boot.
     */
    val basebandOverride: String = "",
    /** the guest's battery follows the phone's real level and charging state (AEmulator Plus) */
    val hostBattery: Boolean = true,
    /** the guest may vibrate the phone through the vibrator bridge (AEmulator Plus; legacy engines) */
    val vibration: Boolean = true,
    /** ro.hardware given to the guest at launch; blank = whatever the firmware defines (AEmulator Plus) */
    val hardware: String = "",
    /**
     * Radio technology the fake modem reports (a NetworkType value: 2G … LTE). Default UMTS —
     * the value this fork's modem always reported before the setting existed (AEmulator Plus's
     * own default is EDGE; the field and picker are theirs).
     */
    val networkType: Int = NetworkType.UMTS,
    /** storage size the guest is told it has, in GiB; 0 = the phone's own disk, unspoofed (AEmulator Plus) */
    val storageGb: Int = 0,
    /** storage technology the guest sees (an id of StorageType); blank = the firmware's own (AEmulator Plus) */
    val storageType: String = "",
    /** SIM card inserted in the fake modem; off = empty slot, the guest shows "No SIM card" (AEmulator Plus) */
    val simPresent: Boolean = true,
    /** the SIM's own phone number (EF_MSISDN); blank = the card carries no number (AEmulator Plus) */
    val simNumber: String = "",
    /** the SIM's serial (EF_ICCID); blank = derived from the operator code and the IMEI, as before (AEmulator Plus) */
    val simIccid: String = "",
    /** Emulated Wi-Fi: the guest sees a connected Wi-Fi network [wifiSsid] that carries the host's internet; applies at the next full VM boot (AEmulator Plus) */
    val wifi: Boolean = false,
    /** name of the emulated Wi-Fi network */
    val wifiSsid: String = WifiEmu.DEFAULT_SSID,
    /** passphrase of the emulated network; blank = an open network */
    val wifiPassword: String = "",
    /** Bars the emulated Wi-Fi shows, 1..4 (see [WifiEmu.rssiFor]) (AEmulator Plus) */
    val wifiSignal: Int = WifiEmu.MAX_BARS,
    /** the emulated Wi-Fi also shows a few invented neighbouring networks (AEmulator Plus) */
    val wifiNeighbors: Boolean = true,
    /**
     * the emulated Wi-Fi mirrors the phone's own connection (its real name, address, signal and scan)
     * instead of the name above, when the phone is connected and the permission is there; otherwise the
     * name above is used. This fork's addition; Plus has no mirror.
     */
    val wifiMirrorHost: Boolean = true,
    /** Experimental host Bluetooth passthrough (scan, RFCOMM, GATT); applies at the next full VM boot. */
    val bluetooth: Boolean = false,
    /** Magisk root (arm64 guests on the native engine): the host starts the daemon at boot, see MagiskCore; applies at the next full VM boot (AEmulator Plus) */
    val magisk: Boolean = false,
    /** the guest agent (identity sync for the per-app views, the APK import inbox): a guest service the host starts at boot, see AgentSupport; applies at the next full VM boot */
    val agent: Boolean = false,
    /** packages that read the guest without root in it (the per-app views, see AgentSupport): package names, resolved to uids by the agent */
    val hiddenApps: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("width", width).put("height", height).put("density", density)
        .put("gpu", gpu).put("hwui", hwui).put("jit", jit).put("fullDexopt", fullDexopt).put("fbHz", fbHz)
        .put("touchHz", touchHz).put("netProxy", netProxy).put("lowRam", lowRam)
        .put("showFrame", showFrame).put("showNavBar", showNavBar).put("navKeys", navKeys).put("navSize", navSize)
        .put("keepScreenOn", keepScreenOn).put("showLed", showLed).put("mtMode", mtMode).put("legacyEngine", legacyEngine)
        .put("ramMb", ramMb).put("radio", radio).put("native32", native32).put("imei", imei).put("qemuArgs", qemuArgs)
        .put("kernelBanner", kernelBanner)
        .put("bootloaderName", bootloaderName)
        .put("serialNo", serialNo)
        .put("operatorName", operatorName).put("operatorNumeric", operatorNumeric)
        .put("basebandOverride", basebandOverride)
        .put("hostBattery", hostBattery)
        .put("vibration", vibration)
        .put("hardware", hardware)
        .put("networkType", networkType)
        .put("storageGb", storageGb)
        .put("storageType", storageType)
        .put("simPresent", simPresent)
        .put("simNumber", simNumber)
        .put("simIccid", simIccid)
        .put("wifi", wifi).put("wifiSsid", wifiSsid).put("wifiPassword", wifiPassword)
        .put("wifiSignal", wifiSignal).put("wifiNeighbors", wifiNeighbors)
        .put("wifiMirrorHost", wifiMirrorHost)
        .put("bluetooth", bluetooth)
        .put("magisk", magisk)
        .put("agent", agent)
        .put("hiddenApps", org.json.JSONArray(hiddenApps))

    companion object {
        const val NAV_DEFAULT = "back,home,recents,menu"
        val NAV_ALL = listOf("back", "home", "recents", "menu", "search", "voldown", "volup", "power", "dpad")

        /** Luhn-valid sample IMEI from the standard; the guest has no real modem */
        const val DEFAULT_IMEI = "490154203237518"

        /** operator identity of the generic fake modem, used while the settings leave it blank */
        const val DEFAULT_OPERATOR_NAME = "AEmulator"
        const val DEFAULT_OPERATOR_NUMERIC = "00101"

        /** baseband version of the generic fake modem, used while nothing else names one */
        const val DEFAULT_BASEBAND = "AEmulator"

        /** Suggested ro.hardware values for the settings menu (any other value can be typed). */
        val HARDWARE_PRESETS = listOf("goldfish", "ranchu", "qcom")

        /** Characters allowed in ro.hardware: it names HAL libraries (gralloc.<hw>.so, init.<hw>.rc), so no path or shell characters. */
        const val HARDWARE_MAX = 32
        fun cleanHardware(v: String): String =
            v.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' }.take(HARDWARE_MAX)

        /** Phone number as stored on the SIM: digits with an optional leading +, at most 15 digits (AEmulator Plus). */
        fun cleanSimNumber(v: String): String {
            val digits = v.filter(Char::isDigit).take(15)
            return if (v.trimStart().startsWith("+") && digits.isNotEmpty()) "+$digits" else digits
        }

        /** SIM serial as stored on the card: digits only, at most 20 (AEmulator Plus). */
        fun cleanSimIccid(v: String): String = v.filter(Char::isDigit).take(20)

        /** Random Luhn-valid IMEI with a test TAC prefix. */
        fun randomImei(): String {
            val d = IntArray(14) { if (it < 2) intArrayOf(3, 5)[it] else (0..9).random() }
            var sum = 0
            for (i in 0 until 14) { var x = d[i]; if (i % 2 == 1) { x *= 2; if (x > 9) x -= 9 }; sum += x }
            return d.joinToString("") + ((10 - sum % 10) % 10)
        }

        fun fromJson(o: JSONObject?): VmSettings {
            if (o == null) return VmSettings()
            val d = VmSettings()
            return VmSettings(
                width = o.optInt("width", d.width),
                height = o.optInt("height", d.height),
                density = o.optInt("density", d.density),
                gpu = o.optBoolean("gpu", d.gpu),
                hwui = o.optBoolean("hwui", d.hwui),
                jit = o.optBoolean("jit", d.jit),
                fullDexopt = o.optBoolean("fullDexopt", d.fullDexopt),
                fbHz = o.optInt("fbHz", d.fbHz),
                touchHz = o.optInt("touchHz", d.touchHz),
                netProxy = o.optBoolean("netProxy", d.netProxy),
                lowRam = o.optBoolean("lowRam", d.lowRam),
                showFrame = o.optBoolean("showFrame", d.showFrame),
                showNavBar = o.optBoolean("showNavBar", d.showNavBar),
                navKeys = o.optString("navKeys", d.navKeys),
                navSize = o.optInt("navSize", d.navSize),
                keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
                showLed = o.optBoolean("showLed", d.showLed),
                mtMode = o.optInt("mtMode", d.mtMode),
                legacyEngine = o.optBoolean("legacyEngine", false),
                ramMb = o.optInt("ramMb", 0),
                radio = o.optBoolean("radio", true),
                native32 = o.optBoolean("native32", false),
                imei = o.optString("imei", ""),
                qemuArgs = o.optString("qemuArgs", ""),
                kernelBanner = o.optString("kernelBanner", ""),
                bootloaderName = o.optString("bootloaderName", ""),
                serialNo = o.optString("serialNo", ""),
                operatorName = o.optString("operatorName", ""),
                operatorNumeric = o.optString("operatorNumeric", ""),
                basebandOverride = o.optString("basebandOverride", ""),
                hostBattery = o.optBoolean("hostBattery", true),
                vibration = o.optBoolean("vibration", true),
                hardware = cleanHardware(o.optString("hardware", "")),
                networkType = NetworkType.normalize(o.optInt("networkType", NetworkType.UMTS)),
                storageGb = StorageSpoof.sanitizeGb(o.optInt("storageGb", 0)),
                storageType = StorageType.parse(o.optString("storageType", ""))?.id ?: "",
                simPresent = o.optBoolean("simPresent", true),
                simNumber = cleanSimNumber(o.optString("simNumber", "")),
                simIccid = cleanSimIccid(o.optString("simIccid", "")),
                wifi = o.optBoolean("wifi", d.wifi),
                wifiSsid = WifiEmu.cleanSsid(o.optString("wifiSsid", d.wifiSsid)).ifEmpty { WifiEmu.DEFAULT_SSID },
                wifiPassword = WifiEmu.cleanPassword(o.optString("wifiPassword", "")),
                wifiSignal = o.optInt("wifiSignal", d.wifiSignal).coerceIn(1, WifiEmu.MAX_BARS),
                wifiNeighbors = o.optBoolean("wifiNeighbors", d.wifiNeighbors),
                wifiMirrorHost = o.optBoolean("wifiMirrorHost", d.wifiMirrorHost),
                bluetooth = o.optBoolean("bluetooth", d.bluetooth),
                magisk = o.optBoolean("magisk", d.magisk),
                agent = o.optBoolean("agent", d.agent),
                hiddenApps = o.optJSONArray("hiddenApps")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }.distinct() } ?: d.hiddenApps,
            )
        }
    }
}

/** Служба гостя, которую мы запускаем вместо init. */
data class GuestService(
    val name: String,
    val argv: List<String>,
    /** сокеты init: имя → права (например "zygote" → "0666") */
    val sockets: Map<String, String> = emptyMap(),
    val uid: Int = 0,
    val gid: Int = 0,
    /** ждать появления этого сокета перед следующей службой */
    val waitSocket: String? = null,
    val delayMs: Long = 0,
    val restart: Boolean = false,
    /** вендорский демон: если упадёт — не страшно */
    val optional: Boolean = false,
    /** not started at boot: the guest asks for it with ctl.start (a service outside init's started classes) */
    val onDemand: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("optional", optional).put("onDemand", onDemand)
        .put("name", name)
        .put("argv", JSONArray(argv))
        .put("sockets", JSONObject(sockets as Map<*, *>))
        .put("uid", uid).put("gid", gid)
        .put("waitSocket", waitSocket ?: JSONObject.NULL)
        .put("delayMs", delayMs).put("restart", restart)

    companion object {
        fun fromJson(o: JSONObject): GuestService {
            val argv = o.getJSONArray("argv").let { a -> List(a.length()) { a.getString(it) } }
            val socks = o.optJSONObject("sockets")?.let { s -> s.keys().asSequence().associateWith { s.getString(it) } } ?: emptyMap()
            return GuestService(
                name = o.getString("name"),
                argv = argv,
                sockets = socks,
                uid = o.optInt("uid", 0),
                gid = o.optInt("gid", 0),
                waitSocket = if (o.isNull("waitSocket")) null else o.optString("waitSocket"),
                delayMs = o.optLong("delayMs", 0),
                restart = o.optBoolean("restart", false),
                optional = o.optBoolean("optional", false),
                onDemand = o.optBoolean("onDemand", false),
            )
        }
    }
}

/**
 * Импортированный образ прошивки. Всё, что нужно для запуска, выводится из самой прошивки
 * при импорте (build.prop, init*.rc, состав /system) — никаких захардкоженных профилей устройств.
 */
data class GuestImage(
    val id: String,
    val name: String,
    val release: String,
    val api: Int,
    val brand: String,
    val model: String,
    val skin: String,
    val engine: Engine,
    val abi: String = "armeabi-v7a",
    val bootclasspath: String,
    val exports: Map<String, String>,
    val dirs: List<String>,
    val services: List<GuestService>,
    val sdcardPath: String,
    /** тома из storage_list.xml, которыми управляет vold (не эмулируемые): о них должна знать заглушка vold */
    val volumes: List<String> = emptyList(),
    val settings: VmSettings,
    val createdAt: Long,
    val sizeBytes: Long = 0,
    val sourceName: String = "",
    val runtime: String = "dalvik",
    val warnings: List<String> = emptyList(),
    val lastBootMs: Long = 0,
    val bootCount: Int = 0,
    /** версия анализатора, которым построен профиль; устаревший профиль пересчитывается перед запуском */
    val profileVersion: Int = 0,
    /** container: id of the image whose /system this one shares (its own /data, card and settings) */
    val baseId: String = "",
    /** baseband version implied by the factory package's radio file name ("" → the stub's generic answer) */
    val baseband: String = "",
) {
    val displayVersion: String get() = "Android $release (API $api)"

    /** Движок с учётом выбора пользователя. */
    fun effective(): GuestImage = copy(engine = when {
        abi == "arm64-v8a" -> Engine.N64
        settings.legacyEngine && api < 14 -> Engine.GB
        else -> Engine.KK
    })

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("release", release).put("api", api)
        .put("brand", brand).put("model", model).put("skin", skin)
        .put("engine", engine.id).put("abi", abi)
        .put("bootclasspath", bootclasspath)
        .put("exports", JSONObject(exports as Map<*, *>))
        .put("dirs", JSONArray(dirs))
        .put("services", JSONArray(services.map { it.toJson() }))
        .put("sdcardPath", sdcardPath)
        .put("volumes", JSONArray(volumes))
        .put("settings", settings.toJson())
        .put("createdAt", createdAt).put("sizeBytes", sizeBytes)
        .put("sourceName", sourceName).put("runtime", runtime)
        .put("warnings", JSONArray(warnings))
        .put("lastBootMs", lastBootMs).put("bootCount", bootCount)
        .put("profileVersion", profileVersion)
        .put("baseId", baseId)
        .put("baseband", baseband)

    companion object {
        fun fromJson(o: JSONObject): GuestImage {
            val ex = o.optJSONObject("exports")
            val exports = ex?.keys()?.asSequence()?.associateWith { ex.getString(it) } ?: emptyMap()
            val dirs = o.optJSONArray("dirs")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val svcs = o.optJSONArray("services")?.let { a -> List(a.length()) { GuestService.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            val warn = o.optJSONArray("warnings")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            return GuestImage(
                id = o.getString("id"),
                name = o.optString("name", "Android"),
                release = o.optString("release", "?"),
                api = o.optInt("api", 19),
                brand = o.optString("brand", ""),
                model = o.optString("model", ""),
                skin = o.optString("skin", "AOSP"),
                engine = Engine.byId(o.optString("engine")),
                abi = o.optString("abi", "armeabi-v7a"),
                bootclasspath = o.optString("bootclasspath", ""),
                exports = exports,
                dirs = dirs,
                services = svcs,
                sdcardPath = o.optString("sdcardPath", "/mnt/sdcard"),
                volumes = o.optJSONArray("volumes")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                settings = VmSettings.fromJson(o.optJSONObject("settings")),
                createdAt = o.optLong("createdAt", 0),
                sizeBytes = o.optLong("sizeBytes", 0),
                sourceName = o.optString("sourceName", ""),
                runtime = o.optString("runtime", "dalvik"),
                warnings = warn,
                lastBootMs = o.optLong("lastBootMs", 0),
                bootCount = o.optInt("bootCount", 0),
                profileVersion = o.optInt("profileVersion", 0),
                baseId = o.optString("baseId", ""),
                baseband = o.optString("baseband", ""),
            )
        }
    }
}

/**
 * A stable per-image serial number derived from the image id: the same image always reports the
 * same serial, and two images never share one. The shape (ten upper-case alphanumerics) follows the
 * common retail convention; exact per-model formats are to be calibrated against real getprop
 * samples later (detection map §5.3) — what matters now is that no two guests answer alike.
 */
fun deriveSerialNo(id: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
    val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    return buildString(10) { for (i in 0 until 10) append(alphabet[(digest[i].toInt() and 0xff) % alphabet.length]) }
}
