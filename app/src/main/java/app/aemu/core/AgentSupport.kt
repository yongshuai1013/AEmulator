package app.aemu.core

import java.io.File

/**
 * The guest agent: a small service of ours inside the guest (aemu-agent.jar, guest-agent/), started like the
 * Wi-Fi and Bluetooth services. It is the in-guest half of two features:
 *
 *  - the per-app views (the hide list): the mount layer can only filter by uid, and only the guest's own
 *    package manager knows which uid a package has. The agent resolves VmSettings.hiddenApps through it and
 *    keeps <root>/dev/.aemu-hideuids current; until it is up, [write] seeds the same file from the image's
 *    packages.xml, which is the package manager's own database (text form; Android 12+ writes a binary ABX
 *    form we do not parse — then only the agent's answer counts, and it comes as soon as it connects).
 *  - the APK import: the host streams the file over the agent socket and the agent writes it into the
 *    guest's own external storage (see AgentBridge.putApk), where the package installer can read it. The
 *    old staging wrote the file into the tree from the host, into a view of the sdcard the guest does not
 *    share, and `pm install` could not parse it.
 *
 * The agent is off unless VmSettings.agent is on; with it off nothing here writes anything and no service
 * starts, so the guest is exactly what it was before this feature existed.
 */
object AgentSupport {
    /** settings + the hide list, read by the agent at start and on every SYNC */
    const val FILE = "data/system/aemu-agent.properties"
    /** the delivered service jar (TreeFixer) */
    const val JAR_PATH = "system/framework/aemu-agent.jar"
    const val JAR_NAME = "aemu-agent.jar"
    const val MAIN_CLASS = "app.aemu.agent.AgentStub"
    /** the delivered inbox app (TreeFixer), installed at boot complete like the Magisk app */
    const val APK_COPY = "data/adb/aemu-agent.apk"
    const val APP_PACKAGE = "app.aemu.agent"
    /** guest path of the uid set the mount layer filters by (vmount.h reads <root>/dev/.aemu-hideuids) */
    const val HIDEUIDS = "dev/.aemu-hideuids"
    /** host socket name (VmPaths.socket), the guest connects to /dev/socket/aemu-agent */
    const val SOCKET = "aemu-agent"

    /** engines/images the agent jar runs on: the native engine's range, and QEMU's KK range like the Wi-Fi service */
    fun supported(engine: Engine, api: Int, recovery: Boolean): Boolean {
        if (recovery) return false
        return when (engine) {
            Engine.N64 -> api in 21..37
            Engine.KK -> api in 14..25 || api in 9..10
            Engine.GB -> false
        }
    }

    fun offered(root: File, engine: Engine, api: Int, recovery: Boolean): Boolean =
        supported(engine, api, recovery) && File(root, JAR_PATH).isFile

    /**
     * Boot preparation: the agent's settings file, and the fallback uid set for the window before the agent
     * connects. An empty list (or the agent off) removes the uid file: no reader is hidden then, which is
     * the baseline the mount layer must reproduce exactly.
     */
    fun write(root: File, s: VmSettings, log: (String) -> Unit) {
        // the manager itself is never hidden from: it is the one place root is managed from
        val hidden = (if (s.agent) s.hiddenApps else emptyList()).filter { it != MagiskCore.PACKAGE }
        runCatching {
            val f = File(root, FILE).apply { parentFile?.mkdirs() }
            f.writeText(
                "# the guest agent's settings (host-written at every boot)\n" +
                    "enabled=${s.agent}\n" +
                    "hidden=${hidden.joinToString(",")}\n"
            )
        }.onFailure { log("agent: could not write its settings: ${it.message}") }
        val uidFile = File(root, HIDEUIDS)
        if (hidden.isEmpty()) {
            if (uidFile.isFile && uidFile.delete()) log("agent: hide list empty, the uid set is removed (no reader is hidden)")
            return
        }
        val pkgs = parsePackagesXml(root)
        if (pkgs == null) {
            log("agent: packages.xml is not in text form; the uid set waits for the agent's own answer")
            return
        }
        val uids = hidden.mapNotNull { pkgs[it] }.distinct().sorted()
        runCatching {
            uidFile.parentFile?.mkdirs()
            uidFile.writeText(uids.joinToString("\n", postfix = "\n"))
            log("agent: hide list seeded from packages.xml: ${uids.size} of ${hidden.size} package(s) resolved" +
                if (uids.size < hidden.size) "; the rest is the agent's to resolve" else "")
        }.onFailure { log("agent: could not seed the uid set: ${it.message}") }
    }

    /**
     * package name -> uid out of the image's data/system/packages.xml, or null when the file is not the
     * text form (Android 12+ ABX) or is missing. Shared users resolve through their <shared-user> entry.
     */
    fun parsePackagesXml(root: File): Map<String, Int>? {
        val f = File(root, "data/system/packages.xml")
        if (!f.isFile) return null
        val head = ByteArray(4)
        val n = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (n < 1 || head[0] != '<'.code.toByte()) return null   // ABX starts with 'A','B','X',0
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val shared = HashMap<String, Int>()
        Regex("""<shared-user[^>]*\bname="([^"]+)"[^>]*\buserId="(\d+)"""").findAll(text)
            .forEach { shared[it.groupValues[1]] = it.groupValues[2].toInt() }
        Regex("""<shared-user[^>]*\buserId="(\d+)"[^>]*\bname="([^"]+)"""").findAll(text)
            .forEach { shared[it.groupValues[2]] = it.groupValues[1].toInt() }
        val out = LinkedHashMap<String, Int>()
        val tag = Regex("""<package\b[^>]*>""")
        val name = Regex("""\bname="([^"]+)"""")
        val uid = Regex("""\buserId="(\d+)"""")
        val sharedId = Regex("""\bsharedUserId="([^"]+)"""")
        for (m in tag.findAll(text)) {
            val t = m.value
            val pkg = name.find(t)?.groupValues?.get(1) ?: continue
            val direct = uid.find(t)?.groupValues?.get(1)?.toIntOrNull()
            val via = sharedId.find(t)?.groupValues?.get(1)?.let { shared[it] }
            val u = direct ?: via ?: continue
            out[pkg] = u
        }
        return out
    }

    /** the image's installed packages for the settings picker; null when packages.xml is not parseable */
    fun installedPackages(root: File): List<String>? = parsePackagesXml(root)?.keys?.sorted()
}
