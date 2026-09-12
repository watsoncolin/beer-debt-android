package me.colinwatson.beerdebt.ui.debt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.colinwatson.beerdebt.BeerDebtApp
import me.colinwatson.beerdebt.engine.BalanceEngine
import me.colinwatson.beerdebt.ui.Format
import me.colinwatson.beerdebt.ui.components.Card
import me.colinwatson.beerdebt.ui.home.BeerDatePicker
import me.colinwatson.beerdebt.ui.theme.Palette
import java.time.Instant
import java.util.UUID

/** One beer's statement; the date is editable and the beer deletable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeerDetailSheet(beerID: UUID, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as BeerDebtApp
    val ledger by app.store.ledger.collectAsState()
    val now = Instant.now()
    val report = remember(ledger) { app.store.report(now) }
    val statement = report.statement(beerID)
    val entry = ledger.beers.firstOrNull { it.id == beerID }
    var pickingDate by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.forestDeep) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(statement?.let { "Beer #${it.number}" } ?: "Beer", color = Palette.cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(16.dp))
            if (statement != null && entry != null) {
                Card(Modifier.clickable { pickingDate = true }) {
                    Column {
                        Row { Text("Added", color = Palette.cream, modifier = Modifier.weight(1f)); Text(Format.dateTime(entry.createdAt), color = Palette.gold) }
                        if (entry.isBackdated) { Spacer(Modifier.height(8.dp)); Row { Text("Logged", color = Palette.cream, modifier = Modifier.weight(1f)); Text(Format.dateTime(entry.recordedAt!!), color = Palette.cream.copy(alpha = 0.7f)) } }
                    }
                }
                Text("Forgot one? Change the date and the books are redone as if it had been logged then.", color = Palette.cream.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                Spacer(Modifier.height(8.dp))
                Card {
                    Column {
                        Line("Cost", Format.miles(statement.costMiles, 2))
                        if (statement.coveredByCreditMiles > BalanceEngine.EPSILON) Line("Paid from credit", Format.miles(statement.coveredByCreditMiles, 2))
                        Line("Principal", Format.miles(statement.principalMiles, 2))
                        Line("Interest accrued", Format.miles(statement.interestAccruedMiles, 2))
                        Line("Repaid by running", Format.miles(statement.paidMiles, 2))
                        if (statement.writtenOffMiles > BalanceEngine.EPSILON) Line("Written off", Format.miles(statement.writtenOffMiles, 2))
                        Line("Outstanding", Format.miles(statement.outstandingMiles, 2), valueColor = if (statement.isPaid) Palette.cream.copy(alpha = 0.6f) else Palette.debt, last = true)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card {
                    Column {
                        Line("Status", if (statement.isPaid) "Paid" else "Unpaid")
                        statement.paidAt?.let { Line("Paid", Format.dateTime(it), last = true) } ?: run {
                            Line("Age", Format.age(statement.createdAt, now))
                            statement.nextInterestAt?.let { Line("Next interest", Format.relative(it, now), last = true) } ?: Line("Interest", "Off", last = true)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(Modifier.clickable { confirmingDelete = true }) { Text("Delete Beer", color = Palette.debt, fontWeight = FontWeight.SemiBold) }
            } else {
                Card { Text("That beer isn't on the books", color = Palette.cream.copy(alpha = 0.7f)) }
            }
        }
    }

    if (pickingDate && entry != null) {
        BeerDatePicker(entry.createdAt, app.store.earliestBeerDate(now), now, onPick = { app.store.updateBeerDate(beerID, it); pickingDate = false }, onDismiss = { pickingDate = false })
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Take this beer off the books?") },
            text = { Text("Any run that paid for it goes to your other beers or to credit instead.") },
            confirmButton = { TextButton(onClick = { app.store.removeBeer(beerID); confirmingDelete = false; onDismiss() }) { Text("Delete Beer", color = Palette.debt) } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Line(label: String, value: String, valueColor: Color = Palette.cream.copy(alpha = 0.85f), last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, color = Palette.cream, modifier = Modifier.weight(1f))
        Text(value, color = valueColor)
    }
    if (!last) HorizontalDivider(color = Palette.cream.copy(alpha = 0.1f))
}
