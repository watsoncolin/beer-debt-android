package me.colinwatson.beerdebt.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.colinwatson.beerdebt.BeerDebtApp
import me.colinwatson.beerdebt.engine.Balance
import me.colinwatson.beerdebt.engine.BalanceState
import me.colinwatson.beerdebt.engine.BeerEntry
import me.colinwatson.beerdebt.engine.Report
import me.colinwatson.beerdebt.ui.Format
import me.colinwatson.beerdebt.ui.components.GoldButton
import me.colinwatson.beerdebt.ui.milesRunInWeekOf
import me.colinwatson.beerdebt.ui.theme.Backdrop
import me.colinwatson.beerdebt.ui.theme.Palette
import java.time.Instant

/** The product: the balance dominates; one big + Beer; one line of context. */
@Composable
fun HomeScreen(onOpenDebt: () -> Unit, onOpenRuns: () -> Unit, onOpenSettings: () -> Unit, showLatestBeerSheet: Boolean = false) {
    val app = LocalContext.current.applicationContext as BeerDebtApp
    val ledger by app.store.ledger.collectAsState()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = Instant.now() } }   // interest can post while on screen
    val report = remember(ledger, now) { app.store.report(now) }
    var addedBeer by remember { mutableStateOf<BeerEntry?>(if (showLatestBeerSheet) ledger.beers.maxByOrNull { it.createdAt } else null) }

    Backdrop {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Box(Modifier.fillMaxWidth()) {
                IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Palette.cream)
                }
                Column(Modifier.align(Alignment.Center).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Beer Debt", color = Palette.cream, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Drink now. Run later.", color = Palette.cream.copy(alpha = 0.7f), fontSize = 15.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpenDebt), horizontalAlignment = Alignment.CenterHorizontally) {
                BalanceHero(report.balance)
                Spacer(Modifier.height(14.dp))
                Text("Your tab ›", color = Palette.gold.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            GoldButton("🍺  + Beer") { addedBeer = app.store.addBeer() }
            Spacer(Modifier.height(16.dp))
            RunsCard(report, now, onOpenRuns)
            Spacer(Modifier.height(16.dp))
            Text(
                quip(report.balance), color = Palette.cream.copy(alpha = 0.7f), fontSize = 13.sp, fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    addedBeer?.let { beer -> BeerAddedSheet(beerID = beer.id, onDismiss = { addedBeer = null }) }
}

@Composable
private fun BalanceHero(balance: Balance) {
    when (balance.state) {
        BalanceState.DEBT -> {
            BigNumber(Format.number(balance.debtMiles))
            Text("mi owed", color = Palette.cream.copy(alpha = 0.85f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Stat(Format.number(balance.principalMiles), "principal", Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(36.dp).background(Palette.cream.copy(alpha = 0.25f)))
                Stat(Format.number(balance.interestMiles), "interest", Modifier.weight(1f))
            }
        }
        BalanceState.CREDIT -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🍺", fontSize = 56.sp)
                Spacer(Modifier.width(8.dp))
                BigNumber(Format.beers(balance.creditBeers))
            }
            Text(if (balance.creditBeers < 1.05) "beer banked" else "beers banked", color = Palette.cream.copy(alpha = 0.85f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text("Credit slowly expires.", color = Palette.cream.copy(alpha = 0.55f), fontSize = 13.sp)
        }
        BalanceState.EVEN -> {
            BigNumber("0")
            Spacer(Modifier.height(8.dp))
            Text("BOOKS ARE CLEAN", color = Palette.gold, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun BigNumber(text: String) {
    Text(text, color = Palette.cream, fontSize = 104.sp, fontWeight = FontWeight.Black, maxLines = 1, lineHeight = 108.sp)
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Palette.cream, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Palette.cream.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

@Composable
private fun RunsCard(report: Report, now: Instant, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.forestDeep.copy(alpha = 0.85f), RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.DirectionsRun, contentDescription = null, tint = Palette.cream)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(Format.miles(report.milesRunInWeekOf(now)), color = Palette.cream, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(if (report.balance.state == BalanceState.DEBT) "paid this week" else "run this week", color = Palette.cream.copy(alpha = 0.7f), fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.cream.copy(alpha = 0.6f))
    }
}

private fun quip(balance: Balance): String = when (balance.state) {
    BalanceState.DEBT -> if (balance.interestMiles > 0.05) "Your tab is getting expensive." else "Your drinking is currently outpacing your running."
    BalanceState.CREDIT -> if (balance.creditBeers >= 2) "You've earned a couple." else "You've earned one."
    BalanceState.EVEN -> "Every beer has a price. Yours is measured in miles."
}
