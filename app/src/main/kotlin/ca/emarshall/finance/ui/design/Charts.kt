package ca.emarshall.finance.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.emarshall.finance.ui.theme.Fin
import kotlin.math.atan2
import kotlin.math.hypot

/*
 * Small hand-drawn charts that answer taps. Every chart takes a `selected` index and an `onSelect`
 * callback; the screen owns the selection and shows the details for it. Tapping the selected item
 * again (or empty space) clears it. Values are cents (Long); they are only turned into pixels here.
 */

private const val DIMMED = 0.32f

/** One column. [faded] draws it lighter (e.g. a month that isn't final yet). */
data class BarDatum(val label: String, val value: Long, val color: Color, val faded: Boolean = false)

/**
 * Columns that can go below zero, with labels underneath. Tap a column, or drag across them, to select.
 * [reference] draws a dashed line (e.g. the average).
 */
@Composable
fun BarChart(
    bars: List<BarDatum>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    reference: Long? = null,
    description: String = "Bar chart. Tap a bar for details.",
) {
    if (bars.isEmpty()) return
    val p = Fin.palette
    val measurer = rememberTextMeasurer()
    val select by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(selected)
    val n = bars.size
    fun indexAt(x: Float, width: Int) = (x / (width.toFloat() / n)).toInt().coerceIn(0, n - 1)
    Canvas(
        modifier.fillMaxWidth().height(height)
            .semantics { contentDescription = description }
            .pointerInput(n) {
                detectTapGestures { o -> val i = indexAt(o.x, size.width); select(if (i == current) null else i) }
            }
            .pointerInput(n) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> select(indexAt(o.x, size.width)) },
                    onHorizontalDrag = { change, _ -> select(indexAt(change.position.x, size.width)) },
                )
            },
    ) {
        val labelArea = 22.dp.toPx()
        val plotH = size.height - labelArea
        val maxV = maxOf(bars.maxOf { it.value }, reference ?: 0L, 0L)
        val minV = minOf(bars.minOf { it.value }, reference ?: 0L, 0L)
        val range = (maxV - minV).coerceAtLeast(1L).toFloat()
        fun y(v: Long) = plotH * (maxV - v).toFloat() / range
        val zeroY = y(0)
        val slot = size.width / n
        val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        val radius = CornerRadius(minOf(6.dp.toPx(), barW / 2))

        drawLine(p.separator, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1.dp.toPx())
        bars.forEachIndexed { i, b ->
            val left = slot * i + (slot - barW) / 2
            val top = minOf(y(b.value), zeroY)
            val h = kotlin.math.abs(y(b.value) - zeroY).coerceAtLeast(if (b.value != 0L) 2.dp.toPx() else 0f)
            val alpha = when {
                selected != null && selected != i -> DIMMED
                b.faded -> 0.55f
                else -> 1f
            }
            if (h > 0f) drawRoundRect(b.color.copy(alpha = alpha), Offset(left, top), Size(barW, h), radius)
            val style = TextStyle(
                fontSize = 11.sp,
                color = if (selected == i) p.label else p.secondaryLabel,
                fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal,
            )
            val text = measurer.measure(b.label, style)
            drawText(text, topLeft = Offset(slot * i + (slot - text.size.width) / 2, plotH + 6.dp.toPx()))
        }
        reference?.let { r ->
            val ry = y(r)
            drawLine(
                p.secondaryLabel.copy(alpha = 0.7f), Offset(0f, ry), Offset(size.width, ry), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }
    }
}

/** One slice or segment. */
data class ChartSlice(val label: String, val value: Long, val color: Color)

/**
 * A donut. Tap a slice to select it (it thickens, the rest dim); tap the hole to clear. [center] is drawn
 * in the hole, usually the selected slice's numbers.
 */
@Composable
fun DonutChart(
    slices: List<ChartSlice>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 210.dp,
    thickness: Dp = 28.dp,
    center: @Composable () -> Unit = {},
) {
    val p = Fin.palette
    val select by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(selected)
    val total = slices.sumOf { it.value.coerceAtLeast(0) }.coerceAtLeast(1)
    val grow by animateFloatAsState(if (selected != null) 1f else 0f, label = "donut")
    Box(modifier.fillMaxWidth().height(diameter), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(
            Modifier.height(diameter).fillMaxWidth()
                .semantics { contentDescription = "Donut chart. Tap a slice for details." }
                .pointerInput(slices) {
                    detectTapGestures { o ->
                        val d = minOf(size.width, size.height).toFloat()
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r = hypot(o.x - cx, o.y - cy)
                        val outer = d / 2
                        val inner = outer - thickness.toPx() - 10.dp.toPx()
                        if (r < inner || r > outer + 8.dp.toPx()) { select(null); return@detectTapGestures }
                        // Angle clockwise from 12 o'clock, 0..360.
                        val deg = ((Math.toDegrees(atan2((o.y - cy).toDouble(), (o.x - cx).toDouble())) + 90 + 360) % 360).toFloat()
                        var acc = 0f
                        val hit = slices.indexOfFirst { s -> acc += 360f * s.value.coerceAtLeast(0) / total; deg < acc }
                        val i = if (hit < 0) slices.lastIndex else hit
                        select(if (i == current) null else i)
                    }
                },
        ) {
            val extra = 8.dp.toPx()
            val stroke = thickness.toPx()
            val d = minOf(size.width, size.height)
            val inset = (stroke + extra) / 2
            val arc = Size(d - 2 * inset, d - 2 * inset)
            val topLeft = Offset((size.width - d) / 2 + inset, (size.height - d) / 2 + inset)
            drawArc(p.cardSecondary, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            val gap = if (slices.size > 1) 1.6f else 0f
            var start = -90f
            slices.forEachIndexed { i, s ->
                val sweep = 360f * s.value.coerceAtLeast(0) / total
                val isSel = selected == i
                val alpha = if (selected != null && !isSel) DIMMED else 1f
                val w = if (isSel) stroke + extra * grow else stroke
                if (sweep > gap) drawArc(s.color.copy(alpha = alpha), start + gap / 2, sweep - gap, false, topLeft, arc, style = Stroke(w, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        center()
    }
}

/** A line over time. Tap or drag to put a marker on a point. */
@Composable
fun LineChart(
    values: List<Long>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 110.dp,
) {
    if (values.size < 2) return
    val p = Fin.palette
    val select by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(selected)
    val n = values.size
    fun indexAt(x: Float, width: Int) = ((x / width) * (n - 1) + 0.5f).toInt().coerceIn(0, n - 1)
    Canvas(
        modifier.fillMaxWidth().height(height)
            .semantics { contentDescription = "Line chart. Tap a point for its value." }
            .pointerInput(n) { detectTapGestures { o -> val i = indexAt(o.x, size.width); select(if (i == current) null else i) } }
            .pointerInput(n) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> select(indexAt(o.x, size.width)) },
                    onHorizontalDrag = { change, _ -> select(indexAt(change.position.x, size.width)) },
                )
            },
    ) {
        val pad = 6.dp.toPx()
        val maxV = values.max()
        val minV = values.min()
        val range = (maxV - minV).coerceAtLeast(1L).toFloat()
        fun pt(i: Int) = Offset(size.width * i / (n - 1), pad + (size.height - 2 * pad) * (maxV - values[i]).toFloat() / range)
        val line = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until n) lineTo(pt(i).x, pt(i).y) }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        selected?.takeIf { it in 0 until n }?.let { i ->
            val o = pt(i)
            drawLine(p.secondaryLabel.copy(alpha = 0.5f), Offset(o.x, 0f), Offset(o.x, size.height), strokeWidth = 1.dp.toPx())
            drawCircle(p.card, 6.dp.toPx(), o)
            drawCircle(color, 4.dp.toPx(), o)
        }
    }
}

/** A horizontal bar split into segments (allocation, where money sits). Tap a segment to select it. */
@Composable
fun SegmentedBar(
    segments: List<ChartSlice>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    val total = segments.sumOf { it.value.coerceAtLeast(0) }.coerceAtLeast(1)
    // A taller hit area than the bar itself, so thin segments are still easy to tap.
    Box(modifier.fillMaxWidth().height(height + 16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Row(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            segments.forEachIndexed { i, s ->
                if (s.value > 0) Box(
                    Modifier.weight(s.value.toFloat() / total).fillMaxHeight()
                        .background(s.color.copy(alpha = if (selected != null && selected != i) DIMMED else 1f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(height + 16.dp)) {
            segments.forEachIndexed { i, s ->
                if (s.value > 0) Box(
                    Modifier.weight(s.value.toFloat() / total).fillMaxHeight()
                        .semantics { contentDescription = s.label }
                        .clickable { onSelect(if (selected == i) null else i) },
                )
            }
        }
    }
}
