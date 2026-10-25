package com.airzelo.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun Modifier.tag(tag: String?): Modifier = if (tag != null) this.testTag(tag) else this

// ---- tones (the desktop's badge classes)

enum class Tone { Gray, Blue, Green, Warn, Red, Violet, Cyan }

@Composable fun Tone.fg(): Color = Az.colors.let { when (this) { Tone.Gray -> it.muted; Tone.Blue -> it.royal; Tone.Green -> it.success
    Tone.Warn -> it.warn; Tone.Red -> it.danger; Tone.Violet -> it.violet; Tone.Cyan -> it.cyan } }
@Composable fun Tone.bg(): Color = Az.colors.let { when (this) { Tone.Gray -> it.surface3; Tone.Blue -> it.royal50; Tone.Green -> it.success50
    Tone.Warn -> it.warn50; Tone.Red -> it.danger50; Tone.Violet -> it.violet50; Tone.Cyan -> it.cyan50 } }

// ---- surfaces

/** A card: surface colour, hairline border, soft shadow, 18 dp corners. */
@Composable
fun AzCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, tag: String? = null, padding: Dp = 16.dp,
           content: @Composable ColumnScope.() -> Unit) {
    val c = Az.colors
    val m = modifier.shadow(if (c.dark) 0.dp else 3.dp, Az.cardShape, ambientColor = c.navy.copy(alpha = 0.05f), spotColor = c.navy.copy(alpha = 0.08f)).tag(tag)
    // only a card with an action is a button (for TalkBack and touch); a plain card is just a surface
    if (onClick != null) Surface(onClick = onClick, shape = Az.cardShape, color = c.surface, border = BorderStroke(1.dp, c.border), modifier = m) {
        Column(Modifier.padding(padding), content = content)
    } else Surface(shape = Az.cardShape, color = c.surface, border = BorderStroke(1.dp, c.border), modifier = m) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** The brand gradient card (dashboard hero, booking header). */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.clip(Az.cardShape).background(Az.colors.hero)) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
fun IconChip(icon: ImageVector, tone: Tone = Tone.Blue, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3.2f)).background(tone.bg()), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tone.fg(), modifier = Modifier.size(size * 0.5f))
    }
}

/** Initials in a circle, for people. */
@Composable
fun Avatar(name: String, tone: Tone = Tone.Blue, size: Dp = 40.dp) {
    val initials = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    Box(Modifier.size(size).clip(CircleShape).background(tone.bg()), contentAlignment = Alignment.Center) {
        Text(initials, color = tone.fg(), fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
    }
}

/** A pill: the desktop's status / payment badges. */
@Composable
fun Pill(text: String, tone: Tone, tag: String? = null) {
    Text(text, color = tone.fg(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(Az.pill).background(tone.bg()).padding(horizontal = 10.dp, vertical = 4.dp).tag(tag))
}

@Composable fun StatusPill(status: String, tag: String? = null) = Pill(Labels.status[status] ?: Labels.title(status), Labels.statusTone(status), tag)
@Composable fun PayPill(status: String, tag: String? = null) = Pill(Labels.pay[status] ?: Labels.title(status), Labels.payTone(status), tag)

// ---- text

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: String? = null, actionTag: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Az.colors.text, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction, modifier = Modifier.tag(actionTag)) { Text(action) }
    }
}

/** A small caps label above a value. */
@Composable fun Overline(text: String, color: Color = Az.colors.muted) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)

@Composable fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = Az.colors.muted, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/** Money in the desktop's format; [signed] colours negatives red and positives green. */
@Composable
fun Money(minor: Long, currency: String = "INR", style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleSmall,
          signed: Boolean = false, color: Color? = null, modifier: Modifier = Modifier) {
    val c = Az.colors
    Text(Fmt.money(minor, currency), style = style.copy(fontFeatureSettings = "tnum"), maxLines = 1, modifier = modifier,
        color = color ?: if (!signed) c.text else if (minor < 0) c.danger else if (minor > 0) c.success else c.text)
}

/** Label on the left, money on the right. */
@Composable
fun FigureRow(label: String, minor: Long, currency: String = "INR", strong: Boolean = false, tone: Color? = null, tag: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = if (strong) Az.colors.text else Az.colors.text2, modifier = Modifier.weight(1f))
        Money(minor, currency, if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, color = tone,
            modifier = Modifier.tag(tag))
    }
}

@Composable
fun LabeledValue(label: String, value: String, icon: ImageVector? = null) {
    if (value.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        if (icon != null) { Icon(icon, null, tint = Az.colors.muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Az.colors.muted, modifier = Modifier.width(118.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Az.colors.text, modifier = Modifier.weight(1f))
    }
}

// ---- rows

/** A list row: leading avatar / icon, title and subtitle, trailing content, optional chevron feel via ripple. */
@Composable
fun ListRow(title: String, subtitle: String = "", onClick: (() -> Unit)? = null, tag: String? = null, meta: String = "",
            leading: (@Composable () -> Unit)? = null, trailing: (@Composable ColumnScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
        .tag(tag).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Az.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Muted(subtitle, maxLines = 2)
            if (meta.isNotEmpty()) Muted(meta, maxLines = 1)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp), content = trailing) }
    }
}

@Composable fun RowDivider() = HorizontalDivider(Modifier.padding(start = 68.dp), color = Az.colors.border)

// ---- states

@Composable
fun EmptyState(icon: ImageVector = Icons.Rounded.Inbox, title: String, body: String = "", action: String? = null, modifier: Modifier = Modifier,
               onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IconChip(icon, Tone.Gray, 52.dp)
        Text(title, style = MaterialTheme.typography.titleSmall, color = Az.colors.text, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodySmall, color = Az.colors.muted, textAlign = TextAlign.Center)
        if (action != null && onAction != null) OutlinedButton(onClick = onAction, shape = Az.fieldShape) { Text(action) }
    }
}

@Composable
fun LoadingState(text: String = "Loading…") {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(color = Az.colors.royal, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
        Muted(text)
    }
}

/** A coloured notice strip (info, warning, success, error). */
@Composable
fun Banner(text: String, tone: Tone = Tone.Blue, icon: ImageVector? = null, tag: String? = null) {
    Row(Modifier.fillMaxWidth().clip(Az.fieldShape).background(tone.bg()).padding(12.dp).tag(tag), verticalAlignment = Alignment.Top) {
        if (icon != null) { Icon(icon, null, tint = tone.fg(), modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (tone == Tone.Gray) Az.colors.text2 else tone.fg())
    }
}

// ---- top bar

/** The app bar: title and optional subtitle on the page colour, back arrow on detail screens, actions on the right. */
@Composable
fun AzTopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val c = Az.colors
    Row(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding().padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
        .heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.text)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (onBack == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("screen-title"))
            if (!subtitle.isNullOrEmpty()) Muted(subtitle, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A screen: top bar, then [content] filling the rest on the page background. */
@Composable
fun AzScreen(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {},
             bottomBar: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Az.colors.bg)) {
        AzTopBar(title, subtitle, onBack, actions)
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        bottomBar()
    }
}

// ---- chips, search, period

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> FilterRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, tag: String? = null, contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp)) {
    val c = Az.colors
    LazyRow(contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().tag(tag)) {
        items(options, key = { it.second }) { (v, label) ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) }, shape = Az.pill,
                modifier = Modifier.tag(tag?.let { "$it-$label" }),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = if (c.dark) c.royal else c.navy, selectedLabelColor = Color.White,
                    containerColor = c.surface, labelColor = c.text2),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = v == selected, borderColor = c.border, selectedBorderColor = if (c.dark) c.royal else c.navy))
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, tag: String? = null, modifier: Modifier = Modifier) {
    val c = Az.colors
    OutlinedTextField(value = value, onValueChange = onChange, singleLine = true, shape = Az.pill,
        placeholder = { Text(placeholder, color = c.muted, maxLines = 1) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = c.muted) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear search", tint = c.muted) } },
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = c.surface, focusedContainerColor = c.surface,
            unfocusedBorderColor = c.border, focusedBorderColor = c.royal),
        modifier = modifier.fillMaxWidth().tag(tag))
}

// ---- form fields

@Composable
private fun fieldColors() = Az.colors.let { c ->
    OutlinedTextFieldDefaults.colors(unfocusedContainerColor = c.surface, focusedContainerColor = c.surface, disabledContainerColor = c.surface2,
        unfocusedBorderColor = c.border, focusedBorderColor = c.royal, unfocusedLabelColor = c.muted, focusedLabelColor = c.royal)
}

@Composable
fun AzField(label: String, value: String, onChange: (String) -> Unit, tag: String? = null, multiline: Boolean = false, caps: Boolean = false,
            keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, leading: ImageVector? = null, hint: String? = null,
            modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, readOnly: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = !multiline, minLines = if (multiline) 2 else 1,
        readOnly = readOnly, shape = Az.fieldShape, colors = fieldColors(),
        leadingIcon = leading?.let { { Icon(it, null, tint = Az.colors.muted) } }, trailingIcon = trailing,
        supportingText = hint?.let { { Text(it) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(capitalization = when { caps -> KeyboardCapitalization.Characters; keyboard == KeyboardType.Text -> KeyboardCapitalization.Sentences
            else -> KeyboardCapitalization.None }, keyboardType = if (secret) KeyboardType.Password else keyboard),
        modifier = modifier.fillMaxWidth().tag(tag))
}

@Composable
fun AmountField(label: String, value: String, onChange: (String) -> Unit, tag: String? = null, modifier: Modifier = Modifier, currency: String? = null) =
    AzField(label, value, { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }) }, tag, keyboard = KeyboardType.Decimal,
        modifier = modifier, trailing = currency?.let { { Text(it, color = Az.colors.muted, modifier = Modifier.padding(end = 12.dp)) } })

private fun isoToMillis(iso: String): Long? = runCatching { LocalDate.parse(iso.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
private fun millisToIso(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()

/** A date (YYYY-MM-DD): type it, or pick it from the calendar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, iso: String, onChange: (String) -> Unit, tag: String? = null, modifier: Modifier = Modifier, clearable: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    AzField(label, iso, onChange, tag, keyboard = KeyboardType.Number, hint = null, modifier = modifier,
        trailing = {
            Row {
                if (clearable && iso.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear $label", tint = Az.colors.muted) }
                IconButton(onClick = { open = true }, modifier = Modifier.tag(tag?.let { "$it-pick" })) { Icon(Icons.Rounded.CalendarMonth, "Pick $label", tint = Az.colors.royal) }
            }
        })
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = isoToMillis(iso) ?: isoToMillis(LocalDate.now().toString()))
        DatePickerDialog(onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onChange(millisToIso(it)) }; open = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }) { DatePicker(state) }
    }
}

/** A date and time (YYYY-MM-DDTHH:MM): type it, or pick the date then the time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(label: String, value: String, onChange: (String) -> Unit, tag: String? = null, modifier: Modifier = Modifier) {
    var step by remember { mutableStateOf(0) }  // 0 closed, 1 date, 2 time
    var date by remember { mutableStateOf("") }
    AzField(label, value, onChange, tag, keyboard = KeyboardType.Text, modifier = modifier,
        trailing = { IconButton(onClick = { step = 1 }) { Icon(Icons.Rounded.Schedule, "Pick $label", tint = Az.colors.royal) } })
    if (step == 1) {
        val state = rememberDatePickerState(initialSelectedDateMillis = isoToMillis(value) ?: isoToMillis(LocalDate.now().toString()))
        DatePickerDialog(onDismissRequest = { step = 0 },
            confirmButton = { TextButton(onClick = { date = state.selectedDateMillis?.let { millisToIso(it) } ?: ""; step = if (date.isEmpty()) 0 else 2 }) { Text("Next") } },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel") } }) { DatePicker(state) }
    }
    if (step == 2) {
        val h = value.drop(11).take(2).toIntOrNull() ?: 9
        val m = value.drop(14).take(2).toIntOrNull() ?: 0
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(onDismissRequest = { step = 0 }, title = { Text(label) }, text = { TimePicker(state) },
            confirmButton = { TextButton(onClick = { onChange(date + "T" + String.format(java.util.Locale.ROOT, "%02d:%02d", state.hour, state.minute)); step = 0 }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel") } })
    }
}

/** A dropdown of (value, label) options. */
@Composable
fun <T> Dropdown(label: String, selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit, tag: String? = null, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(value = options.firstOrNull { it.first == selected }?.second ?: "", onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(label) }, shape = Az.fieldShape, colors = fieldColors(),
            trailingIcon = { Icon(Icons.Rounded.ExpandMore, null, tint = Az.colors.muted) }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clip(Az.fieldShape).clickable(role = Role.DropdownList) { open = true }.tag(tag))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((v, l) in options) DropdownMenuItem(text = { Text(l, fontWeight = if (v == selected) FontWeight.SemiBold else null) },
                onClick = { onSelect(v); open = false }, modifier = Modifier.tag(tag?.let { "$it-option-$l" }))
        }
    }
}

/** A titled group of form fields on a card. */
@Composable
fun FormSection(title: String, icon: ImageVector? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    AzCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            if (icon != null) { IconChip(icon, Tone.Blue, 32.dp); Spacer(Modifier.width(10.dp)) }
            Text(title, style = MaterialTheme.typography.titleMedium, color = Az.colors.text)
        }
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        if (footer != null) { Spacer(Modifier.height(8.dp)); Muted(footer) }
    }
}

// ---- buttons

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, tag: String? = null, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, shape = Az.fieldShape, modifier = modifier.heightIn(min = 50.dp).tag(tag),
        colors = ButtonDefaults.buttonColors(containerColor = Az.colors.royal, contentColor = Color.White)) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, tag: String? = null,
                    danger: Boolean = false, enabled: Boolean = true) {
    val color = if (danger) Az.colors.danger else Az.colors.royal
    OutlinedButton(onClick = onClick, enabled = enabled, shape = Az.fieldShape, modifier = modifier.heightIn(min = 48.dp).tag(tag),
        border = BorderStroke(1.dp, if (danger) Az.colors.danger.copy(alpha = 0.5f) else Az.colors.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A round icon action with a caption (booking actions, quick actions). */
@Composable
fun ActionTile(label: String, icon: ImageVector, tone: Tone = Tone.Blue, tag: String? = null, onClick: () -> Unit) {
    Column(Modifier.width(84.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick).tag(tag).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(tone.bg()).border(1.dp, tone.fg().copy(alpha = 0.12f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center) { Icon(icon, null, tint = tone.fg(), modifier = Modifier.size(24.dp)) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Az.colors.text2, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 14.sp)
    }
}

// ---- dialogs

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true,
                  extra: @Composable (() -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, shape = RoundedCornerShape(24.dp), containerColor = Az.colors.surface,
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(message, color = Az.colors.text2); extra?.invoke() } },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm")) {
            Text(confirm, color = if (danger) Az.colors.danger else Az.colors.royal, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
