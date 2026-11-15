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
import androidx.compose.ui.graphics.drawscope.clipRect
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
    val reveal = rememberReveal(bars.map { it.value })
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
            // Bars grow out of the zero line when the chart appears.
            val tip = zeroY + (y(b.value) - zeroY) * reveal
            val top = minOf(tip, zeroY)
            val h = kotlin.math.abs(tip - zeroY).coerceAtLeast(if (b.value != 0L) 2.dp.toPx() else 0f)
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
    val reveal = rememberReveal(slices.map { it.value }, 800)
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
                val sweep = 360f * s.value.coerceAtLeast(0) / total * reveal
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
    val reveal = rememberReveal(values, 900)
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
        // Drawn in from the left when the chart appears.
        clipRect(right = size.width * reveal + 4.dp.toPx()) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)))
            drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
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

/** 0 → 1 once, when a chart first appears, so bars grow and arcs sweep in. Restarts when [key] changes. */
@Composable
fun rememberReveal(key: Any?, millis: Int = 650): Float {
    val a = androidx.compose.runtime.remember(key) { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(key) {
        a.animateTo(1f, androidx.compose.animation.core.tween(millis, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    return a.value
}

/** One end of a money flow: where money came from, or where it went. */
data class FlowNode(val label: String, val cents: Long, val color: Color)

/**
 * A flow diagram: [sources] on the left pour into [targets] on the right through ribbons as thick as the
 * amount. The two sides must add up to the same total. Tap a destination to select it.
 */
@Composable
fun FlowChart(
    sources: List<FlowNode>,
    targets: List<FlowNode>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    format: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val src = sources.filter { it.cents > 0 }
    val dst = targets.filter { it.cents > 0 }
    if (src.isEmpty() || dst.isEmpty()) return
    val p = Fin.palette
    val measurer = rememberTextMeasurer()
    val select by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(selected)
    val reveal = rememberReveal(src to dst, 800)
    val rowHeight = 26.dp
    val height = maxOf(210.dp, rowHeight * dst.size)
    val total = maxOf(src.sumOf { it.cents }, dst.sumOf { it.cents }).coerceAtLeast(1)

    Canvas(
        modifier.fillMaxWidth().height(height)
            .semantics { contentDescription = "Where the money went. Tap a destination for details." }
            .pointerInput(dst, total) {
                detectTapGestures { o ->
                    // The destination whose label is nearest the finger.
                    val line = 18.dp.toPx()
                    val tops = spreadLabels(flowLayout(dst, total, size.height.toFloat(), 3.dp.toPx()).map { (a, b) -> (a + b) / 2 }, line, size.height.toFloat())
                    val i = tops.indices.minByOrNull { kotlin.math.abs(tops[it] + line / 2 - o.y) } ?: return@detectTapGestures
                    select(if (i == current) null else i)
                }
            },
    ) {
        val bar = 10.dp.toPx()
        val gap = 3.dp.toPx()
        val h = size.height
        val left = flowLayout(src, total, h, gap)
        val right = flowLayout(dst, total, h, gap)

        // Split every destination across the sources in order, so ribbons never cross.
        var si = 0
        var sourceLeft = src[0].cents
        var sourceY = left[0].first
        dst.forEachIndexed { ti, t ->
            var need = t.cents
            var ty = right[ti].first
            val th = right[ti].second - right[ti].first
            while (need > 0 && si < src.size) {
                val take = minOf(need, sourceLeft)
                if (take <= 0) { si++; if (si < src.size) { sourceLeft = src[si].cents; sourceY = left[si].first }; continue }
                val sh = (left[si].second - left[si].first) * take / src[si].cents
                val dh = th * take / t.cents
                val x0 = bar
                val x1 = bar + (size.width - 2 * bar) * reveal
                val mid = (x0 + x1) / 2
                val path = Path().apply {
                    moveTo(x0, sourceY)
                    cubicTo(mid, sourceY, mid, ty, x1, ty)
                    lineTo(x1, ty + dh)
                    cubicTo(mid, ty + dh, mid, sourceY + sh, x0, sourceY + sh)
                    close()
                }
                val alpha = when {
                    selected == null -> 0.42f
                    selected == ti -> 0.75f
                    else -> 0.12f
                }
                drawPath(path, Brush.horizontalGradient(listOf(src[si].color.copy(alpha = alpha), t.color.copy(alpha = alpha)), startX = x0, endX = size.width - bar))
                sourceY += sh
                ty += dh
                need -= take
                sourceLeft -= take
            }
        }
        val radius = CornerRadius(3.dp.toPx())
        src.forEachIndexed { i, n -> drawRoundRect(n.color, Offset(0f, left[i].first), Size(bar, (left[i].second - left[i].first).coerceAtLeast(2f)), radius) }
        dst.forEachIndexed { i, n ->
            val a = if (selected != null && selected != i) DIMMED else 1f
            drawRoundRect(n.color.copy(alpha = a), Offset(size.width - bar, right[i].first), Size(bar, (right[i].second - right[i].first).coerceAtLeast(2f)), radius)
        }

        // Labels: sources beside the left bar, destinations in even rows beside the right bar.
        val small = TextStyle(fontSize = 12.sp, color = p.label)
        val faint = TextStyle(fontSize = 12.sp, color = p.secondaryLabel)
        src.forEachIndexed { i, n ->
            val t = measurer.measure(n.label + "\n" + format(n.cents), small.copy(fontWeight = FontWeight.SemiBold))
            val cy = (left[i].first + left[i].second) / 2
            drawText(t, topLeft = Offset(bar + 8.dp.toPx(), (cy - t.size.height / 2).coerceIn(0f, (h - t.size.height).coerceAtLeast(0f))))
        }
        // Each label sits beside its own bar; where bars are too thin for that, labels are nudged apart but keep their order.
        val line = 18.dp.toPx()
        val tops = spreadLabels(right.map { (a, b) -> (a + b) / 2 }, line, h)
        dst.forEachIndexed { i, n ->
            val on = selected == null || selected == i
            val t = measurer.measure(
                n.label + "  " + format(n.cents),
                if (on) small.copy(fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal) else faint.copy(color = p.tertiaryLabel),
            )
            val x = size.width - bar - 8.dp.toPx() - t.size.width
            val y = tops[i] + (line - t.size.height) / 2
            drawCircle(n.color.copy(alpha = if (on) 1f else DIMMED), 3.dp.toPx(), Offset(x - 8.dp.toPx(), tops[i] + line / 2))
            drawText(t, topLeft = Offset(x, y))
        }
    }
}

/** Top and bottom of each node's bar: heights in proportion to the amounts, with [gap] between bars. */
private fun flowLayout(nodes: List<FlowNode>, total: Long, height: Float, gap: Float): List<Pair<Float, Float>> {
    val usable = height - gap * (nodes.size - 1)
    var y = 0f
    return nodes.map { n ->
        val nh = usable * n.cents / total
        (y to y + nh).also { y += nh + gap }
    }
}

/**
 * Tops for one-line labels centred on [centers] (ascending), moved just enough that none overlap and all
 * stay inside [height]: pushed down past the one above, then pulled back up from the bottom edge.
 */
internal fun spreadLabels(centers: List<Float>, line: Float, height: Float): List<Float> {
    val tops = FloatArray(centers.size)
    for (i in centers.indices) {
        val wanted = centers[i] - line / 2
        tops[i] = maxOf(wanted, if (i == 0) 0f else tops[i - 1] + line)
    }
    for (i in centers.indices.reversed()) {
        val limit = if (i == centers.lastIndex) height - line else tops[i + 1] - line
        if (tops[i] > limit) tops[i] = limit
    }
    return tops.toList()
}

/** One day in a [MonthHeatmap]. */
data class HeatDay(val day: Int, val cents: Long)

/**
 * A month as a calendar, each day shaded by how much was spent. [firstWeekday] is 0 for Sunday. Tap a
 * day to select it.
 */
@Composable
fun MonthHeatmap(
    days: List<HeatDay>,
    firstWeekday: Int,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return
    val p = Fin.palette
    val measurer = rememberTextMeasurer()
    val select by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(selected)
    val reveal = rememberReveal(days, 500)
    val rows = (firstWeekday + days.size + 6) / 7
    val cell = 40.dp
    val header = 20.dp
    // The busiest ordinary day sets the scale; one huge day (rent) shouldn't wash out the rest.
    val sorted = days.map { it.cents }.filter { it > 0 }.sorted()
    val scale = (sorted.getOrNull((sorted.size * 9) / 10) ?: sorted.lastOrNull() ?: 1L).coerceAtLeast(1)

    Canvas(
        modifier.fillMaxWidth().height(header + cell * rows)
            .semantics { contentDescription = "Spending by day. Tap a day for details." }
            .pointerInput(days.size, firstWeekday) {
                detectTapGestures { o ->
                    val cw = size.width / 7f
                    val ch = (size.height - header.toPx()) / rows
                    if (o.y < header.toPx()) return@detectTapGestures
                    val col = (o.x / cw).toInt().coerceIn(0, 6)
                    val r = ((o.y - header.toPx()) / ch).toInt().coerceIn(0, rows - 1)
                    val day = r * 7 + col - firstWeekday + 1
                    if (day in 1..days.size) select(if (day == current) null else day) else select(null)
                }
            },
    ) {
        val cw = size.width / 7f
        val ch = (size.height - header.toPx()) / rows
        val side = minOf(cw, ch) - 6.dp.toPx()
        val names = listOf("S", "M", "T", "W", "T", "F", "S")
        names.forEachIndexed { i, n ->
            val t = measurer.measure(n, TextStyle(fontSize = 11.sp, color = p.tertiaryLabel))
            drawText(t, topLeft = Offset(cw * i + (cw - t.size.width) / 2, 0f))
        }
        days.forEach { d ->
            val idx = firstWeekday + d.day - 1
            val cx = cw * (idx % 7) + cw / 2
            val cy = header.toPx() + ch * (idx / 7) + ch / 2
            val strength = if (d.cents <= 0) 0f else (0.18f + 0.82f * (d.cents.toFloat() / scale).coerceAtMost(1f)) * reveal
            val fill = if (d.cents <= 0) p.cardSecondary else color.copy(alpha = strength)
            drawRoundRect(fill, Offset(cx - side / 2, cy - side / 2), Size(side, side), CornerRadius(side * 0.28f))
            if (selected == d.day) {
                drawRoundRect(p.label, Offset(cx - side / 2, cy - side / 2), Size(side, side), CornerRadius(side * 0.28f), style = Stroke(2.dp.toPx()))
            }
            val strong = strength > 0.55f
            val t = measurer.measure(
                d.day.toString(),
                TextStyle(fontSize = 12.sp, color = if (strong) Color.White else p.secondaryLabel, fontWeight = if (selected == d.day) FontWeight.Bold else FontWeight.Normal),
            )
            drawText(t, topLeft = Offset(cx - t.size.width / 2, cy - t.size.height / 2))
        }
    }
}
