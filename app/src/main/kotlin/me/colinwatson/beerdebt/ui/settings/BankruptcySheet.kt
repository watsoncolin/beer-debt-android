package me.colinwatson.beerdebt.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.colinwatson.beerdebt.engine.Report
import me.colinwatson.beerdebt.ui.Format
import me.colinwatson.beerdebt.ui.components.Card
import me.colinwatson.beerdebt.ui.components.GoldButton
import me.colinwatson.beerdebt.ui.theme.Palette

/**
 * What is about to be written off, read once when the sheet opens. Snapshotted
 * so the line items hold still, including after a write that failed and left
 * the in-memory books already empty.
 */
data class BankruptcyFiling(
    val beers: Int,
    val outstandingMiles: Double,
    val runs: Int,
    val creditMiles: Double,
    val streakDays: Int,
) {
    constructor(report: Report) : this(
        beers = report.beers.size,
        outstandingMiles = report.balance.debtMiles,
        runs = report.runs.size,
        creditMiles = report.balance.creditMiles,
        streakDays = report.streak.currentStreakDays,
    )
}

/**
 * The one destructive act in the app (spec §26). Played as a filing, not a
 * scolding: the bank's voice stays deadpan, the warning is in the line items
 * rather than in a tone of voice, and nothing here says the user should have
 * run more.
 *
 * Two phases in one sheet. The filing states exactly what goes and what stays;
 * the stamp is the payoff, so the reset has a moment of its own instead of
 * dumping the user back into Settings with a changed number.
 *
 * [onWriteOff] returns whether the fresh books reached the file.
 * [startWrittenOff] is for `--es debugScreen bankruptcyWrittenOff`; the app
 * always opens on the filing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankruptcySheet(
    filing: BankruptcyFiling,
    startWrittenOff: Boolean = false,
    onWriteOff: () -> Boolean,
    onDismiss: () -> Unit,
) {
    var writtenOff by remember { mutableStateOf(startWrittenOff) }
    var writeFailed by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.forestDeep,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (writtenOff) WriteOffNotice(filing, onDismiss)
            else FilingForm(filing, writeFailed, onWriteOff = {
                if (onWriteOff()) writtenOff = true else writeFailed = true
            }, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun FilingForm(filing: BankruptcyFiling, writeFailed: Boolean, onWriteOff: () -> Unit, onDismiss: () -> Unit) {
    Text(
        "CHAPTER 7 · SECTION BEER",
        color = Palette.gold.copy(alpha = 0.9f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
    )
    Spacer(Modifier.height(10.dp))
    Text("Declare Bankruptcy", color = Palette.cream, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(10.dp))
    Text(
        "Wipe the tab and open fresh books. No judgment here — some tabs are better closed than run off.",
        color = Palette.cream.copy(alpha = 0.8f),
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))

    // The filing's line items: the actual tab, named in the app's own
    // vocabulary. Seeing the number is most of the warning.
    Card {
        Column {
            Text(
                "ON THE BOOKS",
                color = Palette.cream.copy(alpha = 0.55f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(8.dp))
            LineItem("Beers", Format.beersLabel(filing.beers.toDouble()))
            if (filing.outstandingMiles > 0) LineItem("Outstanding", Format.miles(filing.outstandingMiles), Palette.debt)
            if (filing.creditMiles > 0) LineItem("Banked credit", Format.miles(filing.creditMiles), Palette.credit)
            LineItem("Runs", "${filing.runs}")
            if (filing.streakDays > 0) {
                LineItem("Streak", "${filing.streakDays} day${if (filing.streakDays == 1) "" else "s"}", Palette.gold)
            }
        }
    }
    Spacer(Modifier.height(18.dp))

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Note(Icons.Filled.Delete, Palette.debt, "Gone for good: ", "every beer, run, streak, and freeze on the books. There's no undo.")
        Note(Icons.Filled.CheckCircle, Palette.credit, "Untouched: ", "the rules you set, and every run in Health Connect. They simply stop counting here.")
    }

    if (writeFailed) {
        Spacer(Modifier.height(14.dp))
        Text(
            "Couldn't write the fresh books to this phone. Nothing is lost — try again in a moment.",
            color = Palette.debt,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }

    Spacer(Modifier.height(24.dp))
    DebtButton(if (writeFailed) "Try again" else "Write it all off", onClick = onWriteOff)
    TextButton(onClick = onDismiss) {
        Text("Never mind", color = Palette.cream.copy(alpha = 0.75f), fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun WriteOffNotice(filing: BankruptcyFiling, onDismiss: () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Text(
        "WRITTEN OFF",
        color = Palette.credit,
        fontSize = 32.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 3.sp,
        modifier = Modifier
            .rotate(-7f)
            .border(4.dp, Palette.credit, RoundedCornerShape(10.dp))
            .padding(horizontal = 22.dp, vertical = 12.dp),
    )
    Spacer(Modifier.height(30.dp))
    Text("Books are clean.", color = Palette.cream, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    Text(
        summary(filing),
        color = Palette.cream.copy(alpha = 0.8f),
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(36.dp))
    GoldButton("Start fresh", onClick = onDismiss)
    Spacer(Modifier.height(16.dp))
}

private fun summary(filing: BankruptcyFiling): String {
    val written = if (filing.beers == 0) "Nothing was owed"
    else "${Format.beersLabel(filing.beers.toDouble())} written off"
    return "$written. Your rules carry over untouched, and the tab starts from zero."
}

@Composable
private fun LineItem(label: String, value: String, color: Color = Palette.cream) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.cream.copy(alpha = 0.8f), fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Note(icon: ImageVector, color: Color, lead: String, rest: String) {
    Row(Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Palette.cream.copy(alpha = 0.9f), fontWeight = FontWeight.Bold)) { append(lead) }
                withStyle(SpanStyle(color = Palette.cream.copy(alpha = 0.75f))) { append(rest) }
            },
            fontSize = 13.sp,
        )
    }
}

/** The destructive twin of [GoldButton]: same capsule, debt red. */
@Composable
fun DebtButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.debt, contentColor = Palette.cream),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
