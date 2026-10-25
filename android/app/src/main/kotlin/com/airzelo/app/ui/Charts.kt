package com.airzelo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airzelo.engine.JValue

/**
 * Revenue & profit, as on the desktop dashboard: net-sales bars with the gross-profit line, one bucket per day for
 * ranges up to 62 days and per month otherwise (Ledger.dashboard chart buckets).
 */
@Composable
fun RevenueChart(buckets: List<Map<String, JValue>>, currency: String, modifier: Modifier = Modifier) {
    val c = Az.colors
    val measurer = rememberTextMeasurer()
    val sales = buckets.map { it["sales"].num }
    val gp = buckets.map { it["gross_profit"].num }
    val labels = buckets.map { it["label"].text }
    val maxV = maxOf(1L, (sales + gp).maxOrNull() ?: 1L)
    val minV = minOf(0L, gp.minOrNull() ?: 0L)
    val axis = TextStyle(fontSize = 10.sp, color = c.muted)
    val summary = "Net sales ${Fmt.money(sales.sum(), currency)}, gross profit ${Fmt.money(gp.sum(), currency)} over ${buckets.size} periods"
    Canvas(modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = summary }) {
        val left = 44.dp.toPx(); val bottom = 22.dp.toPx(); val top = 8.dp.toPx()
        val w = size.width - left; val h = size.height - bottom - top
        val range = (maxV - minV).toFloat()
        fun y(v: Long) = top + h * (1f - (v - minV) / range)
        // grid and axis labels
        for (i in 0..3) {
            val v = minV + (maxV - minV) * i / 3
            val yy = y(v)
            drawLine(c.border, Offset(left, yy), Offset(size.width, yy), 1f, pathEffect = if (v == 0L) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val t = measurer.measure(Fmt.compact(v, currency), axis)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2))
        }
        val n = buckets.size.coerceAtLeast(1)
        val slot = w / n
        val barW = (slot * 0.56f).coerceAtMost(26.dp.toPx())
        val zero = y(0)
        sales.forEachIndexed { i, v ->
            if (v == 0L) return@forEachIndexed
            val x = left + slot * i + (slot - barW) / 2
            val yy = y(v)
            drawRoundRect(c.royal.copy(alpha = 0.85f), Offset(x, minOf(yy, zero)), Size(barW, Math.abs(zero - yy)), CornerRadius(4.dp.toPx()))
        }
        // gross-profit line
        val path = Path()
        gp.forEachIndexed { i, v ->
            val p = Offset(left + slot * i + slot / 2, y(v))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, c.success, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        gp.forEachIndexed { i, v -> if (n <= 31) drawCircle(c.success, 3.dp.toPx(), Offset(left + slot * i + slot / 2, y(v))) }
        // x labels: a few, evenly spaced
        val every = maxOf(1, n / 6)
        labels.forEachIndexed { i, l ->
            if (i % every != 0 && i != n - 1) return@forEachIndexed
            val t = measurer.measure(l, axis)
            val cx = (left + slot * i + slot / 2 - t.size.width / 2).coerceIn(left, size.width - t.size.width)
            drawText(t, topLeft = Offset(cx, size.height - t.size.height))
        }
    }
}

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((l, col) in items) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(col))
            Spacer(Modifier.width(6.dp))
            Text(l, style = MaterialTheme.typography.labelMedium, color = Az.colors.muted)
        }
    }
}

/** Horizontal bars with signed values (the desktop's profit breakdown). */
@Composable
fun BreakdownBars(parts: List<Pair<String, Long>>, currency: String) {
    val c = Az.colors
    val maxV = maxOf(1L, parts.maxOfOrNull { Math.abs(it.second) } ?: 1L)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((label, v) in parts) Column {
            Row(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.weight(1f))
                Money(v, currency, MaterialTheme.typography.labelLarge, color = if (v < 0) c.danger else c.text)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surface3)) {
                Box(Modifier.fillMaxWidth(Math.abs(v).toFloat() / maxV).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                    .background(if (v < 0) c.danger else c.royal))
            }
        }
    }
}

/** One stacked bar with a legend of counts (booking statuses, payment statuses). */
@Composable
fun DistributionBar(segments: List<Triple<String, Int, Tone>>) {
    val total = segments.sumOf { it.second }.coerceAtLeast(1)
    val shown = segments.filter { it.second > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Az.colors.surface3)) {
            for ((_, n, tone) in shown) Box(Modifier.weight(n.toFloat() / total).fillMaxHeight().background(tone.fg()))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, n, tone) in segments) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(tone.fg()))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = Az.colors.text2, modifier = Modifier.weight(1f))
                Text(n.toString(), style = MaterialTheme.typography.labelLarge, color = Az.colors.text)
            }
        }
    }
}

/** A gentle padding helper used by chart cards. */
fun Modifier.chartPadding() = this.padding(top = 4.dp)
