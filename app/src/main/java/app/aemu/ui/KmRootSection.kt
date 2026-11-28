/* AEmulator addition, 2026-11-28. GPL-3.0; see LICENSE. */
package app.aemu.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.GuestImage
import app.aemu.core.KmRoot
import app.aemu.core.VmPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Guest keymaster root" settings section: generate (with certificate
 * content typed in by the user — nothing is pre-filled), import, export,
 * implant into this guest's libsoftkeymasterdevice.so by exact-length slot
 * replacement, and restore. Only rendered when the guest actually carries
 * that library (older / 32-bit guests do not).
 */
@Composable
internal fun KmRootSection(img: GuestImage) {
    val ctx = LocalContext.current
    val paths = remember(img.id) { VmPaths(ctx, img.id) }
    val hasLibrary = remember(img.id) { KmRoot.soFile(paths).isFile }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<KmRoot.Status?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var cn by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var ou by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var locality by remember { mutableStateOf("") }
    var years by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmImplant by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    fun refresh() {
        status = runCatching { KmRoot.status(paths) }.getOrNull()
    }
    LaunchedEffect(img.id) {
        status = withContext(Dispatchers.IO) {
            runCatching { KmRoot.status(paths) }.getOrNull()
        }
    }

    // Runs [block] off the main thread; it returns the success string
    // resource to show. Failures are mapped to honest, specific text.
    fun runOp(block: () -> Int) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            busy = false
            refresh()
            message = r.fold(
                { ctx.getString(it) },
                { ctx.getString(R.string.kmroot_failed, kmErrorText(ctx, it)) },
            )
        }
    }

    val importPick = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val pw = password.takeIf { it.isNotEmpty() }?.toCharArray()
        runOp {
            val data = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            KmRoot.importBundle(paths, data, pw)
            R.string.kmroot_done_import
        }
    }
    val exportPick = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runOp {
            val pem = KmRoot.exportPem(paths)
                ?: throw KmRoot.KmException(KmRoot.KmException.Kind.NO_MATERIAL)
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(pem) }
            R.string.kmroot_done_export
        }
    }

    if (!hasLibrary) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.kmroot_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.kmroot_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val st = status
        if (st == null || st.meta == null) {
            Text(
                stringResource(R.string.kmroot_status_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val m = st.meta!!
            val createdText = java.text.DateFormat.getDateTimeInstance()
                .format(java.util.Date(m.createdAt))
            Text(
                stringResource(R.string.kmroot_subject, m.rootSubject),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.kmroot_fp_rsa, m.rsaRootFp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.kmroot_fp_ec, m.ecRootFp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.kmroot_meta_line,
                    stringResource(
                        if (m.source == "imported") R.string.kmroot_source_imported
                        else R.string.kmroot_source_generated,
                    ),
                    createdText,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    when (st.soState) {
                        KmRoot.SoState.PRISTINE -> R.string.kmroot_state_pristine
                        KmRoot.SoState.IMPLANTED_THIS -> R.string.kmroot_state_implanted_this
                        KmRoot.SoState.IMPLANTED_OTHER -> R.string.kmroot_state_implanted_other
                        KmRoot.SoState.CHANGED -> R.string.kmroot_state_changed
                        KmRoot.SoState.NO_SO -> R.string.kmroot_err_no_so
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.kmroot_fields), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.kmroot_fields_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = cn, onValueChange = { cn = it }, singleLine = true,
            label = { Text("CN") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = org, onValueChange = { org = it }, singleLine = true,
            label = { Text("O") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ou, onValueChange = { ou = it }, singleLine = true,
            label = { Text("OU") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = country, onValueChange = { country = it }, singleLine = true,
            label = { Text("C") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state, onValueChange = { state = it }, singleLine = true,
            label = { Text("ST") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = locality, onValueChange = { locality = it }, singleLine = true,
            label = { Text("L") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = years, onValueChange = { years = it.filter { ch -> ch.isDigit() }.take(2) },
            singleLine = true,
            label = { Text(stringResource(R.string.kmroot_years)) },
            supportingText = { Text(stringResource(R.string.kmroot_years_sub)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(
                enabled = !busy,
                onClick = {
                    val form = KmRoot.SubjectForm(
                        cn = cn, o = org, ou = ou, c = country, st = state, l = locality,
                    )
                    val y = years.toIntOrNull()
                    when {
                        form.isBlank() ->
                            message = ctx.getString(R.string.kmroot_need_subject)
                        y == null || y !in 1..50 ->
                            message = ctx.getString(R.string.kmroot_need_years)
                        else -> runOp {
                            val material = KmRoot.generate(form, y)
                            KmRoot.saveMaterial(paths, material, "generated")
                            R.string.kmroot_done_generate
                        }
                    }
                },
            ) { Text(stringResource(R.string.kmroot_generate)) }
        }
        Text(stringResource(R.string.kmroot_import), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.kmroot_import_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, singleLine = true,
            label = { Text(stringResource(R.string.kmroot_password)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = !busy,
                onClick = { importPick.launch(arrayOf("*/*")) },
            ) { Text(stringResource(R.string.kmroot_import)) }
            TextButton(
                enabled = !busy && status?.meta != null,
                onClick = { exportPick.launch("keymaster-bundle.pem") },
            ) { Text(stringResource(R.string.kmroot_export)) }
        }
        if (status?.meta != null) {
            Text(
                stringResource(R.string.kmroot_export_warn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = !busy && status?.meta != null,
                onClick = { confirmImplant = true },
            ) { Text(stringResource(R.string.kmroot_implant)) }
            TextButton(
                enabled = !busy && status?.hasBackup == true,
                onClick = { confirmRestore = true },
            ) { Text(stringResource(R.string.kmroot_restore)) }
        }
        if (busy) {
            Text(
                stringResource(R.string.kmroot_working),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmImplant) {
        AlertDialog(
            onDismissRequest = { confirmImplant = false },
            title = { Text(stringResource(R.string.kmroot_implant_confirm_title)) },
            text = { Text(stringResource(R.string.kmroot_implant_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmImplant = false
                    runOp {
                        when (KmRoot.implant(paths)) {
                            KmRoot.ImplantResult.DONE -> R.string.kmroot_done_implant
                            KmRoot.ImplantResult.ALREADY -> R.string.kmroot_already
                        }
                    }
                }) { Text(stringResource(R.string.kmroot_implant)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmImplant = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.kmroot_restore_confirm_title)) },
            text = { Text(stringResource(R.string.kmroot_restore_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    runOp {
                        KmRoot.restore(paths)
                        R.string.kmroot_done_restore
                    }
                }) { Text(stringResource(R.string.kmroot_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun kmErrorText(ctx: Context, e: Throwable): String {
    if (e !is KmRoot.KmException) return ctx.getString(R.string.kmroot_err_generic)
    fun part(p: KmRoot.Part?): String = ctx.getString(
        when (p) {
            KmRoot.Part.RSA_ROOT -> R.string.kmroot_part_rsa_root
            KmRoot.Part.RSA_ATTEST -> R.string.kmroot_part_rsa_attest
            KmRoot.Part.EC_ROOT -> R.string.kmroot_part_ec_root
            KmRoot.Part.EC_ATTEST -> R.string.kmroot_part_ec_attest
            null -> R.string.kmroot_err_generic
        },
    )
    return when (e.kind) {
        KmRoot.KmException.Kind.NO_SO -> ctx.getString(R.string.kmroot_err_no_so)
        KmRoot.KmException.Kind.NO_MATERIAL -> ctx.getString(R.string.kmroot_err_no_material)
        KmRoot.KmException.Kind.NO_BACKUP -> ctx.getString(R.string.kmroot_err_no_backup)
        KmRoot.KmException.Kind.SHAPE -> ctx.getString(R.string.kmroot_err_shape)
        KmRoot.KmException.Kind.TOO_LONG -> ctx.getString(R.string.kmroot_err_too_long, part(e.part))
        KmRoot.KmException.Kind.LENGTH -> ctx.getString(R.string.kmroot_err_length)
        KmRoot.KmException.Kind.YEARS -> ctx.getString(R.string.kmroot_need_years)
        KmRoot.KmException.Kind.NOT_BUNDLE -> ctx.getString(R.string.kmroot_err_not_bundle)
        KmRoot.KmException.Kind.PASSWORD -> ctx.getString(R.string.kmroot_err_password)
        KmRoot.KmException.Kind.MISSING ->
            if (e.part == null) ctx.getString(R.string.kmroot_need_subject)
            else ctx.getString(R.string.kmroot_err_missing, part(e.part))
        KmRoot.KmException.Kind.CHAIN -> ctx.getString(R.string.kmroot_err_chain, part(e.part))
        KmRoot.KmException.Kind.KEY_MATCH -> ctx.getString(R.string.kmroot_err_key_match, part(e.part))
        KmRoot.KmException.Kind.KEY_SIZE -> ctx.getString(R.string.kmroot_err_key_size, part(e.part))
        KmRoot.KmException.Kind.ROOT_KEY -> ctx.getString(R.string.kmroot_err_root_key, part(e.part))
    }
}
