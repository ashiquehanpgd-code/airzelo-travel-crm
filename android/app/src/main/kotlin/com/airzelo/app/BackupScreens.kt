package com.airzelo.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.airzelo.engine.AgencyStore
import com.airzelo.engine.Dates
import com.airzelo.engine.JValue
import java.text.DateFormat
import java.util.Date

private fun size(n: Long) = if (n >= 1_048_576) String.format("%.1f MB", n / 1_048_576.0) else "${n / 1024 + 1} KB"

/**
 * Device backups (encrypted with this phone's key), passphrase exports through the system file picker (Storage
 * Access Framework: Files, Drive folder synced by the owner, USB ...), and restore / import, including desktop .zip backups.
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

    Screen("Backup & restore", onBack) { pad ->
        Page(pad, "backup") {
            message?.let { Text(it, color = Good, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("backup-message")) }
            Section(footer = "Backups on this phone are encrypted with this device's key. An automatic backup runs when you leave the app " +
                "(every ${model.agency?.setting("auto_backup_hours", "24") ?: "24"} hours; change it in Company & documents).") {
                Button(onClick = { model.backupNow()?.let { message = "Backup saved and verified (${size(it.size)})." } },
                    modifier = Modifier.fillMaxWidth().testTag("backup-now")) { Text("Back up now") }
            }
            Section(footer = "An export is encrypted with a passphrase you choose, so you can keep it in Files, on a computer or on another " +
                "phone. Without the passphrase it cannot be read. Desktop AirZelo backups (.zip) can be imported too.") {
                Button(onClick = { askExport = true }, modifier = Modifier.fillMaxWidth().testTag("export-backup")) { Text("Export a backup…") }
                Button(onClick = { MainActivity.expectingExternal = true; importer.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth().testTag("import-backup")) { Text("Restore or import from a file…") }
            }
            Section("On this phone") {
                if (backups.isEmpty()) Note("No backups yet.")
                for (b in backups) TextButton(onClick = { confirmRestore = b }, modifier = Modifier.testTag("backup-row-${b.name}")) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(b.created)), color = Color.Unspecified)
                        Note("${Fmt.label(b.kind)} · ${size(b.size)}")
                    }
                }
            }
        }
    }

    if (askExport) ConfirmDialog("Export passphrase", "Keep this passphrase safe: the export cannot be opened without it.", "Export",
        onDismiss = { askExport = false; pass1 = ""; pass2 = "" }, onConfirm = {
            askExport = false
            if (pass1 != pass2) { model.problem = AppModel.Problem("Not exported", "The passphrases do not match.", null) }
            else model.exportBytes(pass1)?.let { data ->
                pendingExport = data
                MainActivity.expectingExternal = true
                exporter.launch("AirZelo-backup-${Dates.today()}.azbackup")
            }
            pass1 = ""; pass2 = ""
        }) {
        Field("Passphrase (8+ characters)", pass1, { pass1 = it }, tag = "export-pass-1", secret = true)
        Field("Repeat passphrase", pass2, { pass2 = it }, tag = "export-pass-2", secret = true)
    }
    if (askImportPass) ConfirmDialog("Backup passphrase", "This backup was exported with a passphrase.", "Continue",
        onDismiss = { askImportPass = false; imported = null }, onConfirm = { askImportPass = false; confirmImport = true }) {
        Field("Passphrase", importPass, { importPass = it }, secret = true)
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
    "Company" to listOf("business_name" to "Display name", "legal_name" to "Legal name", "business_tagline" to "Tagline", "business_address" to "Address",
        "business_phone" to "Phone", "business_email" to "Email", "business_website" to "Website", "business_gstin" to "GSTIN",
        "business_state" to "State", "brand_color" to "Brand colour (#RRGGBB)"),
    "Document numbers" to listOf("invoice_prefix" to "Invoice prefix", "credit_note_prefix" to "Credit note prefix", "booking_prefix" to "Booking prefix",
        "receipt_prefix" to "Receipt prefix", "invoice_terms" to "Invoice terms", "invoice_footer" to "Invoice footer", "receipt_footer" to "Receipt footer"),
    "Payment details on tickets" to listOf("pay_account_name" to "Account name", "pay_account_number" to "Account number", "pay_ifsc" to "IFSC",
        "pay_bank_name" to "Bank", "pay_branch" to "Branch", "pay_upi_number" to "UPI number", "pay_upi_apps" to "UPI apps"),
    "Backups" to listOf("auto_backup_hours" to "Automatic backup every (hours, 0 = off)", "backup_keep" to "Automatic backups to keep"))

@Composable
fun SettingsScreen(model: AppModel, onBack: () -> Unit) {
    val a = model.agency ?: return
    val s = remember { mutableStateMapOf<String, String>().apply { putAll(a.allSettings()) } }
    var saved by remember { mutableStateOf(false) }
    fun save() {
        val f = SETTING_GROUPS.flatMap { it.second }.associate { (k, _) -> k to (JValue.Str(s[k] ?: "") as JValue) }
        model.attempt(f, { ag, x -> ag.updateSettings(x) }) { r -> s.clear(); s.putAll(r); saved = true; model.changed() }
    }
    Screen("Company & documents", onBack, actions = {
        TextButton(onClick = { save() }, modifier = Modifier.testTag("save-settings")) { Text("Save", color = Color.White) }
    }) { pad ->
        Page(pad, "settings") {
            for ((group, fields) in SETTING_GROUPS) Section(group, footer = if (group.startsWith("Payment")) "Shown to customers on the ticket + payment PDF." else null) {
                for ((k, label) in fields) Field(label, s[k] ?: "", { s[k] = it; saved = false }, tag = "setting-$k", multiline = k in listOf("business_address", "invoice_terms"))
            }
            if (saved) Text("Saved.", color = Good)
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
        }
    }
}
