package com.airzelo.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.airzelo.app.ui.Az
import com.airzelo.app.ui.AzCard
import com.airzelo.app.ui.AzField
import com.airzelo.app.ui.AzScreen
import com.airzelo.app.ui.Banner
import com.airzelo.app.ui.ConfirmDialog
import com.airzelo.app.ui.EmptyState
import com.airzelo.app.ui.Fmt
import com.airzelo.app.ui.FormSection
import com.airzelo.app.ui.IconChip
import com.airzelo.app.ui.ListRow
import com.airzelo.app.ui.Muted
import com.airzelo.app.ui.Overline
import com.airzelo.app.ui.Pill
import com.airzelo.app.ui.PrimaryButton
import com.airzelo.app.ui.RowDivider
import com.airzelo.app.ui.SecondaryButton
import com.airzelo.app.ui.Tone
import com.airzelo.engine.AgencyStore
import com.airzelo.engine.Dates
import com.airzelo.engine.JValue
import java.text.DateFormat
import java.util.Date

private fun size(n: Long) = if (n >= 1_048_576) String.format(java.util.Locale.ROOT, "%.1f MB", n / 1_048_576.0) else "${n / 1024 + 1} KB"
private val KIND_TEXT = mapOf("manual" to "Manual", "auto" to "Automatic", "pre-restore" to "Before a restore", "pre-import" to "Before an import",
    "pre-recovery" to "Before a recovery")

/**
 * Device backups (encrypted with this phone's key), passphrase exports through the system file picker (Storage
 * Access Framework: Files, a computer over USB, another app), and restore / import, including desktop .zip backups.
 */
@Composable
fun BackupScreen(model: AppModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = model.store ?: return
    val backups = remember(model.revision) { store.listBackups() }
    var message by remember { mutableStateOf<String?>(null) }
    var askExport by remember { mutableStateOf(false) }
    var pass1 by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var imported by remember { mutableStateOf<ByteArray?>(null) }
    var importPass by remember { mutableStateOf("") }
    var askImportPass by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<AgencyStore.BackupInfo?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = pendingExport
        pendingExport = null
        if (uri == null || data == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(data) }
            message = "Export saved (${size(data.size.toLong())})."
        } catch (e: Exception) { model.fail(e) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val data = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            imported = data
            if (store.verify(data, null).needsPassphrase) askImportPass = true else confirmImport = true
        } catch (e: Exception) { model.fail(e) }
    }
    val hours = model.agency?.setting("auto_backup_hours", "24") ?: "24"

    AzScreen("Backup & restore", "Encrypted · stays on this phone unless you export", onBack) {
        Column(Modifier.fillMaxSize()) {
        message?.let { m -> Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) { Banner(m, Tone.Green, Icons.Rounded.CheckCircle, tag = "backup-message") } }
        LazyColumn(Modifier.fillMaxSize().testTag("backup"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "device") {
                AzCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconChip(Icons.Rounded.Save, Tone.Green); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Back up on this phone", style = MaterialTheme.typography.titleSmall, color = Az.colors.text)
                            Muted(if (hours == "0") "Automatic backups are off" else "Automatic backup when you leave the app, every $hours hours")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Back up now", { model.backupNow()?.let { message = "Backup saved and verified (${size(it.size)})." } }, Modifier.fillMaxWidth(),
                        Icons.Rounded.Save, "backup-now")
                    Spacer(Modifier.height(8.dp))
                    Muted("Encrypted with this phone's key: it restores on this phone only. Use an export to move your books.")
                }
            }
            item(key = "export") {
                AzCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconChip(Icons.Rounded.Upload, Tone.Blue); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Export & import", style = MaterialTheme.typography.titleSmall, color = Az.colors.text)
                            Muted("Passphrase-protected file for another phone, the desktop app or safe keeping")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton("Export a backup…", { askExport = true }, Modifier.fillMaxWidth(), Icons.Rounded.Upload, "export-backup")
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Restore or import from a file…", { MainActivity.expectingExternal = true; importer.launch(arrayOf("*/*")) },
                        Modifier.fillMaxWidth(), Icons.Rounded.Download, "import-backup")
                    Spacer(Modifier.height(8.dp))
                    Muted("Desktop AirZelo backups (.zip) can be imported here too. Without its passphrase an export cannot be read.")
                }
            }
            item(key = "list-title") { Overline("On this phone (${backups.size})") }
            if (backups.isEmpty()) item(key = "none") { AzCard(Modifier.fillMaxWidth()) { EmptyState(Icons.Rounded.Restore, "No backups yet", "Tap Back up now to make the first one.") } }
            if (backups.isNotEmpty()) item(key = "list") {
                AzCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    backups.forEachIndexed { i, b ->
                        if (i > 0) RowDivider()
                        ListRow(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(b.created)), size(b.size),
                            onClick = { confirmRestore = b }, tag = "backup-row-${b.name}",
                            leading = { IconChip(Icons.Rounded.Restore, if (b.kind == "auto") Tone.Cyan else if (b.kind == "manual") Tone.Green else Tone.Gray, 36.dp) }) {
                            Pill(KIND_TEXT[b.kind] ?: Fmt.title(b.kind), if (b.kind == "manual") Tone.Green else Tone.Gray)
                        }
                    }
                }
            }
        }
        }
    }

    if (askExport) ConfirmDialog("Export passphrase", "Keep this passphrase safe: the export cannot be opened without it.", "Export", danger = false,
        onDismiss = { askExport = false; pass1 = ""; pass2 = "" }, onConfirm = {
            askExport = false
            if (pass1 != pass2) model.problem = AppModel.Problem("Not exported", "The passphrases do not match.", null)
            else model.exportBytes(pass1)?.let { data ->
                pendingExport = data
                MainActivity.expectingExternal = true
                exporter.launch("AirZelo-backup-${Dates.today()}.azbackup")
            }
            pass1 = ""; pass2 = ""
        }) {
        AzField("Passphrase (8+ characters)", pass1, { pass1 = it }, tag = "export-pass-1", secret = true)
        AzField("Repeat passphrase", pass2, { pass2 = it }, tag = "export-pass-2", secret = true)
    }
    if (askImportPass) ConfirmDialog("Backup passphrase", "This backup was exported with a passphrase.", "Continue", danger = false,
        onDismiss = { askImportPass = false; imported = null }, onConfirm = { askImportPass = false; confirmImport = true }) {
        AzField("Passphrase", importPass, { importPass = it }, secret = true)
    }
    if (confirmImport) ConfirmDialog("Replace the books on this phone?",
        "The file is checked completely first. Your current books are saved as a safety backup before they are replaced.", "Restore from file",
        onDismiss = { confirmImport = false; imported = null; importPass = "" }, onConfirm = {
            confirmImport = false
            imported?.let { if (model.restoreBytes(it, importPass)) message = "Restored from the file." }
            imported = null; importPass = ""
        })
    confirmRestore?.let { b ->
        ConfirmDialog("Restore this backup?", "Your current books are saved as a safety backup first, then replaced by this backup.", "Restore",
            onDismiss = { confirmRestore = null }, onConfirm = {
                confirmRestore = null
                try { if (model.restoreBytes(store.backupData(b), null)) message = "Restored the backup ${b.name}." } catch (e: Exception) { model.fail(e) }
            })
    }
}

private val SETTING_GROUPS = listOf(
    Triple("Company", Icons.Rounded.Business, listOf("business_name" to "Display name", "legal_name" to "Legal name", "business_tagline" to "Tagline",
        "business_address" to "Address", "business_phone" to "Phone", "business_email" to "Email", "business_website" to "Website", "business_gstin" to "GSTIN",
        "business_state" to "State", "brand_color" to "Brand colour (#RRGGBB)")),
    Triple("Document numbers", Icons.Rounded.Numbers, listOf("invoice_prefix" to "Invoice prefix", "credit_note_prefix" to "Credit note prefix",
        "booking_prefix" to "Booking prefix", "receipt_prefix" to "Receipt prefix", "invoice_terms" to "Invoice terms", "invoice_footer" to "Invoice footer",
        "receipt_footer" to "Receipt footer")),
    Triple("Payment details on tickets", Icons.Rounded.AccountBalance, listOf("pay_account_name" to "Account name", "pay_account_number" to "Account number",
        "pay_ifsc" to "IFSC", "pay_bank_name" to "Bank", "pay_branch" to "Branch", "pay_upi_number" to "UPI number", "pay_upi_apps" to "UPI apps")),
    Triple("Backups", Icons.Rounded.Schedule, listOf("auto_backup_hours" to "Automatic backup every (hours, 0 = off)", "backup_keep" to "Automatic backups to keep")))

private val NUMERIC = setOf("auto_backup_hours", "backup_keep", "pay_account_number", "pay_upi_number")
private val PHONE = setOf("business_phone")

@Composable
fun SettingsScreen(model: AppModel, onBack: () -> Unit) {
    val a = model.agency ?: return
    val s = remember { mutableStateMapOf<String, String>().apply { putAll(a.allSettings()) } }
    var saved by remember { mutableStateOf(false) }
    fun save() {
        val f = SETTING_GROUPS.flatMap { it.third }.associate { (k, _) -> k to (JValue.Str(s[k] ?: "") as JValue) }
        model.attempt(f, { ag, x -> ag.updateSettings(x) }) { r -> s.clear(); s.putAll(r); saved = true; model.changed() }
    }
    AzScreen("Company & documents", "Shown on tickets, invoices and receipts", onBack, actions = {
        TextButton(onClick = { save() }, modifier = Modifier.testTag("save-settings")) { Text("Save") }
    }) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (saved) Banner("Saved.", Tone.Green, Icons.Rounded.CheckCircle)
            for ((group, icon, fields) in SETTING_GROUPS) FormSection(group, icon,
                footer = if (group.startsWith("Payment")) "Shown to customers on the ticket + payment PDF." else null) {
                for ((k, label) in fields) AzField(label, s[k] ?: "", { s[k] = it; saved = false }, tag = "setting-$k",
                    multiline = k in listOf("business_address", "invoice_terms"),
                    keyboard = when (k) { in NUMERIC -> KeyboardType.Number; in PHONE -> KeyboardType.Phone; "business_email" -> KeyboardType.Email
                        "business_website" -> KeyboardType.Uri; else -> KeyboardType.Text },
                    caps = k in listOf("business_gstin", "pay_ifsc"))
            }
            PrimaryButton("Save settings", { save() }, Modifier.fillMaxWidth())
            Box(Modifier.height(24.dp))
        }
    }
}
