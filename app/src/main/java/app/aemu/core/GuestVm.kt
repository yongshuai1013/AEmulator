package app.aemu.core

import android.content.Context
import android.os.Process as AProcess
import android.os.SystemClock
import android.view.Surface
import app.aemu.importer.BootImage
import dev.lk.dhd.BinderSlot
import dev.lk.dhd.GlSlot
import dev.lk.dhd.Slot
import dev.lk.m7sense.GlBridge
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Daemons system_server cannot live without: restarted even when init.rc marks them oneshot
 * (MIUI installd dies in the LBE security hook on start). */
private val ALWAYS_RESTART = setOf("mediaserver", "installd", "netd", "keystore")

/** Measurement build: always trace netd's guest syscalls (QEMU_STRACE, see NetdTrace). The field
 * netd needs ~140 s of continuous life before it accepts; this finds the call it parks in. */
private val NETD_STRACE = true

/** Measurement build: also trace the zygote of translated (KK) guests — its translator carries
 * system_server and every app process, so this is the only seat from which the contacts
 * provider's own file calls (and its database open failing) can be seen; installd is traced too,
 * for its /data/user/0 access (see SyscallPathTrace). The run/ markers live in the app sandbox,
 * which the field phone cannot write, so — exactly as with NETD_STRACE — the measurement build
 * turns this on by constant; run/strace.acore and run/strace.installd stay the switches in a
 * build without it. */
private val ACORE_STRACE = true

/** Native arm64 engine: vendor daemons that only poll hardware the phone does not have (modem, DSP, TrustZone). */
private val N64_SKIP = setOf("audiod", "ATFWD-daemon", "imsqmidaemon", "loc_launcher", "cnd", "dpmd", "cnss-daemon",
    "tftp_server", "qcom-system-daemon", "hvdcp_opti", "pm-service", "mtd", "mlipayd", "vsimd", "fdpp", "perfd", "ipacm",
    "qti", "netmgrd", "port-bridge", "ims_rtp_daemon", "imsdatadaemon", "wcnss_service", "msm_irqbalance", "energy-awareness", "sscrpcd", "adsprpcd", "cdsprpcd", "audioadsprpcd")

/**
 * Одна запущенная виртуальная машина. Живёт в процессе :vm (одна на процесс: GL-мост нельзя
 * поднять дважды), заменяет собой init: готовит дерево, поднимает службы хоста (свойства, ввод,
 * звук, заглушки радио и накопителя), binderd, GL-мост и службы гостя в порядке загрузки Android.
 */
class GuestVm(val ctx: Context, val img: GuestImage) {
    enum class State { STOPPED, PREPARING, BOOTING, RUNNING, FAILED, STOPPING }

    val paths = VmPaths(ctx, img.id)
    val kernel: Kernel64 get() = Kernel64.forApi(img.api)
    val settings get() = img.settings
    val engine get() = img.engine

    /** emulated Wi-Fi (WifiEmu) can serve this image: engine and API in range, and not a recovery boot */
    private val wifiSupported get() = WifiEmu.supported(engine, img.api, recoveryMode, false)
    /** the guest Wi-Fi service is up for this run: its network replaces the Ethernet stand-in */
    @Volatile private var wifiOn = false

    /** host Bluetooth bridge (AEmulator-Plus #18) can serve this image: the 4.4 engine, API 9–25, or a
     * 64-bit guest at API 23–27 (per-generation jars from guest-bt-n64), not a recovery boot */
    val bluetoothSupported get() =
        (engine == Engine.KK && img.api in 9..25 || engine == Engine.N64 && img.api in 23..27) && !recoveryMode
    private val bluetooth = BluetoothBridge(ctx, paths, { settings.bluetooth && bluetoothSupported }, ::log)
    private val agentSupported get() = AgentSupport.offered(paths.root, engine, img.api, recoveryMode)
    private val agent = AgentBridge(paths, { settings.agent && AgentSupport.supported(engine, img.api, recoveryMode) },
        { pkg, on -> agentDeny(pkg, on) }, ::log)
    @Volatile private var agentOn = false
    @Volatile private var substOn = false

    @Volatile var state = State.STOPPED
        private set
    @Volatile var bootAt = 0L
        private set
    @Volatile var bootDoneAt = 0L
    // the boot animation has gone: the lock screen or the home screen is up (7.0+ reports boot_completed only after the user unlocks)
    @Volatile var screenUpAt = 0L
    /** the system reached the home screen at least once in this run: later crashes restart it instead of failing */
    @Volatile private var everBooted = false
    /** Times of the zygote crashes: a system that keeps dying is stopped instead of being restarted forever. */
    private val zygoteCrashes = ArrayDeque<Long>()

    /**
     * The services to start, read from the firmware's init.rc at every boot: the list stored at import was made by the
     * rules of the version that imported it, and fixes to those rules must reach firmware imported earlier.
     */
    private fun servicePlan(): List<GuestService> = runCatching {
        val root = paths.root
        val props = HashMap<String, String>()
        for (n in listOf("default.prop", "system/build.prop")) runCatching {
            File(root, n).forEachLine { l -> val i = l.indexOf('='); if (i > 0 && !l.startsWith("#")) props[l.substring(0, i).trim()] = l.substring(i + 1).trim() }
        }
        val rcFiles = (root.listFiles()?.filter { it.isFile && it.name.endsWith(".rc") }?.sortedBy { if (it.name == "init.rc") 0 else 1 } ?: emptyList()) +
            InitPlan.hwRcFiles(root, props) +
            listOf("system/etc/init", "vendor/etc/init").flatMap { d -> File(root, d).listFiles()?.filter { it.name.endsWith(".rc") }?.sortedBy { it.name } ?: emptyList() } +
            // 11+: a module's services are declared inside the module (the software codec service)
            (File(root, "apex").listFiles()?.sortedBy { it.name }?.flatMap { m -> File(m, "etc").listFiles()?.filter { it.isFile && it.name.endsWith(".rc") }?.sortedBy { it.name } ?: emptyList() } ?: emptyList())
        InitPlan.plan(InitPlan.parse(rcFiles, props), img.api, root).takeIf { p -> p.any { it.name == "zygote" } }
    }.getOrNull() ?: img.services.ifEmpty { InitPlan.fallback(img, paths.root) }
    private var zygoteRestarts = 0
        private set
    @Volatile var failure: String? = null
        private set
    @Volatile var surface: Surface? = null
        set(v) {
            field = v
            // (the open bridge's host renderer, GlHost, shows the frames: ARM guests of 2.0+ on both 32-bit engines, as on the
            // native engine; ported from Plus .17)
            if (openBridge) GlHost.surface(v)
            else if (engine == Engine.KK) GlBridge.surface(v) else if (engine == Engine.N64 && kernel.gpuBridge64) GlHost.surface(v)
        }

    /** Ported from Plus .17 (2026-11-25): this guest draws through the open GPU bridge (GlHost) and not through the engine's glserverd, see [OpenGl] */
    private val openBridge: Boolean get() = OpenGl.any(ctx, img, paths.dir)

    private val lines = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val logFile = File(paths.bin, "aemu.log")

    fun log(s: String) {
        val line = "${stamp()} $s"
        lines.add(line)
        while (lines.size > 3000) lines.removeAt(0)
        runCatching {
            if (engine == Engine.N64 && kernel.syncLog) FileOutputStream(logFile, true).use { it.write((line + "\n").toByteArray()); it.fd.sync() }
            else logFile.appendText(line + "\n")
        }
        listeners.forEach { runCatching { it(line) } }
    }
    fun lines(): List<String> = lines.toList()
    /** the real-time guest log stream files (rotated backups first, the live file last) for the panel's export */
    fun logStreamFiles(): List<File> = logStream.filesOldestFirst()
    fun onLog(l: (String) -> Unit) { listeners.add(l) }
    fun onState(l: (State) -> Unit) { stateListeners.add(l) }
    private fun setState(s: State) { state = s; stateListeners.forEach { runCatching { it(s) } } }

    val props = PropService(paths, ::log)
    /** ADB over the LAN, switched on from the VM menu */
    val adb = AdbServer(this)
    val input = InputService(paths, ::log)
    /** guest vibration requests over /dev/aemu_vibrator, played on the phone's motor (AEmulator Plus;
     *  served on the legacy engines only — 64-bit guests vibrate through [sensors]) */
    private val vibration = VibrationBridge(ctx, paths, { settings.vibration }, ::log)
    // 2.x пишет в /dev/eac через AudioHardwareGeneric на 44,1 кГц, HAL 4.x движка — на 48 кГц
    val audio = AudioOut(paths, ::log, if (img.api < 14) 44100 else AudioOut.RATE,
        if (TreeFixer.isMtkAudio(paths.root)) "dev/aemu_pcm" else "dev/eac")
    val ril = RilStub(paths, ::log, img.settings.imei.ifBlank { VmSettings.DEFAULT_IMEI }, img.api, img.baseband,
        img.settings.basebandOverride, img.settings.operatorName, img.settings.operatorNumeric,
        simPresentSetting = img.settings.simPresent, simNumberSetting = img.settings.simNumber,
        simIccidSetting = img.settings.simIccid,
        networkTypeSetting = img.settings.networkType,
        sim = SimCard(
            present = img.settings.simPresent,
            operator = SimCard.cleanOperator(img.settings.operatorName),
            mcc = SimCard.cleanMcc(img.settings.operatorNumeric.filter { it.isDigit() }.take(3)),
            mnc = SimCard.cleanMnc(img.settings.operatorNumeric.filter { it.isDigit() }.drop(3)),
            number = SimCard.cleanNumber(img.settings.simNumber),
            iccid = SimCard.cleanIccid(img.settings.simIccid),
        ))
    val vold = VoldStub(paths, img.sdcardPath, ::log, others = img.volumes, api = img.api)
    var onFrame: (() -> Unit)? = null
    val frames = FrameBell(paths, ::log) { onFrame?.invoke() }
    private val events = EventsSink(paths, ::log)
    private val logd = LogdSink(paths, ::log)
    /** the guest log, copied to run/logcat-stream.log in real time so a crash's death segment survives */
    private val logStream = GuestLogStream(paths, ::log)
    /** one tombstone mirror scan at a time (see [mirrorTombstones]) */
    private val tombstoneMirrorBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    /** notification / battery LED of the guest (lights HAL → /dev/aemu_lights) */
    val lights = Lights(paths.root, ::log)
    /** the phone's accelerometer and vibration motor for the guest */
    val sensors = Sensors(ctx, paths.root, ::log)
    /** the phone's microphone for the guest (audio HAL input → /dev/aemu_mic) */
    val mic = AudioIn(ctx, paths.root, ::log)
    /** the phone's cameras for the guest (camera HAL → /dev/socket/aemu_cam) */
    val camera = CameraFeed(ctx, paths, ::log)
    /** the guest's battery follows the phone's real one (AEmulator Plus): sysfs nodes in the tree,
     *  kept current by a receiver, plus `dumpsys battery set` once the system is up */
    private val battery = HostBatteryBridge(ctx, paths.root, img.api, settings.hostBattery,
        { everBooted && state == State.RUNNING && !stopping && !recoveryMode },
        { zygoteRestarts }, { command ->
            val result = guestRunner.run(listOf("/system/bin/sh", "-c", command), 4_000)
            check(result.first == 0) { "Guest battery update failed: ${result.first}" }
        }, ::log)
    @Volatile private var hostBatteryDefault = settings.hostBattery
    fun setHostBatteryFollowing(enabled: Boolean) { hostBatteryDefault = enabled; battery.setFollowing(enabled) }
    val hostBatteryFollowing get() = battery.following
    @Volatile private var lmk: GuestLmk? = null
    val net = NetProxy(ctx, paths, ::log)
    private val dns = DnsProxy(paths, img.api, img.engine == Engine.N64 && Kernel64.forApi(img.api).dnsLayoutProbe, ::log)
    private val vpnHub = VpnHub(paths, ::log)
    val runner by lazy { GuestRunner(paths, img) }

    private val extraStubs = ArrayList<VoldStub>()
    private val procs = LinkedHashMap<String, Process>()
    private val lazyStarts = HashMap<String, Int>()
    private val pgids = LinkedHashSet<Int>()
    @Volatile private var stopping = false

    // ------------------------------------------------------------------ загрузка

    fun boot() {
        if (state == State.BOOTING || state == State.RUNNING || state == State.PREPARING) return
        stopping = false
        failure = null
        setState(State.PREPARING)
        try {
            if (recoveryMode) doRecovery() else doBoot()
        } catch (t: Throwable) {
            bluetooth.stop()
            agent.stop()
            failure = t.message ?: t.toString()
            log("✖ boot aborted: $failure")
            setState(State.FAILED)
        }
    }

    /** Boot the firmware's recovery (RecoveryImage) instead of Android. */
    @Volatile var recoveryMode = false
    /** Partition seat boot in progress: the ramdisk /init runs the boot, the plan stands down. */
    @Volatile var seatMode = false
    /** recovery framebuffer format for the screen view: 0 RGB565, 1 RGBA/RGBX_8888, 2 BGRA_8888.
     *  Default 1: modern recoveries draw 32bpp, so the geometry is right from the first frame
     *  (at worst R/B swapped until detection below pins the exact variant). */
    @Volatile var recoveryFormat = 1
    /** Host ends of the silent aux input FIFOs (event1-3 in recovery): held open for the
     *  whole recovery session so minui can adopt those nodes, but never written to. */
    private val auxInputFds = mutableListOf<java.io.FileDescriptor>()

    /**
     * The recovery has no init to run load_all_props, so build its property
     * area here, from the tree's own default.prop (+ prop.default if present),
     * before /sbin/recovery starts: getprop then works from the first second
     * of the session, the way it does on hardware. Fresh area every session;
     * the format follows the recovery's own bionic generation (api from its
     * own ro.build.version.sdk), never the system image's area.
     */
    private fun seedRecoveryProps(r: File, areaFile: File) {
        runCatching {
            val merged = LinkedHashMap<String, String>()
            for (name in listOf("prop.default", "default.prop")) {
                val f = File(r, name)
                if (f.isFile) merged.putAll(PropArea.parseProps(f.readText()))
            }
            val api = merged["ro.build.version.sdk"]?.toIntOrNull() ?: 19
            var n = 0
            PropArea.create(areaFile, api).use { a ->
                for ((k, v) in merged) if (a.put(k, v)) n++
            }
            log("recovery: seeded prop area with $n keys (api $api, ${if (api >= 26) "v2" else "v1"})")
        }.onFailure { log("recovery: prop area seed failed: ${it.message}") }
    }

    private fun doRecovery() {
        paths.bin.mkdirs()
        runCatching { logFile.writeText("") }
        log("image \"${img.name}\": recovery mode")
        if (!RecoveryImage.installed(paths)) error("this firmware has no recovery installed")
        log("recovery image: ${recoveryKind()}")
        val is64 = RecoveryImage.isElf64(paths)
        val qemu = paths.nativeBin(if (is64) kernel.runner else Engine.KK.qemu)
        if (!is64 && !(img.settings.native32 && CpuInfo.hasAArch32) && !qemu.canExecute()) error("translator ${Engine.KK.qemu} is not executable")
        killLeftovers()
        HostNative.limitStackSafe()
        TreeFixer(ctx, paths, img, ::log).fixup()   // framebuffer, input node
        val sd = Sdcard.setup(ctx, paths, img, ::log)
        RecoveryImage.prepare(paths, sd, settings, ::log)
        log(if (is64) "recovery shims: preloaded (64-bit)" else "recovery shims: preloaded (32-bit)")
        input.rateHz = settings.touchHz
        input.mtMode = settings.mtMode
        // doBoot sets wide from the engine; recovery has no socket client to infer it from,
        // so pin it to this recovery's own bitness or the FIFO stream keeps the wrong width
        input.wide = is64
        input.serve()
        // recovery's minui opens /dev/input/event* through openat():
        // a FIFO there gets the raw input_event stream straight from InputService
        runCatching {
            val ev = File(RecoveryImage.dir(paths), "dev/input/event0")
            ev.delete()
            android.system.Os.mkfifo(ev.path, "666".toInt(8))
            val fd = android.system.Os.open(ev.path, android.system.OsConstants.O_RDWR, 0)
            input.sink = java.io.FileOutputStream(fd)
            log("recovery input: fifo ready")
            // event1-3 used to be symlinks onto event0's one FIFO: minui adopts all
            // four nodes and the single event stream gets split across four fds, so
            // a tap rarely arrives whole on any one device. Give each aux node its
            // own FIFO instead, held open by the host and never written: the nodes
            // stay present and adoptable, but the stream reaches minui only via event0.
            // A dynamic recovery (64-bit) opens every event node it finds, so it gets
            // the single node only: the aux nodes are removed (leftovers of earlier
            // sessions included) and not recreated; static (32-bit) recoveries keep
            // the aux fifos they have always had.
            auxInputFds.forEach { runCatching { android.system.Os.close(it) } }
            auxInputFds.clear()
            for (alt in listOf("event1", "event2", "event3")) {
                val f = File(RecoveryImage.dir(paths), "dev/input/$alt")
                f.delete()
                if (!is64) runCatching {
                    android.system.Os.mkfifo(f.path, "666".toInt(8))
                    auxInputFds += android.system.Os.open(f.path, android.system.OsConstants.O_RDWR, 0)
                }
            }
            log("recovery input: aux fifos ready")
        }.onFailure { log("recovery: no input channel: ${it.message}") }
        val (w, h) = runCatching { File(paths.root, "dhd.fbgeom").readText().trim().split(Regex("\\s+")).map { it.toInt() } }
            .getOrNull()?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: (settings.width to settings.height)
        val r = RecoveryImage.dir(paths)
        runCatching { File(r, "tmp/recovery.log").delete() }
        // debugging: run/recovery.strace traces the guest's system calls into run/recovery.trace
        val trace = if (File(paths.bin, "recovery.strace").exists()) listOf("-strace", "-D", File(paths.bin, "recovery.trace").absolutePath) else emptyList()
        val cmd = when {
            img.settings.native32 && CpuInfo.hasAArch32 && !is64 -> listOf(File(r, "sbin/recovery").absolutePath)
            is64 -> listOf(qemu.absolutePath, "/sbin/recovery", File(r, "sbin/recovery").absolutePath)
            else -> listOf(qemu.absolutePath, "-L", r.absolutePath, "-0", "/sbin/recovery") + trace + File(r, "sbin/recovery").absolutePath
        }
        val pb = ProcessBuilder(cmd)
            .directory(r).redirectErrorStream(true).redirectOutput(File(paths.bin, "recovery.out"))
        pb.environment().clear()
        val recEnv = mutableMapOf(
            "PATH" to "/sbin:/system/bin", "LD_LIBRARY_PATH" to ".:/sbin", "ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data",
            "EXTERNAL_STORAGE" to "/sdcard", "TZ" to "UTC",
            "AEMU_MOUNTS" to "/sdcard:/data:/system:/cache:/emmc:/external_sd:/usb-otg",
            "DHD_FB_W" to "$w", "DHD_FB_H" to "$h", "DHD_IN_W" to "$w", "DHD_IN_H" to "$h",
            "DHD_INPUT" to paths.inputSock.absolutePath,
        )
        // prepare() has stamped the shim set matching this recovery's bitness into sbin/; preload it either way.
        recEnv["LD_PRELOAD"] = "/sbin/librecshim.so:/sbin/libaemushim.so"
        // A 64-bit recovery runs under the aemu64 launcher (kernel.runner), which dies at once
        // ("AEMU64_ROOT is not set", exit 127) without the environment GuestRunner.env64 hands it
        // for a system boot. Give it the same set, with the paths pointed at the recovery tree.
        if (is64) {
            recEnv["AEMU64_ROOT"] = r.absolutePath
            val propsFile = File(r, "dev/__properties__")
            recEnv["AEMU64_PROPS"] = propsFile.absolutePath
            seedRecoveryProps(r, propsFile)
            val utsRelease = BootImage.bannerRelease(img.settings.kernelBanner)
            if (utsRelease.isNotEmpty()) {
                recEnv["AEMU64_UTS_RELEASE"] = utsRelease
                val utsVersion = BootImage.bannerVersion(img.settings.kernelBanner)
                if (utsVersion.isNotEmpty()) recEnv["AEMU64_UTS_VERSION"] = utsVersion
            }
            recEnv["AEMU64_BINDER"] = paths.binderSock.absolutePath
            if (kernel.treble) {
                recEnv["AEMU64_HWBINDER"] = paths.hwbinderSock.absolutePath
                recEnv["AEMU64_VNDBINDER"] = paths.vndbinderSock.absolutePath
            }
            recEnv["AEMU64_INPUT"] = paths.inputSock.absolutePath
            paths.nativeBin("libqemu_arm.so").takeIf { it.canExecute() }?.let { recEnv["AEMU64_QEMU"] = it.absolutePath }
            sd?.let { recEnv["AEMU64_SDCARD"] = it.absolutePath }
            recEnv["AEMU64_FB"] = "${w}x${h}"
            if (File(paths.bin, "crash.report").exists()) recEnv["AEMU64_CRASH"] = "1"
        }
        pb.environment().putAll(recEnv)
        bootAt = System.currentTimeMillis()
        val p = pb.start()
        synchronized(procs) { procs["recovery"] = p }
        setState(State.RUNNING)
        log("recovery started (${w}x$h)")
        // Pixel format the recovery draws in, from its own log: older minui prints
        // "Pixel format: BGRA_8888"; TWRP 3.7 never prints it — it reports the formats it
        // sets instead ("setting DRM_FORMAT_RGBX8888 and GGL_PIXEL_FORMAT_RGBX_8888", then
        // "setting GGL_PIXEL_FORMAT_RGBA_8888" on the fbdev path) plus the fb0 varinfo dump.
        // Watch until the recovery exits, not just the first seconds: the line can land in the
        // log long after the welcome screen is up, and a missed guess leaves the view decoding
        // 32bpp output with the wrong format (striped, ghosted picture that never heals).
        // The startup capture (recovery.out) is scraped too — whichever shows the line first wins.
        Thread {
            val namedRe = Regex("Pixel format: (BGRA_8888|RGBX_8888|RGBA_8888|RGB_565)" +
                "|GGL_PIXEL_FORMAT_(BGRA_8888|RGBX_8888|RGBA_8888|RGB_565)" +
                "|DRM_FORMAT_(BGRA8888|RGBX8888|RGBA8888|RGB565)")
            val sources = listOf(File(r, "tmp/recovery.log"), File(paths.bin, "recovery.out"))
            var detected: String? = null
            while (detected == null && p.isAlive && !stopping) {
                for (f in sources) {
                    val t = runCatching { f.readText() }.getOrDefault("")
                    if (t.isEmpty()) continue
                    var fmt: String? = null
                    var hitLine: String? = null
                    for (line in t.lineSequence()) {
                        val m = namedRe.find(line) ?: continue
                        val g = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
                        fmt = when (g) {
                            "BGRA8888" -> "BGRA_8888"; "RGBX8888" -> "RGBX_8888"; "RGBA8888" -> "RGBA_8888"; "RGB565" -> "RGB_565"
                            else -> g
                        }
                        hitLine = line
                    }
                    // the fb0 varinfo report alone also fixes the format: 32 bpp with red at
                    // offset 0 draws RGBA, red at offset 16 draws BGRA
                    if (fmt == null && Regex("vi\\.bits_per_pixel\\s*=\\s*32").containsMatchIn(t)) {
                        val red = Regex("vi\\.red\\.offset\\s*=\\s*(\\d+)").find(t)?.groupValues?.get(1)
                        val blue = Regex("vi\\.blue\\.offset\\s*=\\s*(\\d+)").find(t)?.groupValues?.get(1)
                        fmt = when { red == "0" && blue == "16" -> "RGBA_8888"; red == "16" && blue == "0" -> "BGRA_8888"; else -> null }
                        if (fmt != null) hitLine = t.lineSequence().firstOrNull { it.contains("vi.bits_per_pixel") }
                    }
                    if (fmt == null) continue
                    hitLine?.let { log("recovery log | ${it.trim()}") }
                    recoveryFormat = when (fmt) { "BGRA_8888" -> 2; "RGB_565" -> 0; else -> 1 }
                    log("recovery draws $fmt")
                    detected = fmt
                    break
                }
                if (detected == null) Thread.sleep(250)
            }
            if (detected == null) log("recovery format: not detected, view format left at $recoveryFormat")
        }.start()
        // Probe (recovery touch): mirror whatever the recovery itself says about input
        // devices/events into the host log — minui's evdev scan lands in the same two files
        // the format watch above reads. Logging only; each distinct line is mirrored once.
        Thread {
            val keys = listOf("input", "event", "minui", "evdev", "touch")
            val seen = HashSet<String>()
            val sources = listOf(File(r, "tmp/recovery.log"), File(paths.bin, "recovery.out"))
            fun sweep() {
                for (f in sources) {
                    val t = runCatching { f.readText() }.getOrDefault("")
                    for (raw in t.lineSequence()) {
                        val line = raw.trim()
                        if (line.isEmpty()) continue
                        if (keys.none { line.contains(it, ignoreCase = true) }) continue
                        if (seen.add(line)) log("recovery log | $line")
                    }
                }
            }
            while (p.isAlive && !stopping) { sweep(); Thread.sleep(250) }
            sweep()
        }.start()
        // reboot requests of the recovery (librecshim → /dev/aemu_power)
        val power = File(r, "dev/aemu_power")
        Thread {
            while (p.isAlive && !stopping) {
                if (power.length() > 0) {
                    val v = runCatching { power.readText().trim() }.getOrDefault("reboot"); power.writeText("")
                    powerRequest(v); break
                }
                Thread.sleep(500)
            }
        }.start()
        Thread {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (stopping || powerHandled) return@Thread
            log("recovery exited (code $code), booting the system")
            // the recovery's own output sits in recovery.out, which the log export cannot reach: mirror its tail here
            val outLines = runCatching { File(paths.bin, "recovery.out").readLines() }.getOrDefault(emptyList())
            if (outLines.isEmpty()) log("recovery.out | (missing or empty)")
            else outLines.takeLast(60).forEach { log("recovery.out | $it") }
            onPower?.invoke(true, "")
        }.start()
    }

    /** Which recovery is installed, named the way the settings sheet names it. */
    private fun recoveryKind(): String {
        val dir = RecoveryImage.dir(paths)
        val prop = runCatching { File(dir, "default.prop").readText() }.getOrDefault("")
        val twrp = Regex("""ro\.twrp\.version=(\S+)""").find(prop)?.groupValues?.get(1)
        return when {
            twrp != null -> "TWRP $twrp"
            File(dir, "twres").isDirectory -> "TWRP"
            File(dir, "sbin/orangefox.sh").exists() || prop.contains("orangefox", true) -> "OrangeFox"
            prop.contains("cwm", true) || File(dir, "res/images/icon_clockwork.png").exists() -> "ClockworkMod"
            else -> "stock/other"
        }
    }

    private fun doBoot() {
        paths.bin.mkdirs()
        runCatching { logFile.writeText("") }
        log("image \"${img.name}\": ${img.displayVersion}, ${img.skin}, engine ${engine.title}")
        if (!File(paths.root, "system/framework").isDirectory) error("firmware tree missing")
        val qemu = paths.nativeBin(if (engine == Engine.N64) kernel.runner else engine.qemu)
        if (!qemu.canExecute()) error("translator ${qemu.name} is not executable")
        if (engine == Engine.N64) log("· kernel ${kernel.tag} (${kernel.runner})")
        // the guest's SwiftShader (legacy, reads SwiftShader.ini from the process's directory, this one) finds one core
        // on Android and draws every frame in a single thread: MIUI 8 at 1080×1920 flipped pages at 19 fps, 59 with 8.
        // Every N64 kernel ships that build (n64-gl), and the guest's services and apps all run in this directory
        // (run/SwiftShader.off: the old single thread, to compare)
        if (engine == Engine.N64) runCatching {
            if (File(paths.bin, "SwiftShader.off").exists()) File(paths.bin, "SwiftShader.ini").delete()
            else File(paths.bin, "SwiftShader.ini").writeText("[Processor]\nThreadCount=${Runtime.getRuntime().availableProcessors().coerceIn(1, 8)}\n")
        }
        killLeftovers()
        HostNative.limitStackSafe()

        // 1. дерево
        val fixer = TreeFixer(ctx, paths, img, ::log)
        fixer.fixup()
        // partition seat: with the setting on and a boot image kept at import, present the
        // partitions in this boot (by-name devices, the supervisor's mount table) and lay
        // the boot ramdisk over the tree, so the seat branch below can run its /init first
        seatMode = false
        if (settings.bootSeat && engine == Engine.N64 && PartitionSeat.available(paths)) {
            PartitionSeat.plantByName(paths.root, paths, ::log)
            PartitionSeat.arm(paths.root, ::log)
            seatMode = PartitionSeat.overlayBootrd(paths, ::log)
            if (!seatMode) log("seat: the ramdisk overlay failed, booting the service plan (the partitions stay presented)")
        } else {
            if (settings.bootSeat) log("seat: on, but unusable for this image (the native arm64 engine and a boot image kept at import are required), booting the service plan")
            PartitionSeat.disarm(paths.root)
        }
        // политика звука уже подменялась раньше — обновить её файлы (обёртка/AOSP) до текущей версии
        if (File(paths.root, "system/.aemu-parked/system#lib#hw#audio_policy.default.so").isFile) swapAudioPolicy()
        GuestLog.clear(paths.root)
        val sd = Sdcard.setup(ctx, paths, img, ::log)

        // 2. свойства
        val s = settings
        val overrides = LinkedHashMap<String, String>()
        // 2.3: ro.kernel.qemu=1 включает в фреймворке режим эмулятора — ставим его только mediaserver
        // trial flip (per-VM setting qemuZero): boot with 0 instead of 1, so firmware components take
        // their real-device paths. run/props.extra below is applied after this and still wins over
        // both values; mediaserver's private copy forces =1 for itself either way (startServices).
        overrides["ro.kernel.qemu"] = if (engine == Engine.GB || s.qemuZero) "0" else "1"
        overrides["ro.kernel.qemu.gles"] = if (s.gpu && (s.hwui || engine == Engine.GB)) "1" else "0"
        overrides["qemu.gles"] = if (s.gpu) "1" else "0"
        if (engine == Engine.N64) {             // SwiftShader (GLES 2/3 on the CPU) where the engine has it for this release
            // (8.0+ libEGL picks the driver by name: 1 is the emulator's host GPU pipe, 2 is SwiftShader)
            // (there libEGL takes it from /vendor/lib64/egl, and without that directory puts the flag back to 0)
            val ss = if (File(paths.root, kernel.eglLib).isFile) kernel.gles else "0"
            // (32-bit programs read it as 0 through the runner: they only have the GLES 1 renderer)
            overrides["ro.kernel.qemu.gles"] = ss; overrides["qemu.gles"] = ss
        }
        var glBridge = false
        var glInfo = ""
        if (engine == Engine.N64 && kernel.gpuBridge64 && s.gpu && File(paths.root, "${kernel.eglDir}/libGLES_aemu.so").isFile) {
            val r = GlHost.start(paths.socket("aemu_gl").absolutePath)
            log("GPU bridge: $r")
            glBridge = r.startsWith("ok"); glInfo = r
        }
        // 32-bit engine, test switch "glopen" (TreeFixer put the open bridge in front of the engine's own): programs'
        // contexts go to the same host renderer as on the 64-bit engine; the file's text is the mode (default 2: here the
        // "software" driver under the bridge is the engine's own, which has no texture EGLImages, and WebView's
        // threads share their tiles through those - in mode 1 they stay on it and a page draws only its background)
        if (engine == Engine.KK && s.gpu && File(paths.dir, "glopen").isFile && File(paths.root, "system/lib/egl/libGLES_split.so").isFile) {
            paths.socket("aemu_gl").parentFile?.mkdirs()
            val r = GlHost.start(paths.socket("aemu_gl").absolutePath)
            log("open GPU bridge (32-bit): $r")
            if (r.startsWith("ok")) overrides["debug.aemu.gl"] = File(paths.dir, "glopen").readText().trim().takeIf { it.isNotEmpty() } ?: "2"
        }
        // Ported from Plus .17 (2026-11-20/25): the open bridge (TreeFixer.eglConfig planted the armv7 driver as the guest's whole
        // EGL, "solo" mode): every context, SurfaceFlinger's too, goes to the host renderer. (Not ported with it: GlHost.identity -
        // this tree's GlHost has no GPU rename - and the debug.aemu.fbpost override, which belongs to the hwc1fb composer this
        // tree does not ship.)
        if (s.gpu && openBridge && File(paths.root, OpenGl.driverPath(img.api)).isFile) {
            paths.socket("aemu_gl").parentFile?.mkdirs()
            val r = GlHost.start(paths.socket("aemu_gl").absolutePath)
            log("GPU bridge (open bridge): $r")
            glBridge = r.startsWith("ok"); glInfo = r
            if (!glBridge) log("GPU bridge: the host renderer did not start; the guest will find no GL")
        }
        // 7.0+ libEGL ignores egl.cfg and takes the first libGLES_*.so it finds; without the bridge: the software one
        if (img.api >= 24 && !s.gpu) {
            overrides["ro.hardware.egl"] = "android"
        }
        // ro.boot.hardware comes from the boot loader on a phone (androidboot.hardware); ColorOS tells Qualcomm from
        // MediaTek by it (OplusTelephonyManager: starts with "qcom" or "mt"), and with neither its Telecom has no SoC
        // side at all: system_server dies in CallAudioManager. Named as the firmware's own init.<hardware>.rc is.
        if (engine == Engine.N64 && kernel.bootHardwareProp) {
            val hw = File(paths.root, "vendor/etc/init/hw").list().orEmpty().mapNotNull { Regex("^init[.](qcom|mt[0-9]+[a-z]*)[.]rc$").find(it)?.groupValues?.get(1) }.firstOrNull()
            if (hw != null) overrides["ro.boot.hardware"] = hw
        }
        // ro.hardware from the settings (blank = the firmware's own value); the guest picks HAL libraries by it
        VmSettings.cleanHardware(s.hardware).takeIf { it.isNotEmpty() }?.let {
            overrides["ro.hardware"] = it
            log("props: ro.hardware=$it (from settings)")
        }
        // The verified-boot state a locked retail phone reports. Our guests have no boot loader, so
        // without this the whole family reads empty — which no real phone ever does. The constants
        // live here, in the per-boot overrides, so images imported earlier are covered too: their
        // templates still carry the old defaults (ro.secure=0, ro.bootmode=unknown, …).
        overrides["ro.boot.flash.locked"] = "1"
        overrides["ro.boot.verifiedbootstate"] = "green"
        overrides["ro.boot.veritymode"] = "enforcing"
        overrides["ro.bootmode"] = "normal"
        overrides["ro.secure"] = "1"
        overrides["ro.adb.secure"] = "1"
        // the AVB device state exists only from Android 8.0 on; on 6.0 its absence is the correct answer
        if (img.api >= 26) overrides["ro.boot.vbmeta.device_state"] = "locked"
        // a Knox property: only Samsung firmware carries it
        if (img.brand.equals("samsung", ignoreCase = true)) overrides["ro.boot.warranty_bit"] = "0"
        // the boot loader's version string, derived at import from the factory package (blank = the
        // template's answer stays)
        if (s.bootloaderName.isNotEmpty()) overrides["ro.bootloader"] = s.bootloaderName
        // a serial per image, never a shared constant: the settings value, or the same derivation
        // from the image id for images imported before the field existed
        overrides["ro.serialno"] = s.serialNo.ifBlank { deriveSerialNo(img.id) }
        // storage technology the guest sees (AEmulator Plus): the boot-device properties follow the setting
        StorageSpoof.props(StorageType.parse(s.storageType)).forEach { (k, v) -> overrides[k] = v }
        // the SoC the guest reports (AEmulator Plus .17): a chosen preset's properties join the
        // overrides — ro.board.platform, ro.hardware.chipname and, from Android 12, ro.soc.model /
        // ro.soc.manufacturer, from the same record as /proc/cpuinfo and /sys/devices/soc0 (TreeFixer).
        // With the soc setting blank there is no preset and nothing here changes.
        fixer.socPreset?.let { r ->
            r.props.forEach { (k, v) -> overrides[k] = v }
            log("props: SoC ${r.label}" + r.props.entries.joinToString("") { ", ${it.key}=${it.value}" })
        }
        overrides["ro.sf.lcd_density"] = s.density.toString()
        overrides["qemu.sf.lcd_density"] = s.density.toString()
        // MIUI 10 forces the density of the phone's own panel from this one (440 on a 540-pixel screen)
        if (listOf("system/build.prop", "vendor/build.prop").any { runCatching { File(paths.root, it).readText() }.getOrDefault("").contains("persist.miui.density_v2") }) overrides["persist.miui.density_v2"] = s.density.toString()
        overrides["ro.aemu.host"] = if (engine == Engine.N64) "native" else "qemu-user"
        if (engine == Engine.N64) {
            // the phone runs arm64 code only: the 32-bit zygote and its apps go through qemu-arm when it is there
            if (n64Has32) {
                overrides["ro.zygote"] = "zygote64_32"
                overrides["ro.product.cpu.abilist"] = "arm64-v8a,armeabi-v7a,armeabi"
                overrides["ro.product.cpu.abilist64"] = "arm64-v8a"
                overrides["ro.product.cpu.abilist32"] = "armeabi-v7a,armeabi"
            } else {
                overrides["ro.zygote"] = "zygote64"
                overrides["ro.product.cpu.abilist"] = "arm64-v8a"
                overrides["ro.product.cpu.abilist64"] = "arm64-v8a"
                overrides["ro.product.cpu.abilist32"] = ""
            }
        }
        overrides["dalvik.vm.execution-mode"] = if (s.jit) "int:jit" else "int:fast"
        // execution-mode is a Dalvik property; ART's zygote turns it into "-Xint:jit" / "-Xint:fast" and still starts
        // with -Xusejit:true, so the JIT switch alone did nothing on an ART guest. ART's own switch is dalvik.vm.usejit
        // (ported from AEmulator Plus, GuestVm.kt).
        if (!s.jit && img.api >= 21) overrides["dalvik.vm.usejit"] = "false"
        if (s.lowRam || s.ramMb in 1..768) overrides["ro.config.low_ram"] = "true"
        if (s.ramMb > 0) {
            // Dalvik heap sized from the guest RAM budget (like a real device of that class)
            val heap = (s.ramMb / 4).coerceIn(32, 512)
            overrides["dalvik.vm.heapsize"] = "${heap}m"
            overrides["dalvik.vm.heapgrowthlimit"] = "${(heap / 2).coerceAtLeast(24)}m"
        }
        if (!s.radio) overrides["ro.radio.noril"] = "true"
        if (!s.hwui) overrides["debug.hwui.renderer"] = "skia"
        // the 64-bit engine has GLES only (bridge or SwiftShader), no Vulkan device: hwui of firmwares that default to
        // Vulkan (HyperOS 2, MediaTek) aborts "Assertion failed: !gpuCount" in system_server's RenderThread
        else if (engine == Engine.N64) overrides["debug.hwui.renderer"] = "skiagl"
        // PBO для текстур шрифтов (hwui 4.4+ на GLES3) ломают часть драйверов (Mali) — грузим текстуры напрямую
        if (HostInfo.gpu().contains("mali")) overrides["ro.hwui.use_gpu_pixel_buffers"] = "false"
        if (img.api in 19..20) overrides["persist.sys.dalvik.vm.lib"] = "libdvm.so"
        // зигота 4.4+ заранее открывает EGL; дети после fork наследуют соединение моста, и гостевая
        // библиотека моста уходит в бесконечную рекурсию (падение по стеку в каждом приложении)
        if (img.api >= 19) overrides["ro.zygote.disable_gl_preload"] = "1"
        if (engine == Engine.N64) overrides.putAll(kernel.props)
        if (glBridge) {
            overrides["ro.hardware.egl"] = "aemu"
            if (kernel.bridgeAsEmulation) {
                overrides["ro.kernel.qemu.gles"] = "1"; overrides["qemu.gles"] = "1"
                File(paths.root, "${kernel.eglDir}/libGLES_aemu.so").copyTo(File(paths.root, "${kernel.emulationDir ?: kernel.eglDir}/libGLES_emulation.so"), overwrite = true)
            }
            // which contexts go to the GPU (native/glbridge/guest.c, policy): 3 - everything, the compositor
            // included; a file "glmode" in the image's directory sets another for a test (0: none, 1: programs' own
            // contexts, 2: the window toolkit's as well)
            overrides["debug.aemu.gl"] = File(paths.dir, "glmode").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: kernel.gpuBridgeMode
            // GLES 3.0, or 3.1 when the phone's contexts have it (the renderer says "ES 3.1 to the guest")
            overrides["ro.opengles.version"] = if (glInfo.contains("ES 3.1 to the guest")) "196609" else "196608"
        }
        // ART first boot compiles every app with dex2oat under qemu (minutes, single-threaded): by default only
        // skip it (verify-none: ART verifies classes lazily when an app runs) and use several threads; "full compilation" in the settings keeps machine code
        if (img.api >= 21) {
            // (the native arm64 engine too: dex2oat is a 32-bit program there and runs translated; the boot image is
            // still compiled in full, it has its own filter)
            if (!s.fullDexopt) overrides["dalvik.vm.dex2oat-filter"] = "verify-none"
            // 7.0+: PackageManager picks the filter per reason from pm.dexopt.*
            if (img.api >= 24 && !s.fullDexopt && engine != Engine.N64) for (r in listOf("first-boot", "boot", "install", "bg-dexopt", "ab-ota", "core-app", "forced-dexopt", "nsys-library"))
                overrides["pm.dexopt.$r"] = "verify-none"
            overrides["dalvik.vm.dex2oat-flags"] = "-j" + Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            // 6.0 relocates the boot image to a random address (patchoat), which fails under qemu: keep it in place
            if (img.api >= 23) {
                overrides["dalvik.vm.extra-opts"] = "-Xnorelocate"
                // installd's dex2oat starts its own runtime: without this it tries patchoat too and fails every app
                overrides["dalvik.vm.dex2oat-flags"] = overrides["dalvik.vm.dex2oat-flags"] + " --runtime-arg -Xnorelocate"
            }
            // The stock 5.x/6.0 installd hands the whole dex2oat-flags property to dex2oat as one argument, which then
            // fails to parse ("-j" value '4 --runtime-arg -Xnorelocate'): split it in a dex2oat wrapper (ported from
            // AEmulator Plus, Dex2oatArgSplit).
            if (img.api in 21..23 && Dex2oatArgSplit.install(paths.root))
                log("dex2oat: wrapper splits the combined dex2oat-flags argument (API 21-23 installd)")
        }
        // порты MIUI правят framework на smali так, что Dalvik-верификатор отвергает классы ядра
        // (зигота падает на VerifyError) — на телефонах они живут с выключенной проверкой байткода
        if (img.skin.contains("MIUI", true) && img.runtime == "dalvik") overrides["dalvik.vm.dexopt-flags"] = "v=n,o=a,m=y"
        // Dalvik's first boot runs dexopt over every apk without an odex, one after another and translated (S III: 88
        // apps, 104 s), and verification is most of it; like ART's verify-none above, unless "full compilation" is on
        else if (img.runtime == "dalvik" && !s.fullDexopt) overrides["dalvik.vm.dexopt-flags"] = "v=n,o=a"
        // MediaTek: звук, камера, радио ждут «NVRAM готов» от nvram_daemon, которого у нас нет
        if (TreeFixer.isMtkAudio(paths.root) || File(paths.root, "system/bin/nvram_daemon").isFile) overrides["nvram_init"] = "Ready"
        // TouchWiz 4.1+: TvOutService and Samsung EDS look for HDMI/MHL hardware and keep crashing or blocking boot
        if (img.api >= 16 && (img.skin.contains("TouchWiz", true) || img.skin.contains("One UI", true))) {
            overrides["ro.tvout.enable"] = "false"
            overrides["ro.SecEDS.enable"] = "false"
        }
        // своя панель кнопок у нас — экранную панель гостя (4.x) прячем, чтобы не дублировать
        overrides["qemu.hw.mainkeys"] = if (s.showNavBar && !(engine == Engine.N64 && kernel.navBarRequired)) "1" else "0"
        // Zygisk spike: point the native-bridge property at our bridge (planted in
        // /system/lib64 by TreeFixer) so ART loads it inside zygote; only while the
        // VM's spike switch is on, and before zygote is born - this map is written
        // into the property area by PropService.prepare ahead of every service.
        if (ZygiskSpikeSupport.on(img)) overrides[ZygiskSpikeSupport.BRIDGE_PROP] = ZygiskSpikeSupport.BRIDGE_SONAME
        // отладка: run/props.extra — строки ключ=значение поверх всего остального
        File(paths.bin, "props.extra").takeIf { it.isFile }?.readLines()?.forEach { l ->
            val k = l.substringBefore('=').trim()
            if (k.isNotEmpty() && !k.startsWith("#") && l.contains('=')) overrides[k] = l.substringAfter('=').trim()
        }
        // 12+: package lists and settings stay binary XML (the default). An image once started with this set to false
        // (15.10, briefly) keeps the value in its saved properties, and 14 then cannot read its own text files
        // ("illegal attribute name: :strings" in DevicePolicyManagerService): put back explicitly.
        if (engine == Engine.N64) overrides["persist.sys.binary_xml"] = "true"
        overrides["net.dns1"] = "8.8.8.8"
        overrides["net.dns2"] = "1.1.1.1"
        // в прошивках старая база часовых поясов — передаём текущее смещение, а не название зоны
        overrides["persist.sys.timezone"] = gmtZone()
        // what init reads after /system/build.prop: the default properties of a system-only image (8.0+) and the
        // vendor's own files (on a Treble image the vendor side is the engine's). Only what the image does not say.
        val later = LinkedHashMap<String, String>()
        for (n in listOf("system/etc/prop.default", "vendor/default.prop", "vendor/build.prop")) runCatching {
            File(paths.root, n).takeIf { it.isFile }?.let { f -> PropArea.parseProps(f.readText()).forEach { (k, v) -> later.putIfAbsent(k, v) } }
        }
        // A firmware's own vendor built without VNDK (15+: no ro.vndk.version, the system has the unversioned LLNDK
        // list) must keep it unset - linkerconfig then gives the vendor the current LLNDK set; with the engine's
        // "34" it took VNDK 34's list and vendor libbinder lost libapexsupport.so.
        val vndkLessVendor = File(paths.root, "vendor/build.prop").isFile && !later.containsKey("ro.vndk.version") &&
            File(paths.root, "system/etc/llndk.libraries.txt").isFile
        if (engine == Engine.N64) kernel.propDefaults.forEach { (k, v) ->
            if (!(vndkLessVendor && k == "ro.vndk.version")) later.putIfAbsent(k, v)
        }
        if (engine == Engine.N64 && kernel.derivedIdentity) deriveIdentity(later)
        // Without Treble's own check (it needs SELinux), system_server of 12 compares the partitions' fingerprints
        // and shows "There's an internal problem with your device" when the vendor's is of another build - which it
        // is on every phone updated from an earlier release.
        // init reads the vendor's properties after the system's and a later value wins (but for ro.*): the generic
        // system part of such a firmware says a 36 MB Java heap, the vendor's file has the phone's real sizes
        if (engine == Engine.N64 && kernel.vendorHeapProps) runCatching {
            PropArea.parseProps(File(paths.root, "vendor/build.prop").readText()).forEach { (k, v) ->
                if (k.startsWith("dalvik.vm.heap")) overrides.putIfAbsent(k, v)
            }
        }
        if (engine == Engine.N64 && kernel.mediaProfilesFallback) runCatching {
            val etc = File(paths.root, "vendor/etc")
            // (Qualcomm's libmedia opens _vendor, or the chip's name on bengal: _khaje, _scuba)
            listOf("media_profiles_V1_0.xml", "media_profiles.xml").firstOrNull { File(etc, it).isFile }?.let { have ->
                for (name in listOf("vendor", "khaje", "scuba")) File(etc, "media_profiles_$name.xml").let {
                    if (!it.exists()) runCatching { android.system.Os.symlink(have, it.absolutePath) }
                }
            }
        }
        if (engine == Engine.N64 && kernel.oneFingerprint) runCatching {
            PropArea.parseProps(File(paths.root, "system/build.prop").readText())["ro.system.build.fingerprint"]?.let { fp ->
                overrides["ro.vendor.build.fingerprint"] = fp; overrides["ro.bootimage.build.fingerprint"] = fp
            }
        }
        if (!props.prepare(overrides, later, if (engine == Engine.N64) kernel.propAreaSize else PropArea.SIZE)) error("property area not ready")
        fixer.skipPreBoot(props)
        fixer.noScreenSleep()
        fixer.seedDefaultIme()
        props.onSet = { k, v -> onProp(k, v) }
        props.onCtl = { start, svc -> onCtl(start, svc) }

        // 3. службы хоста
        props.serve()
        input.rateHz = s.touchHz
        input.mtMode = s.mtMode
        input.wide = engine == Engine.N64
        input.serve()
        if (engine != Engine.N64) vibration.serve()   // 64-bit guests get vibration through Sensors
        frames.serve()
        if (s.bluetooth && bluetoothSupported) bluetooth.serve()
        if (s.agent) agent.serve()
        if (s.radio) {
            if (img.api >= 26) TreeFixer(ctx, paths, img, ::log).writeRadioHalConfig { ril.halConfig(it) }
            ril.serve()
        } else log("radio: emulation disabled in settings")
        vold.serve()
        // у vold производителей бывают дополнительные сокеты (Samsung: usbstorage, enc_report) — отвечаем «ладно»
        runCatching {
            // 7.0+ keeps vold (with its cryptd socket) in /system/etc/init
            val rcFiles = listOf(paths.root, File(paths.root, "system/etc/init"))
                .flatMap { d -> d.listFiles()?.filter { it.isFile && it.name.endsWith(".rc") } ?: emptyList() }
            val rc = InitPlan.parse(rcFiles)
            rc.services["vold"]?.sockets?.keys?.filter { it != "vold" }?.forEach { name ->
                VoldStub(paths, img.sdcardPath, ::log, name).also { it.serve(); extraStubs.add(it) }
            }
        }
        // MediaTek: libaudioflinger сам работает с устройством /dev/eac (звуковой драйвер MTK) и читает из
        // него — наш канал звука под тем же именем его вешает, а за ним mediaserver и всю систему.
        // Для MTK /dev/eac ведёт в /dev/null: без звука, но без зависания.
        if (TreeFixer.isMtkAudio(paths.root)) {
            runCatching {
                val eac = audio.fifo
                if (!isLinkTo(eac, "/dev/null")) { eac.delete(); android.system.Os.symlink("/dev/null", eac.absolutePath) }
                // голосовой канал модема (CCCI): без него AudioMTKHardware бесконечно ждёт модем
                for (n in listOf("ccci_pcm_rx", "ccci_pcm_tx")) {
                    val f = File(paths.root, "dev/$n")
                    if (!isLinkTo(f, "/dev/null")) { f.delete(); android.system.Os.symlink("/dev/null", f.absolutePath) }
                }
            }
            // our HAL replaces the MTK one (TreeFixer) and plays through its own channel
            audio.makeFifo(); audio.start()
            log("audio: MediaTek, emulator HAL via /dev/aemu_pcm")
        } else { audio.makeFifo(); audio.start() }
        events.start()
        lights.start()
        sensors.start()
        mic.start()
        camera.start()
        battery.start()
        // the storage size the guest is told (AEmulator Plus): the size node the shim reads; 64-bit engine only
        if (engine == Engine.N64) StorageSpoof.writeNode(paths.root, s.storageGb)
        if (img.api >= 21) logd.start()
        logStream.start()
        if (img.api >= 14) runCatching { dns.ensure() }
        if (img.engine == Engine.N64 && img.api == 23) runCatching { vpnHub.ensure() }
        // the RAM the guest is told it has (its /proc/meminfo, scaled by the kernel): the framework sizes its background by it
        if (engine == Engine.N64) runCatching {
            val f = File(paths.root, "dev/.aemu-ram")
            if (kernel.ramBudget) f.writeText((GuestRunner.guestRamMb(img.api, s.ramMb) * 1024L).toString()) else f.delete()
        }
        lmk = GuestLmk(paths.root, s.lowRam, ::log, s.ramMb).also { it.start() }
        val netCfg = if (s.netProxy) net.start() else GuestRunner.NetConfig()
        runner.sdcardHost = sd
        val r = GuestRunner(paths, img, netCfg.copy(glPath = if (s.gpu) "/dev/socket/gl" else null)).also {
            it.sdcardHost = sd
            // отладка: файл run/binder.verbose включает полную трассировку binder в binder-warn.log
            it.binderVerbose = File(paths.bin, "binder.verbose").exists()
            it.userQemuArgs = s.qemuArgs
        }
        runnerRef = r
        Keeper.hold(ctx, img.name)

        bootAt = System.currentTimeMillis()
        bootDoneAt = 0
        screenUpAt = 0
        everBooted = false; zygoteRestarts = 0; zygoteCrashes.clear()
        setState(State.BOOTING)

        // 4. binder
        paths.binderSock.delete()
        paths.hwbinderSock.delete()
        paths.vndbinderSock.delete()
        paths.creds.let { d -> d.listFiles()?.forEach { it.delete() }; d.mkdirs() }
        startBinder()
        if (!waitFor("binder socket", 15_000) { paths.binderSock.exists() }) error("binderd failed to start")
        if (engine == Engine.N64 && kernel.treble) {
            waitFor("hwbinder socket", 5_000) { paths.hwbinderSock.exists() }
            waitFor("vndbinder socket", 5_000) { paths.vndbinderSock.exists() }
        }

        // 11+: the linker takes its library namespaces (which APEX module gives which library to whom) from
        // /linkerconfig, written at every boot by the firmware's own generator; init runs it before any service
        if (engine == Engine.N64 && File(paths.root, kernel.linkerConfigTool).isFile) runCatching {
            apexInfoList()
            File(paths.root, "linkerconfig").mkdirs()
            // (a generator that writes the one-namespace configuration of a device before Treble for
            // ro.treble.enabled=false - MIUI 12.5's does, and no vendor program finds its VNDK - reads "true" here)
            val own = if (kernel.trebleLinkerConfig) props.privateCopy("linkerconfig", mapOf("ro.treble.enabled" to "true")) else null
            val (code, out) = r.run(listOf("/" + kernel.linkerConfigTool, "--target", "/linkerconfig"), 30_000,
                if (own != null) mapOf("AEMU64_PROPS_FILE" to own.absolutePath) else emptyMap())
            if (code != 0 || !File(paths.root, "linkerconfig/ld.config.txt").isFile) log("linkerconfig: exit $code ${out.trim().let { if (it.length > 700) it.take(400) + " ... " + it.takeLast(300) else it }}")
        }

        r.derivedExports = emptyMap()
        if (engine == Engine.N64 && File(paths.root, "apex/com.android.sdkext/bin/derive_classpath").isFile) runCatching {
            val out = File(paths.root, "data/system/environ/classpath")
            out.parentFile?.mkdirs(); out.delete()
            val (code, msg) = r.run(listOf("/apex/com.android.sdkext/bin/derive_classpath", "/data/system/environ/classpath"), 30_000)
            if (code != 0 || !out.isFile) log("derive_classpath: exit $code ${msg.trim().takeLast(300)}")
            else r.derivedExports = out.readLines().mapNotNull { l -> l.trim().split(' ', limit = 3).takeIf { it.size == 3 && it[0] == "export" }?.let { it[1] to it[2] } }.toMap()
        }

        // 5. службы гостя по плану из init.rc прошивки
        val plan = servicePlan()
        // emulated Wi-Fi: the settings file the guest service reads, written (or, when off, removed)
        // before anything starts; the phone's own connection is mirrored into it when there is one
        val wifi = settings.wifi && wifiSupported && File(paths.root, WifiEmu.JAR_PATH).isFile
        if (settings.wifi && wifiSupported && !wifi) log("wifi: ${WifiEmu.JAR_NAME} is not installed (build guest-wifi/build.sh), the guest keeps its normal network")
        val mirror = if (wifi && settings.wifiMirrorHost) HostWifi.current(ctx) else null
        WifiEmu.write(paths.root, settings, wifi, mirror)
        wifiOn = wifi
        if (wifi) log("wifi: emulated network \"${mirror?.ssid ?: settings.wifiSsid}\" (${if (WifiEmu.secured(settings)) "WPA2" else "open"})" + (if (mirror != null) ", mirroring the phone's connection" else ""))
        // the guest agent: its settings file and the fallback uid set for the per-app views, before
        // anything starts (the agent service itself starts with zygote, below)
        if (AgentSupport.supported(engine, img.api, recoveryMode)) {
            if (settings.agent && !File(paths.root, AgentSupport.JAR_PATH).isFile)
                log("agent: ${AgentSupport.JAR_NAME} is not installed (build guest-agent/build.sh), the agent stays off")
            agentOn = settings.agent && agentSupported
            AgentSupport.write(paths.root, settings, ::log)
        }
        // the Magisk substitute gets its own boot seat here, before the service plan starts:
        // the payload tmp is prepared and the post-fs-data stage runs (the daemon itself is
        // woken by that first root client, never exec'd by the host). Every host-log line of
        // this stage carries the magisk-sub: prefix, distinct from any real Magisk boot.
        substOn = SubstituteSupport.on(img) && !seatMode
        if (substOn) substPostFsData()
        // partition seat, layer 2: the ramdisk /init is the boot; the plan, the substitute
        // and the ctl proxy stand down (onCtl checks seatMode), the host keeps the session
        // furniture (the binder core above, screen, input, power via the watchdog)
        if (seatMode) { startSeatInit(r); return }
        var glDone = false
        // (debugging, see Kernel64.syncLog: only the first N services are started, then the guest is left as it is)
        val stopAfter = if (engine == Engine.N64 && kernel.syncLog) runCatching { File(paths.root, "dev/.aemu-stop-after").readText().trim().toInt() }.getOrNull() else null
        if (stopAfter != null) log("stop-after $stopAfter of: " + plan.filter { !it.onDemand }.mapIndexed { i, s -> "$i ${s.name}" }.joinToString())
        var started = 0
        for (svc in plan) {
            if (stopping) return
            if (svc.onDemand) { props.put("init.svc.${svc.name}", "stopped"); continue }
            if (stopAfter != null && started++ >= stopAfter) {
                log("stop-after $stopAfter: holding before ${svc.name}")
                Thread.sleep(60_000)
                log("stop-after $stopAfter: 60 s passed, alive: ${alive().joinToString()}")
                return
            }
            // GL-мост должен ждать гостя до SurfaceFlinger/zygote
            if (!glDone && (svc.name == "surfaceflinger" || svc.name == "zygote" || svc.name == "bootanim")) {
                glUp(); glDone = true
            }
            // 10+: the package manager requires apexd's binder service, which is there once apexd reports "ready"
            if (svc.name == "zygote" && engine == Engine.N64 && plan.any { it.name == "apexd" })
                waitFor("apexd", 15_000) { props.get("apexd.status") == "ready" }
            // 4.2+: заглушка bluetooth_manager до зиготы (system_server с ro.kernel.qemu=1 свою не поднимает)
            if (svc.name == "zygote" && img.api >= 9 && engine != Engine.GB && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                // 2.3–4.1 look up "bluetooth" instead; without it BluetoothAdapter is null and the vendor's Bluetooth apps crash
                val btName = if (img.api >= 17) "bluetooth_manager" else "bluetooth"
                // Android 4.4 (API 19) with the host bridge on: the real bridge client from aemu-bt.jar;
                // the 64-bit generations take their per-generation jar from guest-bt-n64 (API 23, 24-25, 26-27);
                // every other case keeps the placeholder above (AEmulator-Plus #18)
                val btJarName = when {
                    img.api == 19 -> "aemu-bt.jar"
                    engine == Engine.N64 && img.api == 23 -> "aemu-bt23.jar"
                    engine == Engine.N64 && img.api in 24..25 -> "aemu-bt24.jar"
                    engine == Engine.N64 && img.api in 26..27 -> "aemu-bt26.jar"
                    else -> null
                }
                val btJar = btJarName?.let { File(paths.root, "system/framework/$it") }
                val bridgeJar = settings.bluetooth && bluetoothSupported && btJar != null && btJar.isFile
                if (settings.bluetooth && bluetoothSupported && btJarName != null && btJar != null && !btJar.isFile)
                    log("bluetooth: $btJarName is not installed (build guest-bt/build.sh or guest-bt-n64/build.sh), the guest keeps the placeholder")
                synchronized(procs) { procs.remove("aemu-bt") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-bt", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/${if (bridgeJar) btJarName else "aemu-stubs.jar"}", "/system/bin",
                    if (bridgeJar) "app.aemu.stub.BtService" else "app.aemu.stub.BtStub", btName), optional = true))
            }
            // (well before zygote: apexd of 10+ looks for vold first and only then registers its own service, which the
            // package manager requires)
            if (svc.name == "surfaceflinger" && engine == Engine.N64 && kernel.binderVold && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                synchronized(procs) { procs.remove("aemu-vold") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-vold", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.VoldStub") + (if (kernel.volumeAtUserStart) listOf("userstart") else emptyList()), optional = true))
            }
            // emulated Wi-Fi: the guest service answers WifiManager as a connected access point and, on 5.0+,
            // registers the Wi-Fi network itself (instead of the Ethernet stand-in below)
            if (svc.name == "zygote" && wifiOn) {
                synchronized(procs) { procs.remove("aemu-wifi") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-wifi", listOf("/system/bin/app_process",
                    "-Djava.class.path=/${WifiEmu.JAR_PATH}", "/system/bin", "app.aemu.stub.WifiStub"), optional = true))
            }
            // the guest agent (the hide list's identity sync, the import inbox): with zygote, since it
            // needs the package manager; its lookups retry until system_server answers
            if (svc.name == "zygote" && agentOn) {
                synchronized(procs) { procs.remove("aemu-agent") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-agent", listOf("/system/bin/app_process",
                    "-Djava.class.path=/${AgentSupport.JAR_PATH}", "/system/bin", AgentSupport.MAIN_CLASS), optional = true))
            }
            // 5.0–7.x: a registered network, otherwise ConnectivityService says "no active network" and browsers stay offline
            if (svc.name == "zygote" && !wifiOn && (if (engine == Engine.N64) kernel.registeredNetwork else img.api in 21..25) && engine != Engine.GB && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                synchronized(procs) { procs.remove("aemu-net") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-net", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.NetStub") + (if (Kernel64.forApi(img.api).ethernetStandIn) listOf("ethernet") else emptyList()), optional = true))
            }
            if (svc.name == "zygote" && engine == Engine.N64 && kernel.apexStandIn && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                apexStandInList()
                synchronized(procs) { procs.remove("aemu-apex") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-apex", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.ApexStub"), optional = true))
            }
            // services the firmware's programs call unchecked and nothing here registers
            if (svc.name == "zygote" && engine == Engine.N64 && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                val names = Kernel64.forApi(img.api).standInServices.filter { (name, user) ->
                    runCatching { String(File(paths.root, user.substringBefore('#')).readBytes(), Charsets.ISO_8859_1).contains(user.substringAfter('#', name)) }.getOrDefault(false) }.keys
                synchronized(procs) { procs.remove("aemu-standin") }?.let { runCatching { it.destroyForcibly() } }
                if (names.isNotEmpty()) startService(GuestService("aemu-standin", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.StandIn") + names, optional = true))
            }
            startService(svc)
            svc.waitSocket?.let { sock -> waitFor("socket $sock", 20_000) { paths.socket(sock).exists() } }
            // The socket file only proves that dhdrun bound it. Do not start zygote (and with it system_server's NetdConnector)
            // until netd itself answers: see NetdProbe and ensureNetdServing
            if (svc.name == "netd" && svc.waitSocket == "netd" && img.api < 29) ensureNetdServing()
            if (svc.delayMs > 0) Thread.sleep(svc.delayMs)
        }
        if (!glDone) glUp()
        log("system started: ${alive().joinToString()}")
        if (substOn) substLateStart()
        watchdog()
    }

    /**
     * The seat boot: start the one process the host starts in this mode — the boot image
     * ramdisk's /init, under the supervisor, with the seat flag in its environment. It
     * mounts, sets up and hands over itself; the boot is reported through the same
     * sys.boot_completed property as a plan boot (onProp). If /init dies, the session is
     * over: on a device nothing outlives it either.
     */
    private fun startSeatInit(r: GuestRunner) {
        log("seat: plan suppressed, starting the ramdisk /init as the first guest process")
        glUp()   // the plan loop calls this before SurfaceFlinger/zygote; the seat's init starts them itself, so it is due now
        spawn("init", r.cmdline(listOf("/init")), r.env(mapOf("AEMU64_SEAT" to "1")))
        val p = synchronized(procs) { procs["init"] }
        Thread({
            val code = runCatching { p?.waitFor() }.getOrDefault(-1)
            if (!stopping) {
                failure = "the seat /init exited, code $code"
                log("✖ seat: /init exited, code $code" + if (bootDoneAt == 0L) " (before the boot completed)" else "")
                setState(State.FAILED)
            }
        }, "seat-init-watch").apply { isDaemon = true; start() }
        watchdog()
    }

    @Volatile private var runnerRef: GuestRunner? = null
    val guestRunner: GuestRunner get() = runnerRef ?: runner

    private fun startBinder() {
        val sock = paths.binderSock.absolutePath
        when (engine) {
            Engine.KK -> spawn("binderd", listOf(paths.nativeBin(engine.binderd).absolutePath, "-s", sock), emptyMap())
            Engine.N64 -> {
                val env = if (File(paths.bin, "binder.verbose").exists()) mapOf("AEMU_BINDER_VERBOSE" to "1") else emptyMap()
                val bin = paths.nativeBin(kernel.binderd).absolutePath
                spawn("binderd", listOf(bin, sock), env)
                if (kernel.treble) {
                    spawn("hwbinderd", listOf(bin, paths.hwbinderSock.absolutePath), env)
                    spawn("vndbinderd", listOf(bin, paths.vndbinderSock.absolutePath), env)
                    log("· Treble hwbinder and vndbinder daemons started")
                }
            }
            Engine.GB -> if (File(paths.bin, "binderd.kk").exists()) {
                // эксперимент: более новый binderd движка KK
                spawn("binderd", listOf(paths.nativeBin(Engine.KK.binderd).absolutePath, "-s", sock), emptyMap())
            } else {
                Slot.start(ctx, BinderSlot::class.java, listOf("binderd", "-s", sock), emptyMap(), paths.log("binderd"))
                log("· binderd started (:binder service)")
            }
        }
    }

    private fun glUp() {
        if (!settings.gpu) { log("GPU bridge off, software rendering"); return }
        val (w, h) = if (engine == Engine.GB) 480 to 800 else settings.width to settings.height
        paths.glSock.parentFile?.mkdirs()
        paths.glSock.delete()
        when (engine) {
            Engine.N64 -> {
                log("native arm64: graphics are served by the engine's own HALs")
                // ...except for 32-bit programs, which draw through the 4.4 engine's GPU bridge (its frame file is not shown)
                if (kernel.gpuBridge32 != null && n64Has32) {
                    spawn("glserverd", listOf(paths.nativeBin(Engine.KK.glserverd).absolutePath,
                        "-s", paths.glSock.absolutePath, "-fb", File(paths.bin, "gl32.fb").absolutePath, "-w", "$w", "-h", "$h"), glServerEnv())
                    waitFor("GL bridge socket", 10_000) { paths.glSock.exists() }
                }
            }
            Engine.GB -> {
                if (openBridge) {
                    // Ported from Plus .17 (2026-11-25): the host renderer (GlHost) was started in settings preparation; there is no glserverd
                    // slot and no fb0 writer of its own: frames go into the guest's window buffers and its gralloc posts them to fb0
                    log("GPU bridge: open bridge, served by the host renderer (no glserverd)")
                    return
                }
                Slot.start(ctx, GlSlot::class.java,
                    listOf("glserverd", "-s", paths.glSock.absolutePath, "-fb", paths.fb.absolutePath, "-w", "$w", "-h", "$h"),
                    emptyMap(), paths.log("glserverd"))
                waitFor("GL bridge socket", 10_000) { paths.glSock.exists() }
            }
            Engine.KK -> {
                if (openBridge) {
                    // Ported from Plus .17: the host renderer (GlHost) was started in settings preparation; it needs the screen surface
                    // before the first frame (no wait for the screen surface: the setter above hands it to GlHost whenever it arrives)
                    log("GPU bridge: open bridge, served by the host renderer (no glserverd)")
                    surface?.let { GlHost.surface(it) }
                    return
                }
                if (surface == null) waitFor("screen surface", 10_000) { surface != null }
                val sf = surface
                HostNative.relaxFdsanSafe()
                // 5.0+: hwui's GL stream has crashed the in-app bridge (SIGSEGV in serve_one_raw took the whole app down);
                // the standalone glserverd keeps such a crash inside its own process
                if (img.api < 21 && sf != null && GlBridge.start(sf, paths.glSock.absolutePath, paths.log("glbridge").absolutePath, w, h, false)) {
                    log("★ GPU bridge running in-app: frames render straight to the surface")
                    waitFor("GL bridge socket", 5_000) { paths.glSock.exists() }
                    return
                }
                log(if (img.api >= 21) "GPU bridge: standalone glserverd" else "in-app GPU bridge failed, falling back to standalone glserverd")
                spawn("glserverd", listOf(paths.nativeBin(engine.glserverd).absolutePath,
                    "-s", paths.glSock.absolutePath, "-fb", paths.fb.absolutePath,
                    "-notify", paths.frameSock.absolutePath, "-w", "$w", "-h", "$h"), glServerEnv())
                waitFor("GL bridge socket", 10_000) { paths.glSock.exists() }
            }
        }
    }

    /** the 4.4 engine's GL server reads every GL call of a client with its own system calls; native/glbuf reads the
     *  client sockets in large chunks instead (7.1 GLES 2/3 scenes were bound by those reads) */
    private fun glServerEnv(): Map<String, String> {
        val buf = paths.nativeBin("libglbuf.so")
        return if (buf.isFile) mapOf("LD_PRELOAD" to buf.absolutePath) else emptyMap()
    }

    /** guest files this kernel ships (asset names) */
    private val engineFiles: Set<String> by lazy { listOf("engines/n64-${kernel.tag}", kernel.gsiAssets).flatMap { runCatching { EngineAssets.list(ctx, it) }.getOrNull().orEmpty().asList() }.toSet() }

    private val n64Has32: Boolean get() = paths.nativeBin("libqemu_arm.so").canExecute() &&
            File(paths.root, "system/bin/app_process32").isFile

    val glInApp: Boolean get() = engine == Engine.KK && GlBridge.running()

    private fun startService(svc0: GuestService, propsFile: File? = null) {
        // The second zygote is of the other word size than the first. A firmware that ships all three zygote rc files
        // and does not name one (GSI 10: no ro.zygote) can leave both 64-bit in the plan - then 32-bit apps have no
        // zygote and system_server dies at boot ("Unsupported zygote ABI: armeabi-v7a").
        fun is64(path: String?): Boolean = path != null && runCatching {
            File(paths.root, path.removePrefix("/")).inputStream().use { i -> ByteArray(5).also { i.read(it) }[4].toInt() == 2 }
        }.getOrDefault(false)
        val svc = if (svc0.name == "zygote_secondary" && is64(svc0.argv.firstOrNull()) &&
            is64(img.services.firstOrNull { it.name == "zygote" }?.argv?.firstOrNull()) &&
            File(paths.root, "system/bin/app_process32").isFile)
            svc0.copy(argv = listOf("/system/bin/app_process32") + svc0.argv.drop(1)) else svc0
        if (!File(paths.root, svc.argv.first().removePrefix("/")).isFile) {
            log("· ${svc.name}: ${svc.argv.first()} missing, skipping")
            return
        }
        if (engine == Engine.N64 && ((svc.name == "zygote_secondary" && !n64Has32) || svc.argv.first().substringAfterLast('/') in N64_SKIP)) return
        if (engine == Engine.N64 && !paths.nativeBin("libqemu_arm.so").canExecute() && !runsNatively(File(paths.root, svc.argv.first().removePrefix("/")))) {
            log("· ${svc.name}: 32-bit program, skipped by the native arm64 engine")
            return
        }
        val extra = LinkedHashMap<String, String>()
        for ((sock, mode) in svc.sockets) extra["DHD_SOCK_$sock"] = "${paths.socket(sock).absolutePath},${if (engine == Engine.N64) mode else mode.substringBefore(',')}"
        if (svc.uid != 0) { extra["DHD_UID"] = svc.uid.toString(); extra["DHD_GID"] = svc.gid.toString() }
        var props = propsFile
        // звук: с ro.kernel.qemu=1 mediaserver берёт эмуляторный вывод в /dev/eac
        if (svc.name == "mediaserver") props = this.props.privateCopy("media", mapOf("ro.kernel.qemu" to "1"))
        val argv = if (svc.name == "zygote" && !settings.jit && img.runtime == "dalvik")
            svc.argv.take(1) + "-Xint:fast" + svc.argv.drop(1) else svc.argv
        // отладка: файл run/strace.<служба> включает трассировку системных вызовов qemu
        // measurement build: netd is traced unconditionally (NETD_STRACE), other services keep the marker;
        // ACORE_STRACE does the same for the zygote and installd of translated guests (api 14+)
        if (File(paths.bin, "strace.${svc.name}").exists() || (svc.name == "netd" && NETD_STRACE) ||
            (engine == Engine.KK && img.api >= 14 && ACORE_STRACE && (svc.name == "zygote" || svc.name == "installd")))
            extra["QEMU_STRACE"] = "1"
        if (engine == Engine.N64 && props != null) extra["AEMU64_PROPS_FILE"] = props.absolutePath
        // the engine's own 64-bit HAL service over a vendor's firmware: its libraries are the engine's copy of its VNDK
        if (engine == Engine.N64) kernel.ownVndkDir?.let { dir ->
            val name = svc.argv.first().removePrefix("/").replace('/', '#')
            if (name.startsWith("vendor#bin#") && name !in kernel.firmwareOwn && File(paths.root, dir).isDirectory &&
                name in engineFiles && runsNatively(File(paths.root, svc.argv.first().removePrefix("/")))) extra["AEMU64_LIBPATH"] = "/$dir"
        }
        if (engine == Engine.N64) kernel.ownLibDirs[svc.argv.first().removePrefix("/").replace('/', '#')]?.let { dir ->
            // (several directories: "a:b", searched in that order)
            val dirs = dir.split(':').filter { File(paths.root, it).isDirectory }
            if (dirs.isNotEmpty()) extra["AEMU64_LIBPATH"] = dirs.joinToString(":") { "/$it" }
        }
        spawn(svc.name, guestRunner.cmdline(argv, props), guestRunner.env(extra))
    }

    /** a 64-bit ELF or a script */
    private fun runsNatively(f: File): Boolean = runCatching {
        f.inputStream().use { i ->
            val b = ByteArray(5)
            i.read(b) == 5 && ((b[0] == 0x7f.toByte() && b[4] == 2.toByte()) || b[0] == 0x23.toByte())
        }
    }.getOrDefault(false)

    private fun spawn(name: String, cmd: List<String>, env: Map<String, String>) {
        val log = paths.log(name)
        val pb = ProcessBuilder(cmd).directory(paths.bin).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(if (env.isEmpty()) mapOf("PATH" to "/system/bin") else env)
        // 7.0+ zygote refuses to fork with pipes among its descriptors (it only reopens files and devices):
        // its stdio go straight to the log file and /dev/null, which the guest shim shows as /dev/null
        val direct = name == "zygote" && img.api >= 24
        if (direct) {
            runCatching { log.appendText("\n=== $name ${stamp()} ===\n") }
            pb.redirectInput(File("/dev/null")).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        }
        val p = pb.start()
        synchronized(procs) { procs[name] = p }
        pidOf(p)?.let { pgids.add(it) }
        log("· $name started")
        // netd syscall tracing (measurement build): the pump below stamps every line with the time
        // since this spawn and feeds NetdTrace; its digest lands in this log once the trace settles
        val trace = if (name == "netd" && env["QEMU_STRACE"] == "1" && !direct)
            NetdTrace(SystemClock.uptimeMillis()) else null
        if (trace != null) {
            log("netd-trace: tracing netd syscalls (QEMU_STRACE) into run/netd.log")
            trace.digestThread({ stopping }, { s -> log(s) }).start()
        }
        // acore measurement: the zygote's translator carries system_server and every app process,
        // so tracing it is the only way to see the contacts provider's own file calls; the lines
        // naming its database are digested into this log (SyscallPathTrace). The raw trace lands
        // in run/zygote.log, capped — a traced zygote out-produces a traced daemon by far.
        val acoreTrace = if (name == "zygote" && engine == Engine.KK && env["QEMU_STRACE"] == "1" && !direct)
            SyscallPathTrace("acore-trace", listOf("providers.contacts", "contacts2.db"), true, SystemClock.uptimeMillis()) else null
        if (acoreTrace != null) {
            log("acore-trace: tracing zygote syscalls (QEMU_STRACE) into run/zygote.log; lines naming the contacts database will be mirrored here")
            acoreTrace.digestThread({ stopping }, { s -> log(s) }).start()
        }
        // installd measurement (same seat): its initialize_directories touches /data/user/0 once
        // per incarnation, so the few lines naming it are mirrored as they arrive, not digested
        val installdTrace = if (name == "installd" && engine == Engine.KK && env["QEMU_STRACE"] == "1" && !direct)
            SyscallPathTrace("installd-trace", listOf("/data/user/0"), false, SystemClock.uptimeMillis()) else null
        if (installdTrace != null) {
            log("installd-trace: tracing installd syscalls (QEMU_STRACE) into run/installd.log; lines naming /data/user/0 will be mirrored here")
        }
        // init publishes every service's state; the framework waits on it (SystemService.waitForState: the WebView
        // zygote, the boot animation)
        props.put("init.svc.$name", "running")
        // netd's VPN intervention lines (aemu-nlreq:/aemu-nllink:) are mirrored into this log
        // verbatim as they arrive: they used to reach it only through NetdTrace's one-shot
        // digest, which closes a minute into the boot — long before any VPN session starts
        val nlMirror = name == "netd"
        // the ptrace watcher's lines (aemu-watch:, and its crash blocks, which begin
        // "aemu-crash: watcher") are printed by the zygote's supervisor to this service's own
        // log; mirror them here like netd's, or a field run has to know to export zygote.log
        // before it can tell whether the watcher was on post at all
        val watchMirror = name == "zygote"
        // the supervisor-side 137 measurement lines (aemu-137r:) are printed by EVERY
        // service's supervisor (system_server's and netd's above all); mirror them from
        // all services, or they die in the per-service logs like aemu-tun: did before them
        Thread({
            if (!direct) runCatching {
                OutputStreamWriter(FileOutputStream(log, true)).use { w ->
                    w.write("\n=== $name ${stamp()} ===\n")
                    if (trace == null && acoreTrace == null && installdTrace == null) {
                        p.inputStream.bufferedReader().forEachLine {
                            w.write(it); w.write("\n"); w.flush()
                            if (nlMirror && (it.startsWith("aemu-nlreq:") || it.startsWith("aemu-nllink:"))) log(it)
                            if (watchMirror && (it.startsWith("aemu-watch:") || it.startsWith("aemu-crash: watcher"))) log(it)
                            if (it.startsWith("aemu-137r:")) log(it)
                        }
                    } else {
                        var rawLines = 0
                        var rawCapped = false
                        p.inputStream.bufferedReader().forEachLine { line ->
                            val el = when {
                                trace != null -> trace.record(line)
                                acoreTrace != null -> acoreTrace.record(line)
                                installdTrace != null -> installdTrace.record(line)
                                else -> 0L
                            }
                            if (acoreTrace == null || rawLines < SyscallPathTrace.RAW_FILE_CAP) {
                                w.write(String.format(java.util.Locale.US, "[+%d.%03d] %s\n", el / 1000, el % 1000, line))
                                w.flush()
                                rawLines++
                            } else if (!rawCapped) {
                                rawCapped = true
                                w.write("[acore-trace: raw trace capped at ${SyscallPathTrace.RAW_FILE_CAP} lines in this file; matching lines keep being counted and mirrored to the host log]\n")
                                w.flush()
                            }
                            if (installdTrace != null && installdTrace.isMatch(line)) {
                                log(String.format(java.util.Locale.US, "installd-trace: [+%d.%03d] %s", el / 1000, el % 1000, line.take(300)))
                            }
                            if (nlMirror && (line.startsWith("aemu-nlreq:") || line.startsWith("aemu-nllink:"))) {
                                log(line)
                            }
                            if (watchMirror && (line.startsWith("aemu-watch:") || line.startsWith("aemu-crash: watcher"))) {
                                log(line)
                            }
                            if (line.startsWith("aemu-137r:")) {
                                log(line)
                            }
                        }
                    }
                }
            }
            trace?.exited()
            acoreTrace?.exited()
            installdTrace?.exited()
            installdTrace?.summary { s -> log(s) }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (!stopping) log("· $name exited, code $code")
            // a restarted service is already running again under this name
            synchronized(procs) { if (procs[name].let { it == null || it === p }) props.put("init.svc.$name", "stopped") }
        }, "log-$name").apply { isDaemon = true; start() }
    }

    private fun alive(): List<String> = synchronized(procs) { procs.filterValues { it.isAlive }.keys.toList() }

    private fun waitFor(what: String, ms: Long, cond: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < until && !stopping) {
            if (cond()) return true
            Thread.sleep(100)
        }
        val ok = cond()
        if (!ok) log("⚠ timed out waiting for: $what")
        return ok
    }

    /**
     * Waits for the one netd that was started to answer a command, up to 90 s, and never restarts it: in the field our
     * netd is slow to warm up rather than wedged (a single one answered the framework after ~140 s of continuous life),
     * and killing it only resets that clock — the Plus restart gate (three 15 s attempts, ported 2026-11-24) left all
     * three incarnations silent. Probing stays short and dense (see NetdProbe), so a netd that answers early releases
     * the boot at once.
     *
     * The probe runs on a daemon thread and this loop only waits on its latch. A single LocalSocket.connect() inside
     * the probe is a blocking native call with no timeout of its own, and when one wedges, the probe's deadline —
     * checked only between tries — never gets a turn: run synchronously on this loop, the probe left a boot parked
     * after "netd started" for over 800 s in the field. The latch wait below is sliced and hard-capped here, so the
     * gate releases at 90 s whatever the probe thread is doing. A probe that finishes after the gate has already
     * released logs one late line and touches nothing else.
     */
    private fun ensureNetdServing() {
        val sock = paths.socket("netd")
        val t0 = SystemClock.uptimeMillis()
        val done = CountDownLatch(1)
        val answered = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val probe = Thread({
            val ok = NetdProbe.waitServing(sock, 90_000, stopped = { stopping })
            answered.set(ok)
            done.countDown()
            if (released.get()) {
                log("netd probe (late): ${if (ok) "answered" else "never answered"} (${SystemClock.uptimeMillis() - t0} ms after netd's start); the boot had already gone on")
            }
        }, "netd-probe")
        probe.isDaemon = true
        probe.start()
        val until = t0 + 90_000
        var finished = false
        while (!stopping) {
            val remaining = until - SystemClock.uptimeMillis()
            if (remaining <= 0) break
            try {
                if (done.await(minOf(remaining, 200L), TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
            } catch (_: InterruptedException) {
                finished = done.count == 0L
                break
            }
        }
        if (stopping) return
        if (finished) {
            if (answered.get()) {
                log("netd answers (${SystemClock.uptimeMillis() - t0} ms after its start)")
            } else {
                log("⚠ netd never answered within 90 s (${SystemClock.uptimeMillis() - t0} ms after its start); booting on (ConnectivityService may fail)")
            }
            return
        }
        released.set(true)
        log("⚠ netd probe thread still blocked after 90 s (${SystemClock.uptimeMillis() - t0} ms after netd's start); booting on (ConnectivityService may fail)")
    }

    // ------------------------------------------------------------------ присмотр

    /** Guest asked to reboot / power off ("reboot", "reboot,recovery", "shutdown"); the UI restarts or stops the VM. */
    @Volatile var onPower: ((reboot: Boolean, reason: String) -> Unit)? = null
    @Volatile private var powerHandled = false

    private fun powerRequest(v: String) {
        if (powerHandled || v.isEmpty()) return
        powerHandled = true
        val reboot = v.startsWith("reboot")
        val reason = v.substringAfter(',', "")
        log("guest requested ${if (reboot) "reboot" else "power off"}${if (reason.isNotEmpty()) " ($reason)" else ""}")
        if (reason == "recovery" && !RecoveryImage.installed(paths)) log("no recovery installed, booting the system again")
        onPower?.invoke(reboot, reason)
    }

    /**
     * 12+: the generator of the linker's namespaces takes the active modules from /apex/apex-info-list.xml, which
     * apexd writes when it mounts them; here they are plain directories, so the list is written from their manifests.
     */
    private fun apexInfoList() {
        val apex = File(paths.root, "apex")
        val mods = apex.listFiles()?.filter { it.isDirectory && '@' !in it.name && File(it, "apex_manifest.pb").isFile }?.sortedBy { it.name } ?: return
        if (mods.isEmpty()) return
        val pre = File(paths.root, "system/apex").list() ?: emptyArray()
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<apex-info-list>\n")
        for (m in mods) {
            // apex_manifest.pb: field 1 the name, field 2 the version (a varint)
            val b = File(m, "apex_manifest.pb").readBytes()
            var i = 0; var version = 1L
            fun varint(): Long { var v = 0L; var sh = 0; while (i < b.size) { val c = b[i++].toInt(); v = v or ((c and 0x7f).toLong() shl sh); sh += 7; if (c and 0x80 == 0) break }; return v }
            while (i < b.size) {
                val tag = varint().toInt()
                when (tag and 7) { 0 -> { val v = varint(); if (tag shr 3 == 2) version = v }; 2 -> i += varint().toInt(); 1 -> i += 8; 5 -> i += 4; else -> i = b.size }
            }
            val file = pre.firstOrNull { it == m.name || it.substringBeforeLast('.') == m.name || it.startsWith(m.name + ".") } ?: m.name
            sb.append("  <apex-info moduleName=\"${m.name}\" modulePath=\"/system/apex/$file\" preinstalledModulePath=\"/system/apex/$file\" versionCode=\"$version\" versionName=\"\" isFactory=\"true\" isActive=\"true\" />\n")
        }
        sb.append("</apex-info-list>\n")
        File(apex, "apex-info-list.xml").writeText(sb.toString())
    }

    private fun clientComposition() {
        runCatching { guestShell("service call SurfaceFlinger 1008 i32 1", 15_000) }
    }

    /**
     * The emulated Wi-Fi registers with ConnectivityService and connects, but its NetworkMonitor then
     * marks it "validation failed" for good: every probe socket it binds to the network is refused and
     * DNS through the network answers ENONET, because netd cannot attach an interface to the network
     * here. Internet itself works (the guest's traffic goes out through the host), so the check is
     * switched off: with captive_portal_mode = 0 (IGNORE) NetworkMonitor reports the network as
     * validated without probing it. (AEmulator Plus; here only for a guest whose emulated Wi-Fi is on,
     * and read by NetworkMonitor when the network registers, so it is set once the system is up.)
     */
    private fun skipInternetCheck() {
        if (img.api < 21) return
        runCatching {
            val out = guestShell("export PATH=/system/bin:/system/xbin:\$PATH; " +
                "settings put global captive_portal_mode 0; settings put global captive_portal_detection_enabled 0; " +
                "settings get global captive_portal_mode", 60_000)
            log("wifi: internet check off (captive_portal_mode=" + out.trim().lines().lastOrNull().orEmpty() + ")")
        }.onFailure { log("wifi: could not switch the internet check off: ${it.message}") }
    }

    private fun onProp(k: String, v: String) {
        if (k == "sys.powerctl") { powerRequest(v); return }
        // SurfaceFlinger has just come up (it resets this before starting the boot animation)
        if (k == "service.bootanim.exit" && v == "0" && engine == Engine.N64 && kernel.clientComposition)
            Thread { clientComposition() }.start()
        if (k == "service.bootanim.exit" && v == "1" && screenUpAt == 0L) screenUpAt = System.currentTimeMillis()
        if ((k == "sys.boot_completed" || k == "dev.bootcomplete") && v == "1" && bootDoneAt == 0L) {
            bootDoneAt = System.currentTimeMillis()
            everBooted = true
            log("★ system booted in ${(bootDoneAt - bootAt) / 1000} s")
            // second mode dump: against the "fixup" one it shows whether the widening
            // survived the boot (owners-key-measure Q2-f)
            TreeFixer(ctx, paths, img, ::log).dumpModes("booted")
            setState(State.RUNNING)
            Thread { agentBootComplete() }.start()
            Thread { substBootComplete() }.start()
            Thread { afterBoot() }.start()
            mirrorTombstones()
        }
    }

    /**
     * The modules ApexStub reports (<root>/apex/.aemu-apexes): name, package file, preinstalled path, version. The
     * package manager parses the package file itself (its AndroidManifest.xml), so it is the one the importer set
     * aside when it unpacked the module; the preinstalled path tells it the partition.
     */
    private fun apexStandInList() = runCatching {
        val apex = File(paths.root, "apex")
        val out = File(apex, ".aemu-apexes")
        val parked = File(paths.root, "system/.aemu-parked").listFiles()?.filter { it.name.endsWith(".apex") || it.name.endsWith(".capex") }?.sortedBy { it.name } ?: emptyList()
        if (out.isFile && out.lastModified() >= (parked.maxOfOrNull { it.lastModified() } ?: 0L) && out.length() > 0) return@runCatching
        fun fields(b: ByteArray): Pair<String?, Long> {
            var i = 0; var name: String? = null; var version = 1L
            fun varint(): Long { var v = 0L; var sh = 0; while (i < b.size) { val c = b[i++].toInt(); v = v or ((c and 0x7f).toLong() shl sh); sh += 7; if (c and 0x80 == 0) break }; return v }
            while (i < b.size) {
                val tag = varint().toInt()
                when (tag and 7) {
                    0 -> { val v = varint(); if (tag shr 3 == 2) version = v }
                    2 -> { val n = varint().toInt(); if (tag shr 3 == 1 && i + n <= b.size) name = String(b, i, n); i += n }
                    1 -> i += 8; 5 -> i += 4; else -> i = b.size
                }
            }
            return name to version
        }
        val sb = StringBuilder(); val seen = HashSet<String>()
        for (f in parked) runCatching {
            val (name, version) = java.util.zip.ZipFile(f).use { z -> fields(z.getInputStream(z.getEntry("apex_manifest.pb")).use { it.readBytes() }) }
            if (name == null || !seen.add(name) || !File(apex, "$name/apex_manifest.pb").isFile) return@runCatching
            // "system#apex#x.apex" is where the package was: /system/apex/x.apex
            val was = "/" + f.name.replace('#', '/')
            sb.append("$name\t/system/.aemu-parked/${f.name}\t$was\t$version\n")
        }
        out.writeText(sb.toString())
        log("apex stand-in: ${seen.size} modules")
    }.onFailure { log("apex stand-in: ${it.message}") }

    private fun onCtl(start: Boolean, svc: String) {
        if (seatMode) return   // a seat boot's own init owns ctl.* — the host must not double-start
        if (svc.startsWith("aidl/")) {
            // a lazy AIDL service (artd of 14+, a skin's daemon): started when servicemanager is asked for it, and
            // a few times at most, as the question comes every second for as long as the client waits
            if (!kernel.lazyAidlServices) return
            if (kernel.apexStandIn && svc == "aidl/apexservice") return
            val def = InitPlan.lazyAidl(svc.removePrefix("aidl/"), paths.root) ?: return
            synchronized(procs) {
                if (procs[def.name]?.isAlive == true) return
                val n = lazyStarts[def.name] ?: 0
                if (n >= 3) return
                lazyStarts[def.name] = n + 1
            }
            log("lazy service ${def.name} asked for ($svc)")
            Thread { runCatching { startService(def) } }.start()
            return
        }
        val plan = servicePlan()
        if (svc == "bootanim" || svc == "bootanimation") {
            if (!start) {
                // 4.x sets service.bootanim.exit first and the animation quits on its own, closing its audio.
                // Killing it outright left Samsung's boot sound track half-open: mediaserver hung and every
                // app creating a sound (the phone process, ToneGenerator) got an ANR.
                val p = synchronized(procs) { procs.remove("bootanim") } ?: return
                Thread {
                    runCatching { if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
                }.start()
                return
            }
            // below 4.1 (2.3, MIUI ICS) the animation does not quit by itself: killed, it leaves the GL bridge on its last frame
            if (img.api < 16 || bootDoneAt != 0L || synchronized(procs) { procs.containsKey("bootanim") }) return
            InitPlan.optional("bootanim", img, paths.root)?.let { def ->
                Thread { runCatching { startService(def) } }.start()
                log("boot animation started")
            }
            return
        }
        val def = plan.firstOrNull { it.name == svc } ?: InitPlan.optional(svc, img, paths.root)
        if (def == null) { log("ctl.${if (start) "start" else "stop"} $svc: no such service"); return }
        synchronized(procs) { procs.remove(svc) }?.destroyForcibly()
        if (start) Thread { runCatching { startService(def) } }.start()
    }

    private fun afterBoot() {
        val r = guestRunner
        if (skipWizardNow()) return   // the system comes up again and gets here once more
        // (again: something in the first seconds after the boot completes turns it back off, and it is not known what;
        // a call made half a minute later stays)
        if (engine == Engine.N64 && kernel.clientComposition) Thread {
            for (wait in longArrayOf(0, 10_000, 20_000)) {
                Thread.sleep(wait)
                if (state != State.RUNNING) return@Thread
                clientComposition()
            }
        }.start()
        r.run(listOf("/system/bin/sh", "-c", "export PATH=/system/bin:/system/xbin:\$PATH; svc power stayon true"), 60_000)
        if (engine == Engine.N64 && kernel.webViewSingleProcess)
            runCatching { guestShell("cmd webviewupdate disable-multiprocess", 30_000) }
        TreeFixer(ctx, paths, img, ::log).noScreenSleep()
        disableBrokenComponents()
        if (wifiOn) skipInternetCheck()
        if (img.api >= 24) runCatching { enableHome() }.onFailure { log("home screen check failed: $it") }
        val img2 =img.copy(lastBootMs = bootDoneAt - bootAt, bootCount = img.bootCount + 1)
        ImageStore.save(ctx, img2)
    }

    /**
     * The first boot ends in the vendor's setup wizard: the package list it has to be switched off in exists only
     * now, and the running system keeps its own copy of the settings. Several wizards cannot be passed here at all
     * (Sony's 4.0 one shows an empty screen, Samsung's waits for the network time), so the system is restarted once,
     * with the wizard disabled and the device marked as set up while it is down.
     */
    private fun skipWizardNow(): Boolean {
        if (wizardSkipped || zygoteRestarts > 0 || img.bootCount > 0) return false
        // MIUI ships its own wizard under the AOSP provisioner's name, and it does not finish by itself
        val wizards = TreeFixer.FIRST_RUN_WIZARDS + (if (File(paths.root, "system/app/miui/miui.apk").isFile) listOf("com.android.provision") else emptyList())
        val pkgFile = File(paths.root, "data/system/packages.xml")
        // 12+ keeps its package lists in binary XML (ABX) - and a text one written by request (persist.sys.binary_xml)
        // is not read back by 14 ("illegal attribute name: :strings"). There the running system is asked instead.
        if (runCatching { pkgFile.inputStream().use { i -> ByteArray(3).also { i.read(it) } }.contentEquals("ABX".toByteArray()) }.getOrDefault(false)) {
            val listed = runCatching { guestShell("pm list packages", 60_000) }.getOrDefault("")
            val wizard = wizards.firstOrNull { listed.lines().any { l -> l.trim() == "package:$it" } } ?: return false
            wizardSkipped = true
            plannedRestart = true
            log("first boot: setup wizard ($wizard) switched off in the running system, restarting it")
            runCatching { guestShell("settings put global device_provisioned 1; settings put secure user_setup_complete 1; pm disable-user --user 0 $wizard", 60_000) }
            synchronized(procs) { procs["zygote"] }?.let { z -> pidOf(z)?.let { runCatching { AProcess.sendSignal(it, 9) } } }
            return true
        }
        val pkgs = runCatching { pkgFile.readText() }.getOrNull() ?: return false
        val restr = runCatching { File(paths.root, "data/system/users/0/package-restrictions.xml").readText() }.getOrDefault("")
        val wizard = wizards.firstOrNull {
            pkgs.contains("<package name=\"$it\"") && !Regex("<pkg name=\"${Regex.escape(it)}\"[^>]*enabled=\"2\"").containsMatchIn(restr)
        } ?: return false
        wizardSkipped = true
        plannedRestart = true
        log("first boot: restarting the system past the setup wizard ($wizard)")
        synchronized(procs) { procs["zygote"] }?.let { z -> pidOf(z)?.let { runCatching { AProcess.sendSignal(it, 9) } } }
        return true
    }

    /**
     * The home screen of a firmware can ship switched off and be switched on by its setup wizard or by an app that
     * runs during it (the Google app's launcher activity on Nexus 8.0, turned on for the GoogleHome stub). With the
     * wizard skipped the only home left is Settings' FallbackHome, a black screen. Then every home activity the
     * package manager knows of but does not resolve, in a package that has an entry in the app list, is switched on.
     */
    private fun enableHome() {
        val resolved = guestShell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME", 30_000)
        val homes = Regex("""\S+/\S+""").findAll(resolved).map { it.value }.toSet()
        if (homes.any { !it.endsWith("FallbackHome") }) return
        val table = guestShell("dumpsys package -f r", 90_000).lines()
        val off = LinkedHashSet<String>()
        for (i in table.indices) {
            if (!table[i].contains("\"android.intent.category.HOME\"")) continue
            // the component line is the one above the filter's action/category lines
            val comp = (i downTo maxOf(0, i - 8)).firstNotNullOfOrNull { j -> Regex("""\s[0-9a-f]+ (\S+/\S+) filter""").find(table[j])?.groupValues?.get(1) } ?: continue
            if (comp !in homes) off += comp
        }
        // (not the system's own fallbacks, and not a setup or recovery flow: a launcher's package shows in the app list)
        val own = homes.map { it.substringBefore('/') }.toSet() + "android"
        val listed = Regex("""(\S+)/\S+""").findAll(guestShell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER", 30_000))
            .map { it.groupValues[1] }.toSet()
        val c = off.firstOrNull { it.substringBefore('/') !in own && it.substringBefore('/') in listed } ?: return
        log("home screen $c was switched off, switching it on")
        guestShell("pm enable $c; am start -a android.intent.action.MAIN -c android.intent.category.HOME", 30_000)
    }

    /**
     * 2.3–4.1 Google network location (in Play services and in Maps) calls TelephonyRegistry.listen with notifyNow at
     * start: the reply of that call is lost between the nested oneway callback and the reply, and the service dies with
     * "Unknown exception code" and a "has stopped" dialog on every boot. Nothing here needs network location.
     */
    private fun disableBrokenComponents() {
        if (img.api !in 9..16) return
        val marker = File(paths.root, "data/.aemu-components-disabled2")
        if (marker.isFile) return
        val list = listOf(
            "com.google.android.location/com.google.android.location.NetworkLocationService",
            "com.google.android.location/com.google.android.location.internal.server.NetworkLocationService",
            "com.google.android.apps.maps/com.google.android.location.internal.server.NetworkLocationService",
            "com.google.android.apps.maps/com.google.android.location.NetworkLocationService",
        )
        // PATH of 2.3–4.x starts with /sbin: the pm script's "app_process" is then looked up there and qemu gives up
        for (c in list) runCatching { guestRunner.run(listOf("/system/bin/sh", "-c", "export PATH=/system/bin:/system/xbin:\$PATH; pm disable $c"), 120_000) }
        runCatching { marker.writeText("1") }
    }

    /**
     * Third dumpModes timepoint (contacts-cantopen-desk): a guest that dies before
     * sys.boot_completed never produces the "booted" dump, so when the watchdog
     * declares a zygote death fatal it also captures the post-boot tree state as
     * "stop". Once per VM run; read-only lstat + log, the stop flow itself is
     * untouched.
     */
    private val stopDumpDone = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun dumpModesOnStop() {
        if (!stopDumpDone.compareAndSet(false, true)) return
        runCatching { TreeFixer(ctx, paths, img, ::log).dumpModes("stop") }
            .onFailure { log("modedump[stop]: dump failed: ${it.message}") }
    }

    /** qemu maps a guest path into the tree only if the file exists there, so the shim's request file is pre-made */
    private val power: File get() = File(paths.root, "dev/aemu_power")

    private fun watchdog() {
        runCatching { power.writeText(""); power.setWritable(true, false) }
        Thread({
            var restarts = HashMap<String, Int>()
            while (!stopping) {
                Thread.sleep(3000)
                if (power.length() > 0) { val v = runCatching { power.readText().trim() }.getOrDefault("reboot"); power.writeText(""); powerRequest(v) }
                if (img.api >= 14) runCatching { dns.ensure() }
                if (img.engine == Engine.N64 && img.api == 23) runCatching { vpnHub.ensure() }
                val dead = synchronized(procs) { procs.filter { !it.value.isAlive }.keys.toList() }
                for (name in dead) {
                    if (stopping) break
                    val p = synchronized(procs) { procs[name] } ?: continue
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    if (name == "zygote" || name == "zygote_secondary") {
                        if (state != State.FAILED && bootDoneAt == 0L && !(everBooted && zygoteRestarts < 6)) {
                            failure = "$name exited (code $code)" + if (code == 137) " — killed: the phone ran out of memory, or system_server died and took zygote with it (see the guest log)" else ""
                            if (code == 137 && guestLogHas("No original dex files found"))
                                failure = "system_server cannot start: the precompiled code of the framework (system/framework/oat) is missing from this container — import the firmware again"
                            log("✖ $failure"); setState(State.FAILED)
                            dumpModesOnStop()
                        } else if (plannedRestart) {
                            plannedRestart = false
                            killZygoteChildren()
                            TreeFixer(ctx, paths, img, ::log).noScreenSleep()
                            restartZygote()
                        } else if (bootDoneAt > 0 || everBooted) {
                            zygoteRestarts++
                            val now = System.currentTimeMillis()
                            zygoteCrashes.addLast(now)
                            while (zygoteCrashes.isNotEmpty() && zygoteCrashes.first() < now - 600_000) zygoteCrashes.removeFirst()
                            val recentCrashesIn1Min = zygoteCrashes.count { it >= now - 60_000 }
                            if (recentCrashesIn1Min >= 5 || zygoteCrashes.size > 6) {
                                failure = "system keeps crashing: $name exited $recentCrashesIn1Min times in 1 min (${zygoteCrashes.size} times in 10 min, last code $code)"
                                log("✖ $failure"); setState(State.FAILED)
                                dumpModesOnStop()
                            } else {
                                log("✖ $name crashed (code $code), restarting system ($zygoteRestarts)")
                                restartZygote()
                            }
                        }
                        synchronized(procs) { procs.remove(name) }
                        continue
                    }
                    val n = restarts.getOrDefault(name, 0)
                    val svc = img.services.firstOrNull { it.name == name }
                        ?: if (name.contains("-hal-") && n < 3) servicePlan().firstOrNull { it.name == name } else null
                    // init restarts surfaceflinger and then zygote ("onrestart restart zygote"); without that a
                    // crashed compositor leaves the last frame on screen forever
                    if (name == "surfaceflinger" && svc != null && bootDoneAt > 0 && n < 12) {
                        restarts[name] = n + 1
                        log("✖ surfaceflinger crashed (code $code), restarting it and the system (${n + 1}/12)")
                        synchronized(procs) { procs.remove(name) }
                        runCatching { startService(svc) }
                        synchronized(procs) { procs["zygote"] }?.let { z -> pidOf(z)?.let { runCatching { AProcess.sendSignal(it, 9) } } }
                        continue
                    }
                    // родной audio_policy производителя может падать с нашим HAL — подменяем на AOSP
                    if (name == "mediaserver" && (code == 139 || code == 134)) swapAudioPolicy(crashed = n >= 3)
                    // (init restarts every service that is not oneshot; a HAL that leaves when its only client, system_server, is
                    // restarted would otherwise keep the new system_server waiting for it forever)
                    val hal = name.contains("-hal-") && n < 3
                    if (svc != null && (svc.restart || name in ALWAYS_RESTART || hal) && code != 137 && code != 143 && n < 12) {
                        restarts[name] = n + 1
                        log("service $name crashed (code $code), restarting (${n + 1}/12)")
                        synchronized(procs) { procs.remove(name) }
                        runCatching { startService(svc) }
                    } else synchronized(procs) { procs.remove(name) }
                }
            }
        }, "vm-watchdog").apply { isDaemon = true; start() }
    }

    /**
     * Ставит политику AOSP 4.3 за обёрткой: родная сохраняется, лишние потоки производителя
     * (Samsung: 0..14 против 0..9 в AOSP) обёртка сводит к MUSIC, чтобы AOSP-код не писал за массив.
     */
    private fun swapAudioPolicy(crashed: Boolean = false) {
        if (img.api < 16) return
        val f = File(paths.root, "system/lib/hw/audio_policy.default.so")
        val aosp = File(paths.root, "system/lib/libaemu_apaosp.so")
        val parked = File(paths.root, "system/.aemu-parked/system#lib#hw#audio_policy.default.so")
        // MediaTek: AudioPolicyService переделан (другие ops и слоты), политика AOSP в нём падает.
        // Родная политика MTK падала только из-за проверки DRVB, которую теперь снимает TreeFixer
        if (TreeFixer.isMtkAudio(paths.root)) {
            if (parked.isFile) runCatching {
                parked.copyTo(f, overwrite = true); parked.delete(); aosp.delete()
                log("audio: MediaTek, restored stock audio_policy")
            }
            return
        }
        // the AOSP policy itself crashes in some vendors' audio services (Samsung 4.4: its first output is not found
        // right after it is added): after three more crashes in a row it is taken out again for good, and mediaserver
        // runs as it did before the swap
        val bad = File(paths.root, "system/.aemu-parked/audio_policy.aosp-bad")
        if (bad.isFile) return
        fun assetSize(n: String) = runCatching { EngineAssets.size(ctx, "engines/kk/$n") }.getOrDefault(-1L)
        if (aosp.length() == assetSize("audio_policy.default.so") && f.length() == assetSize("audio_policy.wrap.so")) {
            if (!crashed) return
            runCatching {
                bad.parentFile?.mkdirs(); bad.writeText("")
                aosp.delete()
                if (parked.isFile) { parked.copyTo(f, overwrite = true); parked.delete() } else f.delete()
                log("audio: the AOSP audio_policy crashes here too, removed it")
            }
            return
        }
        runCatching {
            parked.parentFile?.mkdirs()
            if (!parked.isFile && f.isFile) f.copyTo(parked, overwrite = true)
            EngineAssets.open(ctx, "engines/kk/audio_policy.default.so").use { i -> aosp.outputStream().use { o -> i.copyTo(o) } }
            EngineAssets.open(ctx, "engines/kk/audio_policy.wrap.so").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            log("audio: firmware audio_policy crashes, installed AOSP one (stock kept)")
        }
    }

    @Volatile private var plannedRestart = false
    private var wizardSkipped = false

    /**
     * Copies the guest tree's native-crash tombstones into run/ (GuestLog.mirrorTombstones),
     * always on its own thread: a zygote restart and the boot itself must never wait on a
     * copy. Runs after every boot_completed and, delayed a few seconds, after every zygote
     * restart — debuggerd writes the tombstone as the crash happens, so it is already in
     * the tree by restart time even if the new system never reaches boot_completed.
     */
    private fun mirrorTombstones(delayMs: Long = 0) {
        Thread {
            if (delayMs > 0) runCatching { Thread.sleep(delayMs) }
            if (!tombstoneMirrorBusy.compareAndSet(false, true)) return@Thread
            try {
                runCatching { GuestLog.mirrorTombstones(paths.root, paths.bin, ::log) }
                    .onFailure { log("tombstone: mirror scan failed: ${it.message}") }
            } finally { tombstoneMirrorBusy.set(false) }
        }.start()
    }

    private fun restartZygote() {
        // the stream keeps the dead system's last records above this marker
        logStream.mark("zygote restart")
        val z = img.services.firstOrNull { it.name == "zygote" } ?: return
        bootDoneAt = 0
        setState(State.BOOTING)
        paths.socket("zygote").delete()
        paths.socket("zygote_secondary").delete()
        // на настоящем ядре дети зиготы гибнут вместе с system_server; здесь они остаются жить и
        // держат ссылки на мёртвые службы (телефония отказывает новому system_server в правах)
        val n = killZygoteChildren()
        if (n > 0) log("killed apps of previous zygote: $n")
        // healthd keeps the battery listener of the dead system_server and stops answering the new one:
        // BatteryService.onStart then blocks in registerListener forever
        img.services.firstOrNull { it.name == "healthd" }?.let { h ->
            synchronized(procs) { procs.remove("healthd") }?.let { runCatching { it.destroyForcibly() } }
            runCatching { startService(h) }
        }
        runCatching { startService(z) }
        img.services.firstOrNull { it.name == "zygote_secondary" }?.let { z2 ->
            synchronized(procs) { procs.remove("zygote_secondary") }?.let { runCatching { it.destroyForcibly() } }
            runCatching { startService(z2) }
        }
        // the crash that brought us here left its tombstone in the tree; mirror it host-side
        mirrorTombstones(4_000)
        // the substitute daemon hears about the zygote restart like a real daemon would
        if (substOn) Thread { runCatching { substStage("--zygote-restart", 30_000) } }.start()
    }

    private fun guestLogHas(text: String): Boolean = runCatching {
        val f = File(paths.root, "dev/log/main")
        val len = f.length()
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(len, 400_000L).toInt(); r.seek(len - n)
            val b = ByteArray(n); r.readFully(b)
            String(b, Charsets.ISO_8859_1).contains(text)
        }
    }.getOrDefault(false)

    private fun killZygoteChildren(): Int {
        val marker = "/images/${img.id}/"
        var n = 0
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
            // app_process с aemu-stubs.jar / aemu-wifi.jar / aemu-bt.jar / aemu-agent.jar — наши службы (bluetooth_manager, Wi-Fi, агент), не приложения зиготы
            if (cmd.contains(marker) && cmd.contains("/system/bin/app_process") && !cmd.contains("aemu-stubs.jar") && !cmd.contains("aemu-wifi.jar") && !cmd.contains("aemu-bt") && !cmd.contains("aemu-agent.jar")) {
                runCatching { AProcess.sendSignal(pid, 9) }; n++
            } else if (orphanOfThisImage(d)) {
                // 7.0+: a zygote child rewrites its whole command line ("system_server"), so the path is gone from it;
                // what is left of a dead zygote is known by the root in its environment and by having no parent
                runCatching { AProcess.sendSignal(pid, 9) }; n++
            }
        }
        return n
    }

    /** a guest program of this image whose parent has exited (init of the host adopted it) */
    private fun orphanOfThisImage(proc: File): Boolean {
        val status = runCatching { File(proc, "status").readLines() }.getOrNull() ?: return false
        if (status.none { it.startsWith("PPid:") && it.substringAfter(':').trim() == "1" }) return false
        val env = runCatching { String(File(proc, "environ").readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return false
        return env.split(0.toChar()).any { it == "AEMU64_ROOT=${paths.root.absolutePath}" || it == "AEMU64_ROOT=${paths.root.canonicalPath}" }
    }

    // ------------------------------------------------------------------ остановка

    fun stop() {
        if (state == State.STOPPED) return
        stopping = true
        setState(State.STOPPING)
        log("stopping system")
        if (recoveryMode) log("stop: skipping guest sync (recovery mode)")
        else runCatching { guestRunner.run(listOf("/system/bin/sync"), 5_000) }
        log("stop: begin killAll"); killAll(); log("stop: done killAll")
        log("stop: begin adbStop"); adb.stop(); log("stop: done adbStop")
        log("stop: begin propsStop"); props.stop(); log("stop: done propsStop")
        log("stop: begin inputStop"); input.stop(); log("stop: done inputStop")
        log("stop: begin framesStop"); frames.stop(); log("stop: done framesStop")
        log("stop: begin rilStop"); ril.stop(); log("stop: done rilStop")
        log("stop: begin voldStop"); vold.stop(); log("stop: done voldStop")
        log("stop: begin audioStop"); audio.stop(); log("stop: done audioStop")
        log("stop: begin netStop"); net.stop(); log("stop: done netStop")
        log("stop: begin dnsStop"); dns.stop(); log("stop: done dnsStop")
        log("stop: begin vpnHubStop"); vpnHub.stop(); log("stop: done vpnHubStop")
        log("stop: begin eventsStop"); events.stop(); log("stop: done eventsStop")
        log("stop: begin logdStop"); logd.stop(); log("stop: done logdStop")
        log("stop: begin logStreamStop"); logStream.stop(); log("stop: done logStreamStop")
        log("stop: begin lightsStop"); lights.stop(); log("stop: done lightsStop")
        log("stop: begin sensorsStop"); sensors.stop(); log("stop: done sensorsStop")
        log("stop: begin micStop"); mic.stop(); log("stop: done micStop")
        log("stop: begin cameraStop"); camera.stop(); log("stop: done cameraStop")
        log("stop: begin vibrationStop"); vibration.stop(); log("stop: done vibrationStop")
        log("stop: begin batteryStop"); battery.stop(); log("stop: done batteryStop")
        log("stop: begin bluetoothStop"); bluetooth.stop(); log("stop: done bluetoothStop")
        log("stop: begin GlHostStop"); GlHost.stop(); log("stop: done GlHostStop")
        extraStubs.forEachIndexed { i, s -> log("stop: begin extraStub[$i]Stop"); s.stop(); log("stop: done extraStub[$i]Stop") }; extraStubs.clear()
        log("stop: begin lmkStop"); lmk?.stop(); lmk = null; log("stop: done lmkStop")
        log("stop: begin KeeperRelease"); Keeper.release(ctx); log("stop: done KeeperRelease")
        log("stop: begin setStateStopped"); setState(State.STOPPED); log("stop: done setStateStopped")
    }

    private fun killAll() {
        val list = synchronized(procs) { procs.values.toList() }
        for (g in pgids) runCatching { android.system.Os.kill(-g, android.system.OsConstants.SIGKILL) }
        list.forEach { runCatching { it.destroyForcibly() } }
        synchronized(procs) { procs.clear() }
        pgids.clear()
        killLeftovers()
    }

    /** Добивает процессы гостя, оставшиеся от прошлых запусков (qemu, binderd, dhdrun). */
    fun killLeftovers(): Int {
        val mine = AProcess.myPid()
        var n = 0
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == mine) return@forEach
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1).replace('\u0000', ' ') }.getOrNull() ?: return@forEach
            val ours = cmd.contains("libaemu64") || cmd.contains("libbinderd64") || (cmd.contains("/images/") && (cmd.contains("libqemu") || cmd.contains("libbinderd") || cmd.contains("libdhdrun") || cmd.contains("libglserverd"))) ||
                cmd.startsWith("${ctx.packageName}:binder") || cmd.startsWith("${ctx.packageName}:gl")
            if (ours) { runCatching { AProcess.sendSignal(pid, 9) }; n++ }
        }
        if (n > 0) log("cleaned up processes from previous run: $n")
        return n
    }

    // ------------------------------------------------------------------ служебное

    fun bootSeconds(): Long = if (bootAt == 0L) 0 else ((if (bootDoneAt > 0) bootDoneAt else System.currentTimeMillis()) - bootAt) / 1000

    /**
     * The PATH of 2.3–4.x starts with /sbin: am/pm/input then look up "app_process" there first and qemu gives up
     * ("Error while loading …/sbin/app_process"), so every menu action that ran such a script failed.
     */
    fun guestShell(cmd: String, timeoutMs: Long = 60_000): String =
        guestRunner.run(listOf("/system/bin/sh", "-c", shellPrefix + cmd), timeoutMs).second

    /** Put in front of every guest shell command (menu, adb, terminal): PATH order and the tool wrappers. */
    val shellPrefix: String get() = "export PATH=/system/bin:/system/xbin:\$PATH; $toolWrappers"

    /**
     * Shell functions that run the firmware's Java tools (am, pm, input, svc…) through app.aemu.stub.Run, which
     * calls System.exit() after main(): left alone, the tool's VM waits forever for its binder thread and the command
     * never returns. Jar and class come from each tool's own script in /system/bin.
     */
    private val toolWrappers: String by lazy {
        val stubs = "/system/framework/aemu-stubs.jar"
        if (!File(paths.root, stubs.removePrefix("/")).isFile) return@lazy ""
        val sb = StringBuilder()
        File(paths.root, "system/bin").listFiles()?.forEach { f ->
            if (!f.isFile || f.length() > 8_000 || !f.name.matches(Regex("[a-z][a-z0-9_]*"))) return@forEach
            val text = runCatching { f.readText(Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
            val cls = Regex("app_process\\S*\\s+\\S+\\s+([A-Za-z_][\\w.$]*\\.[A-Z][\\w$]*)").find(text)?.groupValues?.get(1) ?: return@forEach
            val jar = Regex("framework/([\\w.-]+\\.jar)").find(text)?.groupValues?.get(1) ?: return@forEach
            sb.append("${f.name}() { CLASSPATH=/system/framework/$jar:$stubs app_process /system/bin app.aemu.stub.Run $cls \"\$@\"; }; ")
        }
        sb.toString()
    }

    fun installApk(apkOnSdcard: String): String = guestShell("pm install -r $apkOnSdcard", 600_000)

    /**
     * APK import through the guest agent: the file is streamed over the agent socket and written by the
     * agent into the guest's own import inbox, then installed from there by [installApk]. Returns pm's
     * output, or null when the agent is not connected (the reason is in the log; there is no fallback
     * staging — the host writing into the guest's card folder was the broken path this replaces).
     */
    fun importApk(name: String, size: Long, open: () -> java.io.InputStream?): String? {
        val guestPath = agent.putApk(name, size, open) ?: return null
        log("agent: imported $name -> $guestPath, installing")
        return runCatching { installApk(guestPath) }.getOrElse { it.toString() }
    }

    /**
     * The Root Manager in the guest changed the hide list: the guest's own list file is what the
     * per-app views read, and it is applied by the boot path — there is no daemon to tell while the
     * system runs, so the bridge reports the change as deferred, not as done. While the Magisk
     * substitute's switch is on there IS a daemon to tell: the change goes to its denylist at
     * once (and the boot-complete seat re-syncs the whole list from the same file).
     */
    private fun agentDeny(pkg: String, on: Boolean): AgentBridge.DenyAnswer {
        if (substOn) {
            val out = runCatching { substExec("--denylist ${if (on) "add" else "rm"} $pkg", 30_000) }.getOrElse { it.message ?: "" }
            log("magisk-sub: denylist ${if (on) "add" else "rm"} $pkg (agent hide list)${out.takeIf { it.isNotBlank() }?.let { " -> $it" } ?: ""}")
            return AgentBridge.DenyAnswer(true, "applied to the substitute daemon's denylist")
        }
        return AgentBridge.DenyAnswer(false, "the guest's list applies at the next boot")
    }

    /** One substitute CLI call through the payload tmp binary, output trimmed. */
    private fun substExec(args: String, timeoutMs: Long = 60_000): String =
        guestShell("${SubstituteSupport.TMP}/magisk $args </dev/null", timeoutMs).trim()

    /**
     * One substitute stage call. The stage CLIs are silent on success, so anything they do
     * print is diagnostic — a failed daemon wake reports itself on stderr — and it goes to
     * the host log instead of being dropped: the substitute's first field run failed with
     * no trace anywhere precisely because this output was discarded at every seat.
     */
    private fun substStage(args: String, timeoutMs: Long = 60_000) {
        val out = runCatching { substExec(args, timeoutMs) }.getOrElse { "error: ${it.message}" }
        if (out.isNotBlank()) log("magisk-sub: $args -> ${out.take(400)}")
    }

    /** Lines of the daemon's own log already mirrored into the host log by [substMirrorDaemonLog]. */
    private var substDaemonLogSeen = 0

    /**
     * Mirror the daemon's own log (its /cache/magisk.log, read straight from the guest
     * tree) into the host log, only the lines not mirrored yet: the daemon's lifecycle —
     * its start, its stage work, its own error lines, and the silence where it died —
     * stays readable next to the seats that probed it.
     */
    private fun substMirrorDaemonLog() {
        val f = File(paths.root, "cache/magisk.log")
        if (!f.isFile) return
        val lines = runCatching { f.readLines() }.getOrNull() ?: return
        if (lines.size < substDaemonLogSeen) substDaemonLogSeen = 0
        for (i in substDaemonLogSeen until lines.size) log("magisk-sub: daemon log: ${lines[i].take(300)}")
        substDaemonLogSeen = lines.size
    }

    /**
     * The substitute's liveness, written for these seats: the daemon's socket file exists in
     * the guest and the daemon answers CHECK_VERSION. Both halves are logged either way, so
     * the host log can tell a live substitute from a dead one at every stage. A live verdict
     * names the daemon's pid (its pidfile); a dead one asks the guest whether that pid is
     * gone — "started and died" reads differently from "never started".
     */
    private fun substLiveness(after: String) {
        substMirrorDaemonLog()
        val out = runCatching {
            guestShell("if [ -S ${SubstituteSupport.TMP}/.magisk/device/socket ]; then ${SubstituteSupport.TMP}/magisk -v; else echo no-socket; fi", 30_000)
        }.getOrElse { "error: ${it.message}" }.trim()
        val pidFile = "${SubstituteSupport.TMP}/.magisk/device/daemon.pid"
        if (out.startsWith("30.7")) {
            val pid = runCatching { guestShell("cat $pidFile", 15_000) }.getOrNull()?.trim()?.toIntOrNull()
            log("magisk-sub: daemon alive after $after (version $out${pid?.let { ", pid $it" } ?: ""})")
        } else {
            log("magisk-sub: daemon NOT answering after $after ($out)")
            val state = runCatching {
                guestShell("p=\$(cat $pidFile 2>/dev/null); if [ -n \"\$p\" ]; then if [ -d /proc/\$p ]; then echo \"pid \$p exists but is not answering\"; else echo \"pid \$p gone\"; fi; fi", 15_000)
            }.getOrNull()?.trim().orEmpty()
            if (state.isNotEmpty()) log("magisk-sub: daemon state after $after: $state")
        }
    }

    /**
     * The substitute's pre-services seat: prepare the payload tmp (the daemon's magisk_tmp():
     * the .magisk structure, the daemon binary, busybox and its applet links — sources are the
     * /data/adb deployment TreeFixer planted), link the /system names into it, then run the
     * post-fs-data stage. The stage call is the daemon's first root client of the boot, so it
     * is also what wakes the daemon (its create=true); the host never execs --daemon.
     */
    private fun substPostFsData() {
        runCatching {
            val t = SubstituteSupport.TMP
            guestShell(
                "mkdir -p $t/.magisk/device $t/.magisk/worker $t/.magisk/mirror $t/.magisk/rootdir $t/.magisk/busybox && " +
                    "cp ${SubstituteSupport.BIN}/magisk $t/magisk && chmod 755 $t/magisk && " +
                    "cp ${SubstituteSupport.BIN}/busybox $t/.magisk/busybox/busybox && chmod 755 $t/.magisk/busybox/busybox && " +
                    "$t/.magisk/busybox/busybox --install -s $t/.magisk/busybox",
                60_000)
            substLinkSystem()
            substStage("--post-fs-data", 120_000)
            log("magisk-sub: post-fs-data seat done")
            substLiveness("post-fs-data")
        }.onFailure { log("magisk-sub: post-fs-data seat failed: ${it.message}") }
    }

    /**
     * The /system/bin and /system/xbin names (su, magisk, resetprop) as links into the payload
     * tmp. A firmware file in the way is set aside as <name>.aemu-orig (only when that name is
     * still free — a later seat never overwrites the first seat's original); TreeFixer's
     * switch-off path removes the links and restores the originals.
     */
    private fun substLinkSystem() {
        val t = SubstituteSupport.TMP
        val cmds = StringBuilder()
        for (dir in listOf("/system/bin", "/system/xbin")) for (name in SubstituteSupport.SYSTEM_LINKS) {
            cmds.append(
                "if [ -L $dir/$name ]; then rm -f $dir/$name; " +
                    "elif [ -e $dir/$name ]; then if [ -e $dir/$name.aemu-orig ]; then rm -f $dir/$name; else mv $dir/$name $dir/$name.aemu-orig; fi; fi; " +
                    "ln -s $t/magisk $dir/$name; ")
        }
        guestShell(cmds.toString(), 60_000)
    }

    /** The substitute's late_start seat, after the service plan is up. */
    private fun substLateStart() {
        runCatching {
            substStage("--service")
            log("magisk-sub: late_start seat done")
            substLiveness("late_start")
        }.onFailure { log("magisk-sub: late_start seat failed: ${it.message}") }
    }

    /**
     * sys.boot_completed, the substitute's seat: finish the daemon's stage, re-sync its
     * denylist from the guest agent's hide list (the manager package itself excluded — it
     * must never hide from itself), and make sure the manager app inside the guest is the
     * delivered build (see [substInstallApp]).
     */
    private fun substBootComplete() {
        if (!substOn) return
        runCatching {
            substStage("--boot-complete")
            log("magisk-sub: boot-complete seat done")
            if (agentOn) {
                val hide = AgentSupport.readHideList(paths.root)?.filter { it != SubstituteSupport.PACKAGE }
                if (hide != null) {
                    val cur = substExec("--denylist ls").lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    for (pkg in hide) if (pkg !in cur) substExec("--denylist add $pkg", 30_000)
                    for (pkg in cur) if (pkg !in hide) substExec("--denylist rm $pkg", 30_000)
                    log("magisk-sub: denylist synced from the hide list (${hide.size} entries)")
                }
            }
            substInstallApp()
            substLiveness("boot-complete")
        }.onFailure { log("magisk-sub: boot-complete seat failed: ${it.message}") }
    }

    /**
     * The manager app inside the guest, kept at the delivered build: the copy TreeFixer
     * planted at [SubstituteSupport.APK_PATH], staged through data/local/tmp and installed
     * with pm — the same versionCode gate and uninstall-on-refusal shape as the agent's
     * inbox app above. Unlike that app this one is signed with the project's long-term key,
     * so a replace install across builds is the normal path and the fallback is a rarity.
     */
    private fun substInstallApp() {
        val apk = File(paths.root, SubstituteSupport.APK_PATH)
        if (!apk.isFile) { log("magisk-sub: ${SubstituteSupport.APK_PATH} is not in the image, no manager app"); return }
        val pkgPath = guestShell("pm path ${SubstituteSupport.PACKAGE}", 60_000).lines()
            .firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim()
        if (pkgPath != null) {
            val installedCode = Regex("""versionCode=(\d+)""")
                .find(guestShell("dumpsys package ${SubstituteSupport.PACKAGE}", 60_000))
                ?.groupValues?.get(1)?.toIntOrNull()
            val stagedCode = runCatching {
                ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.versionCode
            }.getOrNull()
            if (installedCode != null && stagedCode != null) {
                if (installedCode == stagedCode) { log("magisk-sub: manager app already at versionCode $stagedCode"); return }
                log("magisk-sub: manager app versionCode $installedCode, delivered $stagedCode: updating")
            } else {
                val guestLen = guestShell("wc -c < $pkgPath", 30_000).trim().toLongOrNull()
                if (guestLen == apk.length()) return
            }
        }
        val staged = File(paths.root, "data/local/tmp").apply { mkdirs() }.let { File(it, "aemu-magisk-sub.apk") }
        java.nio.file.Files.copy(apk.toPath(), staged.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        staged.setReadable(true, false)
        try {
            var out = installApk("/data/local/tmp/aemu-magisk-sub.apk").trim()
            if (out.contains("Success")) {
                log("magisk-sub: manager app install -> ${out.take(300)}")
            } else {
                log("magisk-sub: manager app update refused (${out.take(200)}); reinstalling from scratch")
                val un = guestShell("pm uninstall ${SubstituteSupport.PACKAGE}", 60_000).trim()
                out = installApk("/data/local/tmp/aemu-magisk-sub.apk").trim()
                log("magisk-sub: manager app install -> ${out.take(300)} (after uninstall: ${un.take(120)})")
            }
        } finally {
            staged.delete()
        }
    }

    /** sha256 of a file in this tree, or null when it cannot be read. */
    private fun sha256Hex(f: File): String? = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * sys.boot_completed: the agent's inbox app (the AEmuImport list inside the guest). Staged through data/local/tmp, a plain tree directory the installer reads fine —
     * installed when it is missing or its bytes differ from the delivered copy. The gate is the
     * content hash, not a number: the versionCode gate this replaces slept through a whole agent
     * rebuild because the manifest number did not move with the code (the gate2 egress prints
     * shipped in the tree for two field runs and were never installed), and the length gate before
     * that collided (1.3 and 1.4 both landed on 24,973 bytes). The installed apk is a file in this
     * same tree — the guest's /data is the tree's data/ — so both hashes are taken host-side with
     * the same code. versionCode stays as the auxiliary fallback for when a hash cannot be read on
     * either side, length behind it. A replace install the package
     * manager refuses falls back to an uninstall and a fresh install: the app is signed by a key its build
     * generates on the spot (the keystore is gitignored, so no two builds share one) and pm will not replace
     * across signatures, which would otherwise pin the guest to the first build's copy forever. The app
     * holds no state of its own — the hide list is the agent service's file, the inbox a folder — so the
     * fresh install loses nothing.
     */
    private fun agentBootComplete() {
        if (!agentOn) return
        runCatching {
            val apk = File(paths.root, AgentSupport.APK_COPY)
            if (!apk.isFile) { log("agent: aemu-agent.apk is not in the image (build guest-agent-app/build.sh), no inbox app"); return }
            val pkgPath = guestShell("pm path ${AgentSupport.APP_PACKAGE}", 60_000).lines()
                .firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim()
            if (pkgPath != null) {
                val stagedHash = sha256Hex(apk)
                val installedFile = File(paths.root, pkgPath.removePrefix("/"))
                val installedHash = if (installedFile.isFile) sha256Hex(installedFile) else null
                if (stagedHash != null && installedHash != null) {
                    if (stagedHash == installedHash) {
                        log("agent: inbox app already at the delivered bytes (sha256 ${stagedHash.take(12)}…)")
                        return
                    }
                    log("agent: inbox app bytes differ (installed ${installedHash.take(12)}…, delivered ${stagedHash.take(12)}…): updating")
                } else {
                    val installedCode = Regex("""versionCode=(\d+)""")
                        .find(guestShell("dumpsys package ${AgentSupport.APP_PACKAGE}", 60_000))
                        ?.groupValues?.get(1)?.toIntOrNull()
                    val stagedCode = runCatching {
                        ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.versionCode
                    }.getOrNull()
                    if (installedCode != null && stagedCode != null) {
                        if (installedCode == stagedCode) return
                        log("agent: inbox app versionCode $installedCode, delivered $stagedCode: updating")
                    } else {
                        val guestLen = guestShell("wc -c < $pkgPath", 30_000).trim().toLongOrNull()
                        if (guestLen == apk.length()) return
                    }
                }
            }
            val staged = File(paths.root, "data/local/tmp").apply { mkdirs() }.let { File(it, "aemu-agent.apk") }
            java.nio.file.Files.copy(apk.toPath(), staged.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            staged.setReadable(true, false)
            try {
                var out = installApk("/data/local/tmp/aemu-agent.apk").trim()
                if (out.contains("Success")) {
                    log("agent: inbox app install -> ${out.take(300)}")
                } else {
                    log("agent: inbox app update refused (${out.take(200)}); reinstalling from scratch")
                    val un = guestShell("pm uninstall ${AgentSupport.APP_PACKAGE}", 60_000).trim()
                    out = installApk("/data/local/tmp/aemu-agent.apk").trim()
                    log("agent: inbox app install -> ${out.take(300)} (after uninstall: ${un.take(120)})")
                }
            } finally {
                staged.delete()
            }
        }.onFailure { log("agent: boot-complete stage failed: ${it.message}") }
    }

    private fun pidOf(p: Process): Int? {
        var c: Class<*>? = p.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == "pid" }
            if (f != null) { f.isAccessible = true; return f.getInt(p) }
            c = c.superclass
        }
        return null
    }

    /** What init of 10+ does after reading the property files: ro.product.<what> from the first partition that has
     *  ro.product.<partition>.<what>, and the fingerprint from its parts. Only where the firmware does not say. */
    private fun deriveIdentity(later: MutableMap<String, String>) {
        val all = LinkedHashMap<String, String>()
        for (n in listOf("system/build.prop", "vendor/default.prop", "vendor/build.prop", "odm/etc/build.prop", "odm/build.prop",
            "product/etc/build.prop", "product/build.prop", "product_services/build.prop", "system_ext/etc/build.prop", "system_ext/build.prop")) runCatching {
            File(paths.root, n).takeIf { it.isFile }?.let { f -> PropArea.parseProps(f.readText()).forEach { (k, v) -> all.putIfAbsent(k, v) } }
        }
        // (the files init of 10 reads besides the ones above: the odm's and the product's)
        for (n in listOf("odm/etc/build.prop", "odm/build.prop", "product/etc/build.prop", "product/build.prop", "product_services/build.prop",
            "system_ext/etc/build.prop", "system_ext/build.prop", "mi_ext/etc/build.prop")) runCatching {
            File(paths.root, n).takeIf { it.isFile }?.let { f -> PropArea.parseProps(f.readText()).forEach { (k, v) -> later.putIfAbsent(k, v) } }
        }
        val order = (all["ro.product.property_source_order"] ?: "product,product_services,odm,vendor,system").split(',')
        for (what in listOf("brand", "device", "manufacturer", "model", "name")) {
            if (all.containsKey("ro.product.$what")) continue
            val v = order.firstNotNullOfOrNull { all["ro.product.${it.trim()}.$what"]?.takeIf { s -> s.isNotEmpty() } } ?: continue
            all["ro.product.$what"] = v; later.putIfAbsent("ro.product.$what", v)
        }
        if (!all.containsKey("ro.build.fingerprint")) {
            fun p(k: String) = all[k] ?: "unknown"
            later.putIfAbsent("ro.build.fingerprint", p("ro.product.brand") + "/" + p("ro.product.name") + "/" + p("ro.product.device") + ":" +
                p("ro.build.version.release") + "/" + p("ro.build.id") + "/" + p("ro.build.version.incremental") + ":" + p("ro.build.type") + "/" + p("ro.build.tags"))
        }
    }

    private fun gmtZone(): String {
        val h = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000
        return if (h == 0) "Etc/GMT" else "Etc/GMT" + (if (h > 0) "-$h" else "+${-h}")
    }

    private fun isLinkTo(f: File, target: String) = runCatching { android.system.Os.readlink(f.absolutePath) == target }.getOrDefault(false)

    private fun stamp() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
}
