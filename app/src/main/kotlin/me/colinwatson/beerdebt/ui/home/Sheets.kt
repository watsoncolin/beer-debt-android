package me.colinwatson.beerdebt.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.colinwatson.beerdebt.BeerDebtApp
import me.colinwatson.beerdebt.R
import me.colinwatson.beerdebt.engine.BalanceEngine
import me.colinwatson.beerdebt.engine.CompoundingPeriod
import me.colinwatson.beerdebt.health.DebtFreeCelebration
import me.colinwatson.beerdebt.ui.Format
import me.colinwatson.beerdebt.ui.components.GoldButton
import me.colinwatson.beerdebt.ui.components.StatRow
import me.colinwatson.beerdebt.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Brief feedback after + Beer. One tap to dismiss; date change and remove are a tap away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeerAddedSheet(beerID: UUID, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as BeerDebtApp
    val ledger by app.store.ledger.collectAsState()
    val now = Instant.now()
    val entry = ledger.beers.firstOrNull { it.id == beerID }
    val opening = entry?.let { app.store.report(it.createdAt).statement(beerID) }
    val current = app.store.report(now)
    val statement = current.statement(beerID)
    var pickingDate by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.forestDeep) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.brand_mark), contentDescription = null, modifier = Modifier.size(140.dp))
            Spacer(Modifier.height(8.dp))
            Text("Beer added!", color = Palette.cream, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(12.dp))
            if (opening != null && statement != null && entry != null) {
                when {
                    opening.settledByCredit -> {
                        Text("Covered by your credit.", color = Palette.cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("${Format.beersLabel(current.balance.creditBeers)} still banked.", color = Palette.cream.copy(alpha = 0.7f))
                    }
                    else -> {
                        Text("That's +${Format.miles(opening.principalMiles)}", color = Palette.cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        val sub = when {
                            opening.coveredByCreditMiles > BalanceEngine.EPSILON -> "${Format.miles(opening.coveredByCreditMiles)} came out of credit."
                            entry.isBackdated && statement.isPaid -> "Dated ${Format.dateTime(entry.createdAt)}. Already paid off by a run."
                            entry.isBackdated && statement.interestAccruedMiles > BalanceEngine.EPSILON -> "Dated ${Format.dateTime(entry.createdAt)}. Already ${Format.miles(statement.outstandingMiles, 2)} with interest."
                            current.rules.interestEnabled -> "(for now...)"
                            else -> ""
                        }
                        if (sub.isNotEmpty()) Text(sub, color = Palette.cream.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                    }
                }
                if (!statement.isPaid) {
                    Spacer(Modifier.height(18.dp))
                    Column(Modifier.fillMaxWidth().background(Palette.card.copy(alpha = 0.85f), RoundedCornerShape(18.dp)).padding(18.dp)) {
                        if (current.rules.interestEnabled) {
                            Text("If you don't run it off, this beer will cost you:", color = Palette.cream.copy(alpha = 0.8f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            for ((seconds, label) in horizons(current.rules.interestPeriod)) {
                                val cost = app.store.report(now.plusSeconds(seconds)).statement(beerID)?.outstandingMiles ?: 0.0
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(Format.miles(cost, 2), color = Palette.debt, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text(label, color = Palette.cream.copy(alpha = 0.7f))
                                }
                            }
                            statement.nextInterestAt?.let { next ->
                                val verb = if (statement.interestAccruedMiles > BalanceEngine.EPSILON) "Next interest" else "Interest starts"
                                Spacer(Modifier.height(8.dp))
                                Text("$verb ${Format.relative(next, now)}.", color = Palette.cream.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                        } else {
                            Text("No interest. You turned it off. Still owed, though.", color = Palette.cream.copy(alpha = 0.8f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { pickingDate = true }) { Text("📅  Forgot one earlier? Change the date", color = Palette.cream.copy(alpha = 0.75f), fontSize = 13.sp) }
            TextButton(onClick = { app.store.removeBeer(beerID); onDismiss() }) { Text("↩  Didn't mean that? Remove it", color = Palette.cream.copy(alpha = 0.75f), fontSize = 13.sp) }
            Spacer(Modifier.height(16.dp))
            GoldButton("Cheers!", onClick = onDismiss)
            Spacer(Modifier.height(16.dp))
        }
    }

    if (pickingDate && entry != null) {
        BeerDatePicker(current = entry.createdAt, earliest = app.store.earliestBeerDate(now), latest = now,
            onPick = { app.store.updateBeerDate(beerID, it); pickingDate = false }, onDismiss = { pickingDate = false })
    }
}

private fun horizons(period: CompoundingPeriod): List<Pair<Long, String>> = when (period) {
    CompoundingPeriod.DAILY -> listOf(86_400L to "in 1 day", 2 * 86_400L to "in 2 days", 5 * 86_400L to "in 5 days")
    CompoundingPeriod.WEEKLY -> listOf(7 * 86_400L to "in 1 week", 14 * 86_400L to "in 2 weeks", 28 * 86_400L to "in 4 weeks")
}

/** Date picker for a beer; keeps the beer's time of day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeerDatePicker(current: Instant, earliest: Instant, latest: Instant, onPick: (Instant) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val state = rememberDatePickerState(initialSelectedDateMillis = current.atZone(zone).toLocalDate().atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis ?: return@TextButton
                val day = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                val time = current.atZone(zone).toLocalTime()
                val picked = day.atTime(time).atZone(zone).toInstant()
                onPick(picked.coerceIn(earliest, latest))
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

/** Shown once when a synced run clears the tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtFreeSheet(celebration: DebtFreeCelebration, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.forestDeep) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.debt_free_trophy), contentDescription = null, modifier = Modifier.size(170.dp))
            Text("Debt Free!", color = Palette.gold, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
            Text("Nice work. Your tab is paid.", color = Palette.cream, fontSize = 20.sp)
            Spacer(Modifier.height(24.dp))
            StatRow("${celebration.totalBeers}" to "total beers", Format.number(celebration.milesRepaid) to "miles repaid", Format.beers(celebration.creditBeers) to "beers banked")
            Spacer(Modifier.height(24.dp))
            GoldButton("Cheers!", onClick = onDismiss)
            Spacer(Modifier.height(16.dp))
        }
    }
}
