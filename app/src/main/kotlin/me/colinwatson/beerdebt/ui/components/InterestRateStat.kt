package me.colinwatson.beerdebt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.colinwatson.beerdebt.engine.Report
import me.colinwatson.beerdebt.ui.Format
import me.colinwatson.beerdebt.ui.streak.StreakFlame
import me.colinwatson.beerdebt.ui.theme.Palette

/**
 * What the tab costs to leave alone: the interest one compounding period adds
 * at the rate in force. Home leads with this instead of interest-to-date,
 * which is a total that barely moves; the totals live on Your Debt.
 *
 * It is also where the streak becomes worth something you can see. When today
 * is protected the charge is struck through and lit, so the number reads as
 * what running saved. The condition is `todayProtected`, not
 * `interestProtectionActive`: a streak alive from yesterday pauses nothing
 * until today has its mile, and showing the charge live is exactly the nudge
 * the streak card underneath then spells out.
 *
 * Mirrors iOS `InterestRateStat`.
 */
@Composable
fun InterestRateStat(report: Report, modifier: Modifier = Modifier, alignment: Alignment.Horizontal = Alignment.CenterHorizontally) {
    val paused = report.streak.todayProtected
    val on = report.rules.interestEnabled
    val value = Format.number(report.balance.interestPerPeriodMiles, decimals = 2)
    val label = if (on) report.rules.interestPeriod.perLabel else "interest off"
    val struck = on && paused

    val spoken = when {
        !on -> "Interest is off"
        paused -> "$value miles $label in interest, paused by your streak today"
        else -> "$value miles $label in interest"
    }

    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (struck) {
                StreakFlame(lit = true, size = 18)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                if (on) value else "—",
                color = if (struck) Palette.gold else Palette.cream,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
            )
        }
        Text(label, color = Palette.cream.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
    }
}
