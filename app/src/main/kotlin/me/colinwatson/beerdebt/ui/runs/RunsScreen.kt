package me.colinwatson.beerdebt.ui.runs

import me.colinwatson.beerdebt.ui.LocalUnits
import me.colinwatson.beerdebt.ui.Units
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.colinwatson.beerdebt.BeerDebtApp
import me.colinwatson.beerdebt.engine.BalanceEngine
import me.colinwatson.beerdebt.engine.Report
import me.colinwatson.beerdebt.engine.RunStatement
import me.colinwatson.beerdebt.ui.Format
import me.colinwatson.beerdebt.ui.components.Card
import me.colinwatson.beerdebt.ui.components.ForestTopBar
import me.colinwatson.beerdebt.ui.components.Pill
import me.colinwatson.beerdebt.ui.components.Segmented
import me.colinwatson.beerdebt.ui.components.SwipeToDelete
import me.colinwatson.beerdebt.ui.theme.ForestBackground
import me.colinwatson.beerdebt.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.WeekFields
import java.util.Locale

enum class RunRange(val label: String, val caption: String) {
    WEEK("This Week", "this week"), MONTH("This Month", "this month"), ALL("All Time", "all time")
}

data class Bucket(val label: String, val miles: Double, val showsLabel: Boolean)

/** Runs: range picker, the total, a bar chart, and the runs as cards. */
@Composable
fun RunsScreen(onBack: () -> Unit) {
    val units = LocalUnits.current
    val app = LocalContext.current.applicationContext as BeerDebtApp
    val ledger by app.store.ledger.collectAsState()
    val now = Instant.now()
    val report = remember(ledger) { app.store.report(now) }
    var range by remember { mutableStateOf(RunRange.WEEK) }
    var toDelete by remember { mutableStateOf<RunStatement?>(null) }
    val runs = remember(report, range) { RunChart.runsIn(range, report, now) }
    val buckets = remember(runs, range) { RunChart.buckets(range, runs, now) }
    val total = runs.sumOf { it.paidMiles }

    ForestBackground {
        Scaffold(containerColor = Color.Transparent, topBar = { ForestTopBar(units.workoutsTitle, onBack) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Segmented(RunRange.entries, range, { it.label }, { range = it })
                        Spacer(Modifier.height(28.dp))
                        Text(units.amount(total), color = Palette.cream, fontSize = 44.sp, fontWeight = FontWeight.Black)
                        Text(range.caption, color = Palette.cream.copy(alpha = 0.7f), fontSize = 15.sp)
                        Spacer(Modifier.height(20.dp))
                        // In display units, so the axis reads in calories when the books do.
                        BarChart(buckets.map { it.copy(miles = units.value(it.miles)) }, Modifier.fillMaxWidth().height(190.dp))
                        Spacer(Modifier.height(20.dp))
                        Text("Recent ${units.workoutsTitle}", color = Palette.cream, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(start = 4.dp))
                    }
                }
                if (runs.isEmpty()) {
                    item { Card { Text(if (range == RunRange.ALL) "No ${units.workout}s yet. Health Connect will hand them over." else "No ${units.workout}s ${range.caption}.", color = Palette.cream.copy(alpha = 0.7f)) } }
                }
                items(runs, key = { it.id }) { statement ->
                    SwipeToDelete(onDelete = { toDelete = statement }) { Card { RunRow(statement) } }
                }
            }
        }
    }

    toDelete?.let { statement ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Take this ${units.workout} off the books?") },
            text = { Text("Whatever it paid goes back on your tab. It stays in Health Connect but won't count here again.") },
            confirmButton = { TextButton(onClick = { app.store.deleteRun(statement.id); toDelete = null }) { Text("Delete ${units.amount(statement.paidMiles)} ${WorkoutKind.of(statement.run).label.lowercase()}", color = Palette.debt) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun RunRow(statement: RunStatement) {
    val units = LocalUnits.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val kind = WorkoutKind.of(statement.run)
        Icon(kind.icon, contentDescription = kind.label, tint = Palette.cream, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(units.amount(statement.paidMiles), color = Palette.cream, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(subtitle(statement, kind, units), color = Palette.cream.copy(alpha = 0.65f), fontSize = 15.sp)
            breakdown(statement, units)?.let { Text(it, color = Palette.cream.copy(alpha = 0.5f), fontSize = 12.sp) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (statement.ignored) Pill("Ignored", Palette.cream.copy(alpha = 0.6f)) else Pill("Applied")
            statement.streakDayNumber?.let { Pill("🔥 Day $it", Palette.gold) }
        }
    }
}

/**
 * When it ended, and in calorie mode what it was: "Ride · Sep 9, 8:13 PM".
 * A workout that recorded no calories says so rather than reading as a zero
 * nobody can explain.
 */
private fun subtitle(s: RunStatement, kind: WorkoutKind, units: Units): String {
    val whenText = Format.dateTime(s.run.endedAt)
    if (!units.isCalories) return whenText
    if (s.run.isRunning) return "${kind.label} · ${Format.miles(s.run.distanceMiles)} · $whenText"
    if (s.run.activeEnergyKcal == null) return "${kind.label} · No calories recorded · $whenText"
    return "${kind.label} · $whenText"
}

private fun breakdown(s: RunStatement, units: Units): String? {
    val parts = buildList {
        if (s.debtPaidMiles > BalanceEngine.EPSILON) add("${units.amount(s.debtPaidMiles)} to debt")
        if (s.creditEarnedMiles > BalanceEngine.EPSILON) add("${units.amount(s.creditEarnedMiles)} banked")
        if (s.discardedMiles > BalanceEngine.EPSILON) add("${units.amount(s.discardedMiles)} over the cap")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Miles per day / day of month / month, for the chart. */
object RunChart {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun runsIn(range: RunRange, report: Report, now: Instant): List<RunStatement> {
        val counted = report.runs.filter { !it.ignored }
        val today = now.atZone(zone).toLocalDate()
        val selected = when (range) {
            RunRange.WEEK -> counted.filter { Format.sameCalendarWeek(it.run.endedAt, now) }
            RunRange.MONTH -> counted.filter { val d = it.run.endedAt.atZone(zone).toLocalDate(); d.year == today.year && d.month == today.month }
            RunRange.ALL -> counted
        }
        return selected.sortedByDescending { it.run.endedAt }
    }

    fun buckets(range: RunRange, runs: List<RunStatement>, now: Instant): List<Bucket> {
        val today = now.atZone(zone).toLocalDate()
        fun milesOn(day: LocalDate) = runs.filter { it.run.endedAt.atZone(zone).toLocalDate() == day }.sumOf { it.paidMiles }
        return when (range) {
            RunRange.WEEK -> {
                val first = today.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1)
                (0 until 7).map { i -> val d = first.plusDays(i.toLong()); Bucket(d.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()), milesOn(d), true) }
            }
            RunRange.MONTH -> {
                val ym = YearMonth.from(today)
                (1..ym.lengthOfMonth()).map { n -> Bucket("$n", milesOn(ym.atDay(n)), n == 1 || n % 5 == 0) }
            }
            RunRange.ALL -> (11 downTo 0).map { back ->
                val ym = YearMonth.from(today).minusMonths(back.toLong())
                val miles = runs.filter { YearMonth.from(it.run.endedAt.atZone(zone).toLocalDate()) == ym }.sumOf { it.paidMiles }
                Bucket(ym.month.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()), miles, back % 2 == 0)
            }
        }
    }
}

/** Bars with rounded tops, a stub on empty periods, right-hand axis, like the concept art. */
@Composable
fun BarChart(buckets: List<Bucket>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Palette.cream.copy(alpha = 0.75f), fontSize = 12.sp)
    val axisStyle = TextStyle(color = Palette.cream.copy(alpha = 0.55f), fontSize = 11.sp)
    Canvas(modifier) {
        if (buckets.isEmpty()) return@Canvas
        val axisWidth = 36.dp.toPx()
        val labelHeight = 22.dp.toPx()
        val plotWidth = size.width - axisWidth
        val plotHeight = size.height - labelHeight
        val yMax = maxOf(2.0, (buckets.maxOf { it.miles }) * 1.15)
        val gridSteps = if (yMax <= 2.5) listOf(0.0, 0.5, 1.0, 1.5, 2.0).filter { it <= yMax } else {
            // A round step (1, 2 or 5 × 10ⁿ), so a calorie axis reads 0, 50, 100 rather than 0, 31, 62.
            val raw = yMax / 4
            val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
            val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
            generateSequence(0.0) { it + step }.takeWhile { it <= yMax }.toList()
        }
        for (g in gridSteps) {
            val y = plotHeight - (g / yMax * plotHeight).toFloat()
            drawLine(Palette.cream.copy(alpha = 0.12f), Offset(0f, y), Offset(plotWidth, y), strokeWidth = 1f)
            val text = if (g == g.toLong().toDouble()) g.toLong().toString() else "%.1f".format(g)
            drawText(measurer, text, topLeft = Offset(plotWidth + 8.dp.toPx(), y - 7.dp.toPx()), style = axisStyle)
        }
        val slot = plotWidth / buckets.size
        val barWidth = slot * if (buckets.size > 12) 0.6f else 0.45f
        buckets.forEachIndexed { i, b ->
            val isStub = b.miles < BalanceEngine.EPSILON
            val value = if (isStub) yMax * 0.015 else b.miles
            val h = (value / yMax * plotHeight).toFloat()
            val x = i * slot + (slot - barWidth) / 2
            drawRoundRect(Palette.creditSoft.copy(alpha = if (isStub) 0.5f else 1f), topLeft = Offset(x, plotHeight - h), size = Size(barWidth, h), cornerRadius = CornerRadius(4.dp.toPx()))
            if (b.showsLabel) {
                val measured = measurer.measure(b.label, labelStyle)
                drawText(measured, topLeft = Offset(x + barWidth / 2 - measured.size.width / 2, plotHeight + 6.dp.toPx()))
            }
        }
    }
}
