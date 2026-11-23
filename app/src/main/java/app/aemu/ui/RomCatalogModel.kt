package app.aemu.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One firmware entry of the ROM catalog index (see the aemu-rom-index repo for the schema). */
data class RomEntry(
    val id: String,
    val name: String,
    val series: String,
    val band: String,
    val android: String,
    val api: Int,
    val device: String?,
    val format: String,
    val url: String,
    val size: Long,
    val md5: String,
    val sha1: String,
    val sourceItem: String,
    val sourceFile: String,
    val usability: String,
    val note: String,
)

enum class CatalogOrigin { ONLINE, CACHE, SNAPSHOT }

data class CatalogState(
    val loading: Boolean = true,
    val entries: List<RomEntry> = emptyList(),
    val origin: CatalogOrigin = CatalogOrigin.SNAPSHOT,
    val error: String? = null,
)

sealed interface RomDownloadState {
    object Idle : RomDownloadState
    data class Running(val entryId: String, val downloaded: Long, val total: Long) : RomDownloadState {
        val progress: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else -1f
    }
    data class Verifying(val entryId: String) : RomDownloadState
    data class Failed(val entryId: String, val message: String) : RomDownloadState
    data class Ready(val entryId: String, val file: File) : RomDownloadState
}

/**
 * The ROM catalog: a self-built index of public firmware archives (the aemu-rom-index repo),
 * fetched multi-source with a cache and a bundled snapshot behind it, and a downloader that
 * verifies size + md5 + sha1 before the file is handed to the regular importer.
 * The catalog is a leaf feature: every failure stays inside this model.
 */
class RomCatalogModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state
    private val _download = MutableStateFlow<RomDownloadState>(RomDownloadState.Idle)
    val download: StateFlow<RomDownloadState> = _download
    private var downloadJob: Job? = null
    @Volatile private var downloadCancelled = false

    init { load() }

    private fun dir(ctx: Context) = File(ctx.filesDir, "romcatalog").apply { mkdirs() }
    private fun cacheFile(ctx: Context) = File(dir(ctx), "index-cache.json")
    private fun downloadsDir(ctx: Context) = File(ctx.cacheDir, "romcatalog").apply { mkdirs() }

    fun load() {
        if (_state.value.loading && _state.value.entries.isNotEmpty()) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val (entries, origin, err) = withContext(Dispatchers.IO) { fetchIndex(ctx) }
            _state.value = CatalogState(loading = false, entries = entries, origin = origin, error = err)
        }
    }

    /** raw.githubusercontent first, the jsDelivr mirror second; then the last good cache; then the bundled snapshot. */
    private fun fetchIndex(ctx: Context): Triple<List<RomEntry>, CatalogOrigin, String?> {
        for (url in INDEX_SOURCES) {
            val text = runCatching { httpGetText(url) }.getOrNull() ?: continue
            val entries = runCatching { parseIndex(text) }.getOrNull() ?: continue
            runCatching { cacheFile(ctx).writeText(text) }
            return Triple(entries, CatalogOrigin.ONLINE, null)
        }
        val cached = runCatching { cacheFile(ctx).readText() }.getOrNull()
        if (cached != null) runCatching { parseIndex(cached) }.getOrNull()?.let {
            return Triple(it, CatalogOrigin.CACHE, null)
        }
        val snapshot = runCatching {
            ctx.assets.open(SNAPSHOT_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
        if (snapshot != null) runCatching { parseIndex(snapshot) }.getOrNull()?.let {
            return Triple(it, CatalogOrigin.SNAPSHOT, null)
        }
        return Triple(emptyList(), CatalogOrigin.SNAPSHOT, "no source answered")
    }

    fun download(entry: RomEntry) {
        if (_download.value is RomDownloadState.Running || _download.value is RomDownloadState.Verifying) return
        val ctx = getApplication<Application>()
        downloadCancelled = false
        _download.value = RomDownloadState.Running(entry.id, 0, entry.size)
        downloadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { downloadAndVerify(ctx, entry) } }
            result.fold(
                onSuccess = { file -> _download.value = RomDownloadState.Ready(entry.id, file) },
                onFailure = { t ->
                    _download.value = if (downloadCancelled) RomDownloadState.Idle
                    else RomDownloadState.Failed(entry.id, t.message ?: t.toString())
                },
            )
        }
    }

    fun cancelDownload() {
        downloadCancelled = true
        downloadJob?.cancel()
        _download.value = RomDownloadState.Idle
    }

    /** The screen has handed the verified file to the importer; clear the state (the file itself is pruned on the next download). */
    fun consumeReady() {
        if (_download.value is RomDownloadState.Ready) _download.value = RomDownloadState.Idle
    }

    fun dismissFailure() {
        if (_download.value is RomDownloadState.Failed) _download.value = RomDownloadState.Idle
    }

    private fun downloadAndVerify(ctx: Context, entry: RomEntry): File {
        val dir = downloadsDir(ctx)
        // one download at a time and the cache dir is ours: drop leftovers of earlier runs
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, entry.id + "-" + entry.sourceFile.substringAfterLast('/'))
        val part = File(dir, out.name + ".part")
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(entry.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AEmulator-App")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw Exception("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: entry.size
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (downloadCancelled) throw Exception("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        md5.update(buf, 0, n)
                        sha1.update(buf, 0, n)
                        downloaded += n
                        _download.value = RomDownloadState.Running(entry.id, downloaded, total)
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }
        if (downloadCancelled) { part.delete(); throw Exception("cancelled") }
        _download.value = RomDownloadState.Verifying(entry.id)
        val md5Hex = md5.digest().joinToString("") { "%02x".format(it) }
        val sha1Hex = sha1.digest().joinToString("") { "%02x".format(it) }
        if (part.length() != entry.size || !md5Hex.equals(entry.md5, true) || !sha1Hex.equals(entry.sha1, true)) {
            part.delete()
            throw Exception("checksum mismatch")
        }
        part.renameTo(out)
        return out
    }

    private fun httpGetText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "AEmulator-App")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val SNAPSHOT_ASSET = "romcatalog/index.json"
        private val INDEX_SOURCES = listOf(
            "https://raw.githubusercontent.com/yongshuai1013/aemu-rom-index/main/index.json",
            "https://cdn.jsdelivr.net/gh/yongshuai1013/aemu-rom-index@main/index.json",
        )

        fun parseIndex(text: String): List<RomEntry> {
            val root = JSONObject(text)
            val arr = root.getJSONArray("entries")
            val out = ArrayList<RomEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += RomEntry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    series = o.getString("series"),
                    band = o.getString("band"),
                    android = o.getString("android"),
                    api = o.optInt("api", 0),
                    device = if (o.isNull("device")) null else o.optString("device").ifEmpty { null },
                    format = o.optString("format"),
                    url = o.getString("url"),
                    size = o.getLong("size"),
                    md5 = o.getString("md5"),
                    sha1 = o.getString("sha1"),
                    sourceItem = o.getString("sourceItem"),
                    sourceFile = o.optString("sourceFile"),
                    usability = o.optString("usability", "direct"),
                    note = o.optString("note"),
                )
            }
            return out
        }
    }
}
