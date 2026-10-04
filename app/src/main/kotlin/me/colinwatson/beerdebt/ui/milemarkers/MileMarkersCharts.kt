package me.colinwatson.beerdebt.ui.milemarkers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.colinwatson.beerdebt.ui.Format
import me.colinwatson.beerdebt.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

private val AXIS_WIDTH = 42.dp
private val LABEL_HEIGHT = 20.dp

/**
 * Where a value sits in the plot, 0 at the bottom and 1 at the top. Log when
 * the readings span orders of magnitude: the default rules compound at 10% a
 * day, and on a linear axis an exponential tab is a flat line followed by a
 * wall.
 */
internal class VScale(val lo: Double, val hi: Double, val log: Boolean) {
    fun fraction(value: Double): Float {
        val v = value.coerceIn(lo, hi)
        if (!log) return ((v - lo) / (hi - lo)).toFloat()
        return (ln(v / lo) / ln(hi / lo)).toFloat()
    }
}

/**
 * The tab over time: total owed as a filled line, with principal dashed
 * underneath, so the space between the two *is* the interest. Not a stacked
 * area -- within a couple of months interest dwarfs principal and a stack
 * buries the gold band against the axis.
 *
 * Streak stretches are washed in faint gold behind the curve and freezes in
 * frost, with a marker lane along the top: a flame per streak worth pointing
 * at, a snowflake per freeze on the row below.
 */
@Composable
fun PositionChart(
    points: List<MileMarkersReport.Point>,
    spans: List<MileMarkersReport.Span>,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Palette.cream.copy(alpha = 0.55f), fontSize = 11.sp)
    val dateFormat = rememberDayFormatter(zone)
    val scale = scaleFor(points, spans)
    val markers = spans.filter { it.isMarked }

    BoxWithConstraints(modifier) {
        val plotW = maxWidth - AXIS_WIDTH
        Canvas(Modifier.fillMaxSize()) {
            if (points.size < 2) return@Canvas
            val axis = AXIS_WIDTH.toPx()
            val labels = LABEL_HEIGHT.toPx()
            val w = size.width - axis
            val h = size.height - labels
            val t0 = points.first().date.toEpochMilli().toDouble()
            val t1 = points.last().date.toEpochMilli().toDouble()
            fun x(at: Instant) = (((at.toEpochMilli() - t0) / max(1.0, t1 - t0)) * w).toFloat()
            fun y(v: Double) = h - scale.fraction(v) * h

            // Washes first, clipped to the plot by construction.
            for (span in spans) {
                val x0 = x(span.start).coerceIn(0f, w)
                val x1 = x(span.end).coerceIn(0f, w)
                val frozen = span.kind == MileMarkersReport.Span.Kind.FROZEN
                val width = max(if (frozen) 2.5f else 1f, x1 - x0)
                drawRect(
                    if (frozen) Palette.frost.copy(alpha = 0.20f) else Palette.gold.copy(alpha = 0.06f),
                    topLeft = Offset(x0, 0f), size = Size(min(width, w - x0), h),
                )
            }

            gridAndAxis(scale, w, h, measurer, axisStyle)

            // Owed: filled area under a solid line.
            val owed = Path().apply {
                moveTo(x(points.first().date), y(points.first().debtMiles))
                points.drop(1).forEach { lineTo(x(it.date), y(it.debtMiles)) }
            }
            val fill = Path().apply {
                addPath(owed)
                lineTo(x(points.last().date), h)
                lineTo(x(points.first().date), h)
                close()
            }
            drawPath(fill, Brush.verticalGradient(
                listOf(Palette.debt.copy(alpha = 0.45f), Palette.debt.copy(alpha = 0.05f)),
            ))
            drawPath(owed, Palette.debt, style = Stroke(width = 2.5.dp.toPx()))

            // Principal underneath: the gap up to the red line is the interest.
            val principal = Path().apply {
                moveTo(x(points.first().date), y(points.first().principalMiles))
                points.drop(1).forEach { lineTo(x(it.date), y(it.principalMiles)) }
            }
            drawPath(
                principal, Palette.gold,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                ),
            )

            // Date labels at the ends and the middle.
            listOf(0, points.size / 2, points.size - 1).distinct().forEach { i ->
                val label = dateFormat.format(points[i].date.atZone(zone))
                val measured = measurer.measure(label, axisStyle)
                val cx = (x(points[i].date) - measured.size.width / 2f)
                    .coerceIn(0f, w - measured.size.width)
                drawText(measured, topLeft = Offset(cx, h + 4.dp.toPx()))
            }
        }

        // The marker lane rides over the plot as real icons; drawing vectors
        // into the Canvas would mean hand-rolling the paths.
        if (points.size >= 2) {
            val t0 = points.first().date.toEpochMilli().toDouble()
            val t1 = points.last().date.toEpochMilli().toDouble()
            for (marker in markers) {
                val f = ((marker.markerDate.toEpochMilli() - t0) / max(1.0, t1 - t0)).toFloat()
                if (f < 0f || f > 1f) continue
                val frozen = marker.kind == MileMarkersReport.Span.Kind.FROZEN
                Icon(
                    imageVector = if (frozen) Icons.Filled.AcUnit else Icons.Filled.LocalFireDepartment,
                    contentDescription = null,
                    tint = if (frozen) Palette.frost else Palette.gold,
                    modifier = Modifier
                        .offset(
                            x = plotW * f - 6.dp,
                            y = 2.dp + (marker.markerRow * 14).dp,
                        )
                        .size(12.dp),
                )
            }
        }
    }
}

/**
 * Headroom has to be reserved in the scale's own space. On a log axis a plain
 * multiplier buys almost nothing -- x1.5 on a range spanning three decades is a
 * couple of pixels -- so the ceiling is raised by the span itself, raised to
 * the share of the height being reserved for the marker lane.
 */
internal fun scaleFor(points: List<MileMarkersReport.Point>, spans: List<MileMarkersReport.Span>): VScale {
    val values = points.map { it.debtMiles }.filter { it > 0 }
    val high = values.maxOrNull() ?: 0.0
    val low = values.minOrNull() ?: 0.0
    if (high <= 0.0) return VScale(0.0, 1.0, log = false)
    val log = low > 0 && high / low > 100
    val headroom = when {
        spans.isEmpty() -> 0.04
        spans.any { it.kind == MileMarkersReport.Span.Kind.FROZEN } -> 0.22
        else -> 0.16
    }
    if (!log) return VScale(0.0, niceCeiling(max(1.0, high * (1 + headroom))), log = false)
    val lo = max(0.1, low * 0.8)
    return VScale(lo, high * Math.pow(high / lo, headroom / (1 - headroom)), log = true)
}

/** 1, 2, 2.5 or 5 x a power of ten, so axis labels land on round numbers. */
internal fun niceCeiling(value: Double): Double {
    if (value <= 0) return 1.0
    val base = Math.pow(10.0, floor(log10(value)))
    val n = value / base
    val nice = when {
        n <= 1 -> 1.0
        n <= 2 -> 2.0
        n <= 2.5 -> 2.5
        n <= 5 -> 5.0
        else -> 10.0
    }
    return nice * base
}

private fun DrawScope.gridAndAxis(
    scale: VScale, w: Float, h: Float,
    measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle,
) {
    val steps = if (scale.log) {
        generateSequence(1.0) { it * 10 }.takeWhile { it <= scale.hi }.filter { it >= scale.lo }.toList()
    } else {
        (0..4).map { scale.lo + (scale.hi - scale.lo) * it / 4 }
    }
    for (v in steps) {
        val y = h - scale.fraction(v) * h
        drawLine(Palette.cream.copy(alpha = 0.12f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        drawText(measurer, Format.compact(v), topLeft = Offset(w + 6.dp.toPx(), y - 7.dp.toPx()), style = style)
    }
}

/**
 * A flow, as bars under the position chart -- the price-and-volume pairing.
 * Runs and beers get a strip each: one is miles, the other is a count, and
 * putting them on one axis would be a lie about the units.
 */
@Composable
fun FlowChart(
    points: List<MileMarkersReport.Point>,
    value: (MileMarkersReport.Point) -> Double,
    colour: Color,
    modifier: Modifier = Modifier,
    /** A count is not miles, so the caller says how its axis reads. */
    axisLabel: (Double) -> String = { Format.compact(it) },
) {
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Palette.cream.copy(alpha = 0.5f), fontSize = 10.sp)
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val axis = AXIS_WIDTH.toPx()
        val w = size.width - axis
        val h = size.height
        val top = points.maxOf(value).coerceAtLeast(1.0)
        drawLine(Palette.cream.copy(alpha = 0.10f), Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1f)
        drawText(measurer, axisLabel(top), topLeft = Offset(w + 6.dp.toPx(), 0f), style = axisStyle)
        drawText(measurer, "0", topLeft = Offset(w + 6.dp.toPx(), h - 14.dp.toPx()), style = axisStyle)
        val slot = w / points.size
        val barWidth = max(2f, slot * 0.5f)
        points.forEachIndexed { i, p ->
            val v = value(p)
            if (v <= 0) return@forEachIndexed
            val barHeight = (v / top * h).toFloat()
            drawRoundRect(
                colour,
                topLeft = Offset(i * slot + (slot - barWidth) / 2, h - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
        }
    }
}

/**
 * Are you outpacing it? Two cumulative lines: miles the beers put on the tab,
 * and miles actually run. The gap between them is the whole app.
 */
@Composable
fun OutpacingChart(points: List<MileMarkersReport.Point>, zone: ZoneId, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Palette.cream.copy(alpha = 0.55f), fontSize = 11.sp)
    val dateFormat = rememberDayFormatter(zone)
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val axis = AXIS_WIDTH.toPx()
        val labels = LABEL_HEIGHT.toPx()
        val w = size.width - axis
        val h = size.height - labels
        val top = niceCeiling(max(1.0, max(points.maxOf { it.cumulativeBeerMiles }, points.maxOf { it.cumulativeRunMiles }) * 1.05))
        val t0 = points.first().date.toEpochMilli().toDouble()
        val t1 = points.last().date.toEpochMilli().toDouble()
        fun x(at: Instant) = (((at.toEpochMilli() - t0) / max(1.0, t1 - t0)) * w).toFloat()
        fun y(v: Double) = (h - v / top * h).toFloat()

        for (i in 0..4) {
            val v = top * i / 4
            drawLine(Palette.cream.copy(alpha = 0.12f), Offset(0f, y(v)), Offset(w, y(v)), strokeWidth = 1f)
            drawText(measurer, Format.compact(v), topLeft = Offset(w + 6.dp.toPx(), y(v) - 7.dp.toPx()), style = axisStyle)
        }
        fun line(of: (MileMarkersReport.Point) -> Double, colour: Color, width: Float) {
            val path = Path().apply {
                moveTo(x(points.first().date), y(of(points.first())))
                points.drop(1).forEach { lineTo(x(it.date), y(of(it))) }
            }
            drawPath(path, colour, style = Stroke(width = width))
        }
        line({ it.cumulativeBeerMiles }, Palette.caution, 2.dp.toPx())
        line({ it.cumulativeRunMiles }, Palette.credit, 2.5.dp.toPx())

        listOf(0, points.size / 2, points.size - 1).distinct().forEach { i ->
            val label = dateFormat.format(points[i].date.atZone(zone))
            val measured = measurer.measure(label, axisStyle)
            val cx = (x(points[i].date) - measured.size.width / 2f).coerceIn(0f, w - measured.size.width)
            drawText(measured, topLeft = Offset(cx, h + 4.dp.toPx()))
        }
    }
}

/**
 * Running days as a contribution grid: a column per week, a cell per day. Warm
 * where you ran, frost where a freeze covered the day, dark where nothing
 * happened -- the streak's own palette (spec §25.1).
 */
@Composable
fun StreakGrid(days: List<MileMarkersReport.Day>, modifier: Modifier = Modifier) {
    // A year of columns is as much as a phone can show without the cells
    // becoming specks; beyond that the grid shows the most recent year.
    val maxWeeks = 53
    val weeks = rememberWeeks(days, maxWeeks)
    if (weeks.isEmpty()) return
    BoxWithConstraints(modifier) {
        val spacing = if (weeks.size > 30) 1.5.dp else 3.dp
        val side = ((maxWidth - spacing * (weeks.size - 1)) / weeks.size).coerceIn(3.dp, 16.dp)
        Row(Modifier.fillMaxWidth()) {
            weeks.forEachIndexed { i, week ->
                Column {
                    week.forEachIndexed { j, day ->
                        Box(
                            Modifier
                                .size(side)
                                .background(cellColour(day), RoundedCornerShape(if (side > 8.dp) 3.dp else 1.5.dp)),
                        )
                        if (j < 6) Box(Modifier.height(spacing))
                    }
                }
                if (i < weeks.size - 1) Box(Modifier.size(spacing))
            }
        }
    }
}

private fun cellColour(day: MileMarkersReport.Day?): Color = when {
    day == null -> Color.Transparent
    day.frozen -> Palette.frost.copy(alpha = 0.85f)
    !day.qualifies -> if (day.miles > 0.05) Palette.creditSoft.copy(alpha = 0.28f) else Palette.cream.copy(alpha = 0.07f)
    day.miles >= 4 -> Palette.credit
    day.miles >= 2 -> Palette.credit.copy(alpha = 0.75f)
    else -> Palette.creditSoft.copy(alpha = 0.55f)
}

@Composable
private fun rememberDayFormatter(zone: ZoneId): DateTimeFormatter =
    androidx.compose.runtime.remember(zone) { DateTimeFormatter.ofPattern("MMM d") }

/** Columns of seven, weekday-aligned, most recent [maxWeeks] kept. */
@Composable
private fun rememberWeeks(days: List<MileMarkersReport.Day>, maxWeeks: Int): List<List<MileMarkersReport.Day?>> =
    androidx.compose.runtime.remember(days, maxWeeks) {
        if (days.isEmpty()) return@remember emptyList()
        val leading = (days.first().date.dayOfWeek.value % 7)
        val cells = ArrayList<MileMarkersReport.Day?>(leading + days.size + 7)
        repeat(leading) { cells += null }
        cells += days
        while (cells.size % 7 != 0) cells += null
        cells.chunked(7).takeLast(maxWeeks)
    }
