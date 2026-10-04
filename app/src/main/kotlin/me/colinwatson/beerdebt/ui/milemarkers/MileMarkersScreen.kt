package me.colinwatson.beerdebt.ui.milemarkers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.SportsBar
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.colinwatson.beerdebt.BeerDebtApp
import me.colinwatson.beerdebt.ui.Format
import me.colinwatson.beerdebt.ui.components.Card
import me.colinwatson.beerdebt.ui.components.ForestTopBar
import me.colinwatson.beerdebt.ui.components.Segmented
import me.colinwatson.beerdebt.ui.theme.ForestBackground
import me.colinwatson.beerdebt.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Mile Markers": the whole history in one screen -- where the tab has been,
 * what the interest cost, whether the running is keeping up, and the records
 * behind it. A port of the Swift `MileMarkersView` (spec §27).
 *
 * Everything here is read-only and derived. The build runs on [Dispatchers.Default]
 * because the balance curve costs one engine replay per sampled point; the
 * result is cached in state and rebuilt only when the range or the ledger
 * changes, never during composition.
 */
@Composable
fun MileMarkersScreen(onBack: () -> Unit, initialRange: MileMarkersRange = MileMarkersRange.QUARTER) {
    val app = LocalContext.current.applicationContext as BeerDebtApp
    val ledger by app.store.ledger.collectAsState()
    val zone = remember { ZoneId.systemDefault() }
    var range by remember { mutableStateOf(initialRange) }
    var report by remember { mutableStateOf<MileMarkersReport?>(null) }
    var building by remember { mutableStateOf(true) }

    LaunchedEffect(range, ledger) {
        building = true
        report = withContext(Dispatchers.Default) {
            MileMarkersStats.build(ledger, range, Instant.now(), zone)
        }
        building = false
    }

    ForestBackground {
        Scaffold(containerColor = Color.Transparent, topBar = { ForestTopBar("Mile Markers", onBack) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                // Above the list, not in it: as a row it cost a chunk of the
                // screen to padding and scrolled away just when you wanted it.
                Segmented(
                    options = MileMarkersRange.entries.toList(),
                    selected = range,
                    label = { it.label },
                    onSelect = { range = it },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                val r = report
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (r != null && !r.isEmpty) {
                        tabSection(r, zone)
                        headlineSection(r, range)
                        outpacingSection(r, zone)
                        streakSection(r, range)
                        notableSection(r, zone)
                    } else {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 80.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (building) {
                                    CircularProgressIndicator(color = Palette.gold)
                                    Spacer(Modifier.height(12.dp))
                                    Text("Reading the books…", color = Palette.cream.copy(alpha = 0.7f))
                                } else {
                                    Text(
                                        "Nothing on the books over ${range.phrase}. Log a beer or go for a run.",
                                        color = Palette.cream.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.tabSection(r: MileMarkersReport, zone: ZoneId) {
    item { SectionHeader("The Tab") }
    item {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        Format.compactMiles(r.debtAtEndMiles),
                        color = Palette.cream, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        if (r.debtAtEndMiles < 0.05) "books are clean" else "on the tab",
                        color = Palette.cream.copy(alpha = 0.7f), fontSize = 14.sp,
                    )
                }
                ChangePill(r.debtChangeMiles)
            }
            Spacer(Modifier.height(12.dp))
            PositionChart(r.points, r.spans, zone, Modifier.fillMaxWidth().height(170.dp))
            Spacer(Modifier.height(6.dp))
            Key(
                buildList {
                    add(Palette.debt to "owed")
                    add(Palette.gold to "principal")
                    if (r.spans.any { it.kind == MileMarkersReport.Span.Kind.PROTECTED }) {
                        add(Palette.gold.copy(alpha = 0.35f) to "streak")
                    }
                    if (r.spans.any { it.kind == MileMarkersReport.Span.Kind.FROZEN }) {
                        add(Palette.frost.copy(alpha = 0.6f) to "freeze")
                    }
                },
                note = if (scaleFor(r.points, r.spans).log) "log scale" else null,
            )
            Spacer(Modifier.height(10.dp))
            StripLabel("miles run", Palette.creditSoft)
            FlowChart(r.points, { it.runMiles }, Palette.creditSoft, Modifier.fillMaxWidth().height(38.dp))
            StripLabel("beers", Palette.caution)
            FlowChart(
                r.points, { it.beers.toDouble() }, Palette.caution,
                Modifier.fillMaxWidth().height(30.dp),
                axisLabel = { it.toInt().toString() },
            )
            if (r.events.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                val formatter = DateTimeFormatter.ofPattern("MMM d")
                for (event in r.events) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            formatter.format(event.date.atZone(zone)),
                            color = Palette.cream.copy(alpha = 0.55f), fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp),
                        )
                        Text(event.title, color = Palette.cream.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.headlineSection(r: MileMarkersReport, range: MileMarkersRange) {
    item { SectionHeader("Over ${range.phrase}") }
    item {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(Format.compact(r.milesRun), "miles run", Icons.Filled.DirectionsRun, Palette.creditSoft, Modifier.weight(1f))
                Tile("${r.beersAdded}", if (r.beersAdded == 1) "beer" else "beers", Icons.Filled.SportsBar, Palette.gold, Modifier.weight(1f))
                Tile(Format.compact(r.interestChargedMiles), "mi interest", Icons.Filled.Percent, Palette.debt, Modifier.weight(1f))
            }
            // The number no other app can show: what the streak kept off the
            // books, from replaying the same ledger unprotected.
            if (r.interestWaivedMiles > 0.05) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .background(Palette.frost.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.AcUnit, null, tint = Palette.frost, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Your streak waived ${Format.compactMiles(r.interestWaivedMiles)} of interest.",
                        color = Palette.cream.copy(alpha = 0.85f), fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.outpacingSection(r: MileMarkersReport, zone: ZoneId) {
    item { SectionHeader("Keeping Up?") }
    item {
        val owed = r.points.lastOrNull()?.cumulativeBeerMiles ?: 0.0
        val run = r.points.lastOrNull()?.cumulativeRunMiles ?: 0.0
        val gap = run - owed
        Column {
            Text(
                when {
                    kotlin.math.abs(gap) < 0.1 -> "Dead even — every beer run off."
                    gap > 0 -> "Run ${Format.compactMiles(gap)} more than you drank."
                    else -> "Drunk ${Format.compactMiles(-gap)} more than you've run."
                },
                color = if (run >= owed) Palette.credit else Palette.debt,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            // Without this line the section contradicts the tab above: you can
            // out-run every beer you ever drank and still be buried, because
            // this compares what the beers cost and the tab is what the waiting
            // turned that into.
            if (r.interestChargedMiles > 0.05) {
                Text(
                    "Interest put ${Format.compactMiles(r.interestChargedMiles)} on top of that.",
                    color = Palette.debt.copy(alpha = 0.9f), fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            OutpacingChart(r.points, zone, Modifier.fillMaxWidth().height(140.dp))
            Spacer(Modifier.height(6.dp))
            Key(listOf(Palette.caution to "beer miles", Palette.credit to "miles run"))
        }
    }
    item { Footer("Beer miles are what the beers cost when you drank them. Interest is the tab above.") }
}

private fun androidx.compose.foundation.lazy.LazyListScope.streakSection(r: MileMarkersReport, range: MileMarkersRange) {
    item { SectionHeader("Running Days") }
    item {
        Column {
            StreakGrid(r.days, Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("${r.currentStreakDays}", "current", Icons.Filled.LocalFireDepartment, Palette.gold, Modifier.weight(1f))
                Tile("${r.longestStreakDays}", "longest", Icons.Filled.EmojiEvents, Palette.caution, Modifier.weight(1f))
                Tile("${r.totalStreakDays}", "total days", Icons.Filled.CalendarMonth, Palette.creditSoft, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("${r.freezesHeld}", if (r.freezesHeld == 1) "freeze held" else "freezes held", Icons.Filled.AcUnit, Palette.frost, Modifier.weight(1f))
                Tile("${r.freezesSpent}", "rest days", Icons.Filled.AcUnit, Palette.frost, Modifier.weight(1f))
            }
        }
    }
    item {
        Footer("Records are all-time. The grid covers ${if (range == MileMarkersRange.ALL) "up to the last year" else range.phrase}.")
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.notableSection(r: MileMarkersReport, zone: ZoneId) {
    item { SectionHeader("Notable") }
    item {
        val formatter = DateTimeFormatter.ofPattern("MMM d")
        Card {
            Column {
                val rows = buildList {
                    r.priciestBeer?.let {
                        if (r.priciestBeerInterestMiles > 0.05) {
                            add(Triple("Priciest beer", "#${it.number}", "${Format.compactMiles(r.priciestBeerInterestMiles)} of interest") to Palette.debt)
                        }
                    }
                    r.longestRun?.let {
                        add(Triple("Longest run", Format.miles(it.run.distanceMiles), formatter.format(it.run.endedAt.atZone(zone))) to Palette.creditSoft)
                    }
                    if (r.biggestDayBeers > 1 && r.biggestDay != null) {
                        add(Triple("Biggest day", "${r.biggestDayBeers} beers", formatter.format(r.biggestDay)) to Palette.gold)
                    }
                    if (r.longestDrySpellDays > 0) {
                        add(Triple("Longest dry spell", "${r.longestDrySpellDays} days", if (r.longestDrySpellDays >= 7) "impressive" else "") to Palette.frost)
                    }
                }
                rows.forEachIndexed { i, (row, colour) ->
                    val (label, value, detail) = row
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Palette.cream.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(value, color = colour, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (detail.isNotEmpty()) {
                                Text(detail, color = Palette.cream.copy(alpha = 0.5f), fontSize = 11.sp)
                            }
                        }
                    }
                    if (i < rows.lastIndex) HorizontalDivider(color = Palette.cream.copy(alpha = 0.08f))
                }
            }
        }
    }
}

// MARK: pieces

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, color = Palette.cream, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
private fun Footer(text: String) {
    Text(
        text, color = Palette.cream.copy(alpha = 0.5f), fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun ChangePill(delta: Double) {
    val flat = kotlin.math.abs(delta) < 0.05
    val colour = if (flat) Palette.cream.copy(alpha = 0.6f) else if (delta < 0) Palette.credit else Palette.debt
    Row(
        Modifier
            .background(colour.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                flat -> Icons.Filled.Remove
                delta < 0 -> Icons.Filled.ArrowDownward
                else -> Icons.Filled.ArrowUpward
            },
            null, tint = colour, modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (flat) "flat" else Format.compactMiles(kotlin.math.abs(delta)),
            color = colour, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Tile(value: String, label: String, icon: ImageVector, colour: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Palette.card, RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = colour, modifier = Modifier.size(14.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, color = Palette.cream, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Palette.cream.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Key(items: List<Pair<Color, String>>, note: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (colour, label) ->
            Box(Modifier.size(7.dp).background(colour, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(label, color = Palette.cream.copy(alpha = 0.65f), fontSize = 11.sp)
            Spacer(Modifier.width(12.dp))
        }
        Spacer(Modifier.weight(1f))
        // Say so rather than quietly bending the axis under the reader.
        if (note != null) Text(note, color = Palette.cream.copy(alpha = 0.45f), fontSize = 11.sp)
    }
}

@Composable
private fun StripLabel(text: String, colour: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(colour, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, color = Palette.cream.copy(alpha = 0.6f), fontSize = 11.sp)
    }
}
