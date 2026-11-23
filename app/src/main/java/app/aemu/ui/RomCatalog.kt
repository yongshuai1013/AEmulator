package app.aemu.ui

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aemu.R
import java.io.File

private fun formatLabelRes(format: String): Int = when (format) {
    "sdk-platform-zip" -> R.string.catalog_format_sdk
    "factory-item" -> R.string.catalog_format_factory
    "odin-zip" -> R.string.catalog_format_odin
    else -> R.string.catalog_format_ota
}

private fun versionLine(e: RomEntry): String =
    if (e.api > 0) "Android ${e.android} · API ${e.api}" else "Android ${e.android}"

/**
 * The ROM catalog page (#34): series → version band → entry, backed by [RomCatalogModel].
 * A verified download is handed to [onImportFile]; importing itself is the library's job,
 * with its usual progress card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RomCatalogScreen(model: RomCatalogModel, onBack: () -> Unit, onImportFile: (File) -> Unit) {
    val state by model.state.collectAsState()
    val download by model.download.collectAsState()
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf<RomEntry?>(null) }

    BackHandler { onBack() }
    LaunchedEffect(download) {
        val d = download
        if (d is RomDownloadState.Ready) {
            selected = null
            onImportFile(d.file)
            model.consumeReady()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.catalog_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.catalog_back)) }
                },
                actions = {
                    IconButton(onClick = { model.load() }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.catalog_refresh)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = {
                    Text(stringResource(when (state.origin) {
                        CatalogOrigin.ONLINE -> R.string.catalog_online
                        CatalogOrigin.CACHE -> R.string.catalog_cached
                        CatalogOrigin.SNAPSHOT -> R.string.catalog_snapshot
                    }))
                })
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                state.entries.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.catalog_error), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { model.load() }) { Text(stringResource(R.string.catalog_retry)) }
                }
                else -> {
                    val bySeries = remember(state.entries) { state.entries.groupBy { it.series } }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        bySeries.forEach { (series, inSeries) ->
                            item(key = "s:$series") {
                                Text(series, style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                            }
                            inSeries.groupBy { it.band }.forEach { (band, inBand) ->
                                item(key = "b:$series:$band") {
                                    Text(band, style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 6.dp))
                                }
                                items(inBand.size, key = { "e:" + inBand[it].id }) { i ->
                                    RomEntryRow(inBand[i], onClick = { selected = inBand[i] })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        RomEntryDialog(
            entry = entry,
            download = download,
            onDismiss = { selected = null; model.dismissFailure() },
            onDownload = { model.download(entry) },
            onCancelDownload = { model.cancelDownload() },
            onOpenSource = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://archive.org/details/${entry.sourceItem}"))) }
            },
        )
    }
}

@Composable
private fun RomEntryRow(e: RomEntry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    ElevatedCard(onClick = onClick, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${versionLine(e)} · ${Formatter.formatShortFileSize(ctx, e.size)} · ${stringResource(formatLabelRes(e.format))}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RomEntryDialog(
    entry: RomEntry,
    download: RomDownloadState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenSource: () -> Unit,
) {
    val ctx = LocalContext.current
    val running = download as? RomDownloadState.Running
    val verifying = download as? RomDownloadState.Verifying
    val failed = download as? RomDownloadState.Failed
    val mineRunning = running?.entryId == entry.id
    val mineVerifying = verifying?.entryId == entry.id
    val busy = running != null || verifying != null
    AlertDialog(
        onDismissRequest = { if (!mineRunning && !mineVerifying) onDismiss() },
        title = { Text(entry.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(versionLine(entry), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(formatLabelRes(entry.format)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.catalog_size, Formatter.formatShortFileSize(ctx, entry.size)),
                    style = MaterialTheme.typography.bodyMedium)
                entry.device?.let {
                    Text(stringResource(R.string.catalog_device, it), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.catalog_source, entry.sourceItem),
                    style = MaterialTheme.typography.bodyMedium)
                Text("MD5: ${entry.md5}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("SHA-1: ${entry.sha1}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.note.isNotEmpty()) Text(entry.note, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    mineRunning -> {
                        Spacer(Modifier.height(4.dp))
                        if (running!!.progress >= 0f) LinearWavyProgressIndicator(progress = { running.progress }, modifier = Modifier.fillMaxWidth())
                        else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("${stringResource(R.string.catalog_downloading)} ${Formatter.formatShortFileSize(ctx, running.downloaded)} / ${Formatter.formatShortFileSize(ctx, running.total)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    mineVerifying -> {
                        Spacer(Modifier.height(4.dp))
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.catalog_verifying), style = MaterialTheme.typography.bodySmall)
                    }
                    failed?.entryId == entry.id -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (failed!!.message.contains("checksum")) stringResource(R.string.catalog_hash_mismatch)
                            else stringResource(R.string.catalog_download_failed, failed.message),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onOpenSource) { Text(stringResource(R.string.catalog_open_source)) }
            }
        },
        confirmButton = {
            when {
                mineRunning || mineVerifying -> TextButton(onClick = onCancelDownload) { Text(stringResource(R.string.cancel)) }
                entry.usability == "reference" -> {}
                else -> Button(onClick = onDownload, enabled = !busy) { Text(stringResource(R.string.catalog_download_import)) }
            }
        },
        dismissButton = {
            if (!mineRunning && !mineVerifying) TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
