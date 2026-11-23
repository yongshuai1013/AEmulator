package app.aemu.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * TreeSource view of a UBIFS volume (see [UbiFsReader], from AEmulator-Plus), so that
 * Importer.importTree consumes it like every other file system image. Our-side glue:
 * AEmulator-Plus walks the reader inline in its Importer instead.
 */
class UbiTreeSource(src: RandomSource, checkCancelled: () -> Unit = {}) : TreeSource {
    private val fs = UbiFsReader(src, checkCancelled)

    /** One line for the import log. */
    val summary: String get() = fs.summary
    val warnings: List<String> get() = fs.warnings

    override fun walk(visit: (path: String, isDir: Boolean, link: String?, mode: Int, size: Long, data: (() -> InputStream)?) -> Unit) {
        fs.walk { path, node ->
            when (node.type) {
                3 -> visit(path, true, null, fs.fileMode(node), 0, null)
                2 -> visit(path, false, node.target, fs.fileMode(node), 0, null)
                1 -> visit(path, false, null, fs.fileMode(node), node.size) {
                    val bos = ByteArrayOutputStream()
                    fs.copy(node, bos)
                    ByteArrayInputStream(bos.toByteArray())
                }
                else -> visit(path, false, null, fs.fileMode(node), 0, null) // devices/fifos: never created on the host
            }
        }
    }
}
