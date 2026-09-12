package me.colinwatson.beerdebt.ui.debt

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.colinwatson.beerdebt.BeerDebtApp
import me.colinwatson.beerdebt.R
import me.colinwatson.beerdebt.engine.BeerStatement
import me.colinwatson.beerdebt.ui.Format
import me.colinwatson.beerdebt.ui.components.Card
import me.colinwatson.beerdebt.ui.components.ForestTopBar
import me.colinwatson.beerdebt.ui.components.Pill
import me.colinwatson.beerdebt.ui.components.Segmented
import me.colinwatson.beerdebt.ui.components.SwipeToDelete
import me.colinwatson.beerdebt.ui.theme.ForestBackground
import me.colinwatson.beerdebt.ui.theme.Palette
import java.time.Instant
import java.util.UUID

private enum class Filter(val label: String) { ACTIVE("Active"), PAID("Paid") }

/** Your Debt: active beers with what they cost now, paid beers behind a tab. */
@Composable
fun DebtScreen(onBack: () -> Unit, openFirstBeer: Boolean = false) {
    val app = LocalContext.current.applicationContext as BeerDebtApp
    val ledger by app.store.ledger.collectAsState()
    val now = Instant.now()
    val report = remember(ledger) { app.store.report(now) }
    var filter by remember { mutableStateOf(Filter.ACTIVE) }
    var selected by remember { mutableStateOf<UUID?>(if (openFirstBeer) report.beers.firstOrNull { !it.isPaid }?.id else null) }
    var toDelete by remember { mutableStateOf<BeerStatement?>(null) }
    val active = report.beers.filter { !it.isPaid }.reversed()
    val paid = report.beers.filter { it.isPaid }.reversed()
    val shown = if (filter == Filter.ACTIVE) active else paid

    ForestBackground {
        Scaffold(containerColor = Color.Transparent, topBar = { ForestTopBar("Your Debt", onBack) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Segmented(Filter.entries, filter, { if (it == Filter.ACTIVE) "Active (${active.size})" else "Paid (${paid.size})" }, { filter = it })
                }
                if (shown.isEmpty()) {
                    item { Card { Text(if (filter == Filter.ACTIVE) "Nothing owed. Books are clean." else "No beers paid off yet. Go for a run.", color = Palette.cream.copy(alpha = 0.7f)) } }
                }
                items(shown, key = { it.id }) { statement ->
                    SwipeToDelete(onDelete = { toDelete = statement }) {
                        Card(Modifier.clickable { selected = statement.id }) { BeerRow(statement, now) }
                    }
                }
                if (filter == Filter.ACTIVE && active.isNotEmpty()) {
                    item {
                        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Palette.cream.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Runs are applied to your oldest beers first.", color = Palette.cream.copy(alpha = 0.6f), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    selected?.let { id -> BeerDetailSheet(beerID = id, onDismiss = { selected = null }) }
    toDelete?.let { statement ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Take this beer off the books?") },
            text = { Text("Any run that paid for it goes to your other beers or to credit instead.") },
            confirmButton = { TextButton(onClick = { app.store.removeBeer(statement.id); toDelete = null }) { Text("Delete Beer #${statement.number}", color = Palette.debt) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun BeerRow(statement: BeerStatement, now: Instant) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.brand_mark), contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Beer #${statement.number}", color = Palette.cream, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(Format.dateTime(statement.createdAt), color = Palette.cream.copy(alpha = 0.65f), fontSize = 15.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (statement.isPaid) {
                Pill("PAID")
                Text(Format.miles(statement.costMiles, 2), color = Palette.cream.copy(alpha = 0.65f), fontSize = 15.sp)
                Text(if (statement.settledByCredit) "from credit" else "paid ${Format.day(statement.paidAt!!)}", color = Palette.cream.copy(alpha = 0.5f), fontSize = 12.sp)
            } else {
                Text(Format.miles(statement.outstandingMiles, 2), color = Palette.debt, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(Format.miles(statement.principalMiles, 2), color = Palette.cream.copy(alpha = 0.65f), fontSize = 15.sp)
                Text(Format.age(statement.createdAt, now), color = Palette.cream.copy(alpha = 0.5f), fontSize = 12.sp)
            }
        }
    }
}
