package app.aemu.core

import java.io.File

/**
 * The guest agent: a small service of ours inside the guest (aemu-agent.jar, guest-agent/), started like the
 * Wi-Fi and Bluetooth services. It is the in-guest half of two features:
 *
 *  - the per-app views (the hide list): the mount layer can only filter by uid, and only the guest's own
 *    package manager knows which uid a package has. The list itself is the guest's own file ([LIST_FILE]),
 *    owned and written by the Root Manager app inside the guest through the agent; the agent resolves it
 *    to uids and keeps <root>/dev/.aemu-hideuids current. The host only ever reads the list: [write]
 *    seeds the uid set from it (through the image's packages.xml) for the window before the agent is up,
 *    packages.xml is the package
 *    manager's own database (text form; Android 12+ writes a binary ABX form we do not parse — then only
 *    the agent's answer counts, and it comes as soon as it connects).
 *  - the APK import: the host streams the file over the agent socket and the agent writes it into the
 *    guest's own external storage (see AgentBridge.putApk), where the package installer can read it. The
 *    old staging wrote the file into the tree from the host, into a view of the sdcard the guest does not
 *    share, and `pm install` could not parse it.
 *
 * The agent is off unless VmSettings.agent is on; with it off nothing here writes anything and no service
 * starts, so the guest is exactly what it was before this feature existed.
 */
object AgentSupport {
    /** the agent's settings (the enable flag; the hide list is [LIST_FILE], not in here any more) */
    const val FILE = "data/system/aemu-agent.properties"
    /**
     * The hide list itself: one package per line, inside the guest. Written only by the agent (for the
     * Root Manager app); the host reads it in [readHideList] and never writes it — the single exception
     * is the one-time migration in [write], which copies the legacy settings list over on the first boot
     * that finds no guest file, so a list made with the old host-side picker is not silently dropped.
     */
    const val LIST_FILE = "data/system/aemu-hideapps.txt"
    /** the delivered service jar (TreeFixer) */
    const val JAR_PATH = "system/framework/aemu-agent.jar"
    const val JAR_NAME = "aemu-agent.jar"
    const val MAIN_CLASS = "app.aemu.agent.AgentStub"
    /** the delivered inbox app (TreeFixer), installed at boot complete */
    const val APK_COPY = "data/adb/aemu-agent.apk"
    const val APP_PACKAGE = "app.aemu.agent"
    /** the Magisk manager's package name: if a guest still carries that app it is never put on a hide
     * list — it manages root, so it must always see the full guest (the agent refuses it at write time too) */
    const val MAGISK_PACKAGE = "com.topjohnwu.magisk"
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
     * The guest's hide list, or null when the guest has no list file yet (which is not the same as an
     * empty list: an empty file is a list the manager set to nothing, a missing file is one nobody set —
     * the migration in [write] keys on the difference). The Magisk app is filtered out wherever the list
     * is consumed; it is refused at write time in the guest as well.
     */
    fun readHideList(root: File): List<String>? {
        val f = File(root, LIST_FILE)
        if (!f.isFile) return null
        return runCatching {
            f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }.getOrDefault(emptyList())
    }

    /**
     * Boot preparation: the agent's settings file, and the fallback uid set for the window before the agent
     * connects. An empty list (or the agent off) removes the uid file: no reader is hidden then, which is
     * the baseline the mount layer must reproduce exactly. The list read here is the guest's own file; the
     * one write of it the host ever does is the legacy migration below.
     */
    fun write(root: File, s: VmSettings, log: (String) -> Unit) {
        runCatching {
            val f = File(root, FILE).apply { parentFile?.mkdirs() }
            f.writeText(
                "# the guest agent's settings (host-written at every boot)\n" +
                    "enabled=${s.agent}\n"
            )
        }.onFailure { log("agent: could not write its settings: ${it.message}") }
        val uidFile = File(root, HIDEUIDS)
        if (!s.agent) {
            if (uidFile.isFile && uidFile.delete()) log("agent: off, the uid set is removed (no reader is hidden)")
            return
        }
        var hidden = readHideList(root)
        if (hidden == null && s.hiddenApps.isNotEmpty()) {
            // one-time migration: the list still lives in the legacy settings field (the host-side picker
            // the Root Manager replaces). Copy it into the guest once; from then on the file is the only
            // source and this branch never runs again for this image.
            val migrated = s.hiddenApps.filter { it != MAGISK_PACKAGE && it != APP_PACKAGE }.distinct()
            val done = runCatching {
                val f = File(root, LIST_FILE).apply { parentFile?.mkdirs() }
                f.writeText(migrated.joinToString("\n", postfix = if (migrated.isEmpty()) "" else "\n"))
                true
            }.getOrDefault(false)
            if (done) {
                log("agent: the hide list moved into the guest (${migrated.size} package(s) from the old settings); the Root Manager app manages it now")
                hidden = migrated
            } else {
                log("agent: could not move the hide list into the guest; the agent starts with an empty one")
            }
        }
        // the places root is managed from are never hidden from (the agent refuses them at write time too)
        val list = (hidden ?: emptyList()).filter { it != MAGISK_PACKAGE && it != APP_PACKAGE }
        if (list.isEmpty()) {
            if (uidFile.isFile && uidFile.delete()) log("agent: hide list empty, the uid set is removed (no reader is hidden)")
            return
        }
        val pkgs = parsePackagesXml(root)
        if (pkgs == null) {
            log("agent: packages.xml is not in text form; the uid set waits for the agent's own answer")
            return
        }
        val uids = list.mapNotNull { pkgs[it] }.distinct().sorted()
        runCatching {
            uidFile.parentFile?.mkdirs()
            uidFile.writeText(uids.joinToString("\n", postfix = "\n"))
            log("agent: hide list seeded from packages.xml: ${uids.size} of ${list.size} package(s) resolved" +
                if (uids.size < list.size) "; the rest is the agent's to resolve" else "")
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
}
