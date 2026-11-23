package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * The input methods a firmware ships, found on the host before the first boot (TreeFixer's
 * default-IME seed). An APK of /system/app or /system/priv-app contributes one entry per
 * service that answers android.view.InputMethod under BIND_INPUT_METHOD — the same test
 * IMMS applies. The service's method XML (the one under res/ whose root is <input-method>)
 * tells a keyboard from a pure voice panel: subtype modes are enum ints in the compiled
 * XML (keyboard 0, voice 1, handwriting 2). Only the compiled XML's string pool and
 * element tree are read; anything unexpected parses as null and the file is skipped.
 */
object ImeProbe {

    data class Ime(
        val id: String,
        val pkg: String,
        val cls: String,
        /** false only for a pure voice panel (every declared subtype is a voice one) */
        val keyboard: Boolean,
        val subtypeLocales: List<String>,
    )

    private const val ACTION_INPUT_METHOD = "android.view.InputMethod"
    private const val PERM_BIND_INPUT_METHOD = "android.permission.BIND_INPUT_METHOD"
    private const val SUBTYPE_MODE_VOICE = 1

    fun detect(root: File): List<Ime> {
        val out = ArrayList<Ime>()
        for (d in listOf("system/app", "system/priv-app", "vendor/app", "system/vendor/app")) {
            val dir = File(root, d)
            val apks = ArrayList<File>()
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".apk", true)) apks += f
                else if (f.isDirectory) f.listFiles()?.forEach { g -> if (g.isFile && g.name.endsWith(".apk", true)) apks += g }
            }
            for (apk in apks.sortedBy { it.path }) runCatching { out += probeApk(apk) }
        }
        return out
    }

    private fun probeApk(apk: File): List<Ime> {
        val out = ArrayList<Ime>()
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return emptyList()
            val manifest = zip.getInputStream(entry).use { it.readBytes() }
            if (manifest.size > 4_000_000) return emptyList()
            val doc = Axml.parse(manifest) ?: return emptyList()
            val pkg = doc.attrs["package"] as? String ?: return emptyList()
            // the method XML of the package: with several, no service can be matched to
            // one of them without the resource table, so all count as undeclared
            val methodXmls = ArrayList<Axml.Node>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (!e.name.startsWith("res/") || !e.name.endsWith(".xml")) continue
                val bytes = runCatching { zip.getInputStream(e).use { it.readBytes() } }.getOrNull() ?: continue
                if (bytes.size > 1_000_000) continue
                if (Axml.rootName(bytes) != "input-method") continue
                Axml.parse(bytes)?.let { methodXmls += it }
            }
            val subtypes = if (methodXmls.size == 1) methodXmls[0].children.filter { it.name == "subtype" }.map {
                ((it.attrs["imeSubtypeMode"] as? Int) ?: 0) to ((it.attrs["imeSubtypeLocale"] as? String) ?: "")
            } else null
            doc.walk { node ->
                if (node.name != "service") return@walk
                var isIme = false
                node.walk { sub -> if (sub.name == "action" && sub.attrs["name"] == ACTION_INPUT_METHOD) isIme = true }
                if (!isIme) return@walk
                if (node.attrs["permission"] as? String != PERM_BIND_INPUT_METHOD) return@walk
                val raw = node.attrs["name"] as? String ?: return@walk
                val cls = expandClass(pkg, raw)
                val keyboard = subtypes == null || subtypes.isEmpty() || subtypes.any { it.first != SUBTYPE_MODE_VOICE }
                out += Ime(flatten(pkg, cls), pkg, cls, keyboard, subtypes?.map { it.second } ?: emptyList())
            }
        }
        return out
    }

    private fun expandClass(pkg: String, name: String): String = when {
        name.startsWith(".") -> pkg + name
        !name.contains('.') -> "$pkg.$name"
        else -> name
    }

    /** ComponentName.flattenToShortString: the class shortened when it starts with the package */
    private fun flatten(pkg: String, cls: String): String =
        if (cls.startsWith("$pkg.")) pkg + "/" + cls.substring(pkg.length) else "$pkg/$cls"

    /**
     * Minimal reader for compiled (binary) Android XML: the string pool and the element
     * tree, attribute values as String / Int / Boolean / null (references stay Ints).
     */
    private object Axml {
        class Node(val name: String, val attrs: Map<String, Any?>, val children: List<Node>) {
            fun walk(f: (Node) -> Unit) { f(this); for (c in children) c.walk(f) }
        }

        private class Builder(val name: String, val attrs: Map<String, Any?>) {
            val children = ArrayList<Builder>()
            fun build(): Node = Node(name, attrs, children.map { it.build() })
        }

        fun rootName(data: ByteArray): String? = scan(data, shallow = true)?.name

        fun parse(data: ByteArray): Node? = scan(data, shallow = false)?.build()

        private fun scan(data: ByteArray, shallow: Boolean): Builder? {
            if (data.size < 16) return null
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            if ((b.getShort(0).toInt() and 0xffff) != 0x0003) return null
            var pool: List<String> = emptyList()
            var root: Builder? = null
            val stack = ArrayList<Builder>()
            var pos = 8
            while (pos + 8 <= data.size) {
                val type = b.getShort(pos).toInt() and 0xffff
                val size = b.getInt(pos + 4)
                if (size < 8 || pos + size > data.size) break
                when (type) {
                    0x0001 -> pool = stringPool(b, pos)
                    0x0102 -> {
                        val name = pool.getOrNull(b.getInt(pos + 20)) ?: return null
                        if (shallow) return Builder(name, emptyMap())
                        val node = Builder(name, attributes(b, pos, pool))
                        if (stack.isEmpty()) root = node else stack.last().children += node
                        stack += node
                    }
                    0x0103 -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                }
                pos += size
            }
            return root
        }

        private fun attributes(b: ByteBuffer, chunk: Int, pool: List<String>): Map<String, Any?> {
            val count = b.getShort(chunk + 28).toInt() and 0xffff
            val start = chunk + 16 + (b.getShort(chunk + 24).toInt() and 0xffff)
            val out = LinkedHashMap<String, Any?>()
            for (i in 0 until count) {
                val at = start + i * 20
                if (at + 20 > b.capacity()) break
                val name = pool.getOrNull(b.getInt(at + 4)) ?: continue
                val raw = b.getInt(at + 8)
                val vtype = b.get(at + 15).toInt() and 0xff
                val vdata = b.getInt(at + 16)
                out[name] = when {
                    raw != -1 -> pool.getOrNull(raw)
                    vtype == 0x03 -> pool.getOrNull(vdata)       // TYPE_STRING
                    vtype == 0x10 || vtype == 0x11 -> vdata      // TYPE_INT_DEC / TYPE_INT_HEX
                    vtype == 0x12 -> vdata != 0                  // TYPE_BOOLEAN
                    else -> vdata                                // references and the rest: the raw int
                }
            }
            return out
        }

        private fun stringPool(b: ByteBuffer, chunk: Int): List<String> {
            val count = b.getInt(chunk + 8)
            if (count < 0 || count > 100_000) return emptyList()
            val flags = b.getInt(chunk + 16)
            val stringsStart = b.getInt(chunk + 20)
            val utf8 = flags and 0x100 != 0
            val out = ArrayList<String>(count)
            for (i in 0 until count) {
                val off = chunk + stringsStart + b.getInt(chunk + 28 + i * 4)
                if (off < 0 || off >= b.capacity()) { out += ""; continue }
                out += if (utf8) readUtf8(b, off) else readUtf16(b, off)
            }
            return out
        }

        private fun readUtf8(b: ByteBuffer, at: Int): String {
            var p = at
            fun len(): Int {
                val x = b.get(p++).toInt() and 0xff
                return if (x and 0x80 != 0) ((x and 0x7f) shl 8) or (b.get(p++).toInt() and 0xff) else x
            }
            len()                       // the character count; the byte count follows
            val bytes = len()
            return String(b.array(), p, bytes, Charsets.UTF_8)
        }

        private fun readUtf16(b: ByteBuffer, at: Int): String {
            var chars = b.getShort(at).toInt() and 0xffff
            var p = at + 2
            if (chars and 0x8000 != 0) {
                chars = ((chars and 0x7fff) shl 16) or (b.getShort(p).toInt() and 0xffff)
                p += 2
            }
            val sb = StringBuilder(chars)
            for (i in 0 until chars) sb.append(b.getShort(p + i * 2).toChar())
            return sb.toString()
        }
    }
}
