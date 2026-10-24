package com.airzelo.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.airzelo.engine.JValue
import com.airzelo.engine.fmt
import com.airzelo.engine.money
import java.io.File

// ---- theme and formatting

val Navy = Color(0xFF0A1A3F)
val Royal = Color(0xFF2350E6)
val Good = Color(0xFF0E9F6B)
val Bad = Color(0xFFD33447)
val Warn = Color(0xFFC27A06)
val Muted = Color(0xFF64738F)

@Composable
fun AirZeloTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Royal, secondary = Navy, error = Bad), content = content)
}

object Fmt {
    fun label(key: String): String = key.replace("_", " ").split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    fun amountText(minor: Long): String = if (minor == 0L) "" else fmt(minor)
    fun money(minor: Long, cur: String = "INR"): String = com.airzelo.engine.money(minor, cur)
}

/** Text of a JSON value for screens (null shows as empty). */
val JValue?.text: String get() = if (this == null || isNull) "" else py
val JValue?.num: Long get() = this?.long ?: 0

// ---- layout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.testTag("back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { actions() },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White,
                navigationIconContentColor = Color.White, actionIconContentColor = Color.White))
    }) { content(it) }
}

/** A scrolling page (not lazy: every field always exists, so nothing is lost while scrolling). */
@Composable
fun Page(padding: PaddingValues, tag: String = "page", content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun Section(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (title != null) Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F8FD)), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
        if (footer != null) Text(footer, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun FigureRow(label: String, minor: Long, currency: String = "INR", strong: Boolean = false, tag: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (strong) Color.Unspecified else Muted, fontWeight = if (strong) FontWeight.Bold else null, modifier = Modifier.weight(1f))
        Text(Fmt.money(minor, currency), fontWeight = if (strong) FontWeight.Bold else null, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

@Composable
fun LabeledValue(label: String, value: String) {
    if (value.isEmpty()) return
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Muted, modifier = Modifier.width(130.dp))
        Text(value, modifier = Modifier.weight(1f))
    }
}

@Composable
fun MoneyText(minor: Long, currency: String = "INR", colored: Boolean = false, small: Boolean = false) {
    Text(Fmt.money(minor, currency), fontSize = if (small) 12.sp else 14.sp,
        color = if (!colored) Color.Unspecified else if (minor > 0) Bad else Good)
}

private val STATUS_COLORS = mapOf("ticketed" to Good, "confirmed" to Royal, "paid" to Good, "settled" to Good, "issued" to Good,
    "partial" to Warn, "advance" to Warn, "enquiry" to Warn, "on_hold" to Warn, "unpaid" to Bad, "cancelled" to Bad, "refunded" to Bad,
    "refund_due" to Bad, "overpaid" to Warn, "not_billed" to Muted)

@Composable
fun StatusBadge(status: String, tag: String? = null) {
    val c = STATUS_COLORS[status] ?: Muted
    Text(Fmt.label(status), color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(c.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
            .let { if (tag != null) it.testTag(tag) else it })
}

@Composable
fun ListRow(onClick: (() -> Unit)?, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.let { if (tag != null) it.testTag(tag) else it }
        .padding(horizontal = 16.dp, vertical = 10.dp), content = content)
    HorizontalDivider()
}

@Composable
fun ActionButton(label: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label) }
}

// ---- inputs

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, tag: String? = null, multiline: Boolean = false,
          caps: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = !multiline,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences,
            keyboardType = if (secret) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it })
}

@Composable
fun AmountField(label: String, value: String, onChange: (String) -> Unit, tag: String? = null) =
    Field(label, value, onChange, tag, keyboard = KeyboardType.Decimal)

/** A dropdown of (value, label) options. */
@Composable
fun <T> Picker(label: String, selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit, tag: String? = null) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(value = options.firstOrNull { it.first == selected }?.second ?: "", onValueChange = {}, readOnly = true,
            label = { Text(label) }, trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable { open = true }.let { if (tag != null) it.testTag(tag) else it })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((v, l) in options) DropdownMenuItem(text = { Text(l) }, onClick = { onSelect(v); open = false },
                modifier = if (tag != null) Modifier.testTag("$tag-option-$l") else Modifier)
        }
    }
}

// ---- dialogs

@Composable
fun ProblemDialog(model: AppModel) {
    val p = model.problem ?: return
    AlertDialog(onDismissRequest = { model.problem = null }, title = { Text(p.title) }, text = { Text(p.message, modifier = Modifier.testTag("problem-message")) },
        confirmButton = {
            if (p.retry != null) TextButton(onClick = { model.problem = null; p.retry.invoke() }, modifier = Modifier.testTag("save-anyway")) { Text("Save anyway") }
            else TextButton(onClick = { model.problem = null }, modifier = Modifier.testTag("problem-ok")) { Text("OK") }
        },
        dismissButton = { if (p.retry != null) TextButton(onClick = { model.problem = null }) { Text("Cancel") } })
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, extra: @Composable (() -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(message); extra?.invoke() } },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm")) { Text(confirm, color = Bad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun Note(text: String, color: Color = Muted) { Text(text, fontSize = 12.sp, color = color) }

@Composable
fun Gap() { Spacer(Modifier.padding(4.dp)) }

// ---- sharing (files in the app cache, shared through the FileProvider)

object Share {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "shared").also { it.mkdirs() }

    /** Writes [data] to the share folder (old shared files are removed first: they can hold customer data). */
    fun file(ctx: Context, name: String, data: ByteArray): File {
        val d = dir(ctx)
        d.listFiles()?.forEach { it.delete() }
        return File(d, name.replace(Regex("[^A-Za-z0-9._+-]"), "_")).also { it.writeBytes(data) }
    }

    fun send(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        MainActivity.expectingExternal = true
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
