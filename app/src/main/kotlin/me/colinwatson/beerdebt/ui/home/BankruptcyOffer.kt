package me.colinwatson.beerdebt.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.colinwatson.beerdebt.engine.Balance
import me.colinwatson.beerdebt.engine.BalanceState
import me.colinwatson.beerdebt.ui.theme.Palette

/**
 * When Home offers Fresh Start (iOS spec §29). Pure: the threshold and the
 * tiers are the whole rule, and the dismissal is a preference Home owns.
 * Mirrors iOS `BankruptcyOffer`.
 */
object BankruptcyOffer {
    /**
     * Total debt, in engine miles, past which the tab can't sensibly be run off
     * (iOS decisions §B.11). Calorie mode changes how it reads, not where it is.
     */
    const val THRESHOLD_MILES = 100.0

    /** Key in the "app" preferences for the highest tier the user said "Not now" to. */
    const val DISMISSED_TIER_KEY = "bankruptcyOfferDismissedTier"

    /**
     * 0 from 100 mi, 1 from 1,000, 2 from 10,000: each tier ten times the last,
     * so a dismissed offer comes back about once a month at the default rate
     * however large the tab has grown. Null under the threshold, and whenever
     * the books aren't in debt.
     */
    fun tier(balance: Balance): Int? {
        if (balance.state != BalanceState.DEBT || balance.debtMiles < THRESHOLD_MILES) return null
        var tier = 0
        var next = THRESHOLD_MILES * 10
        while (balance.debtMiles >= next) {
            tier++
            next *= 10
        }
        return tier
    }

    /** Shown until dismissed, and again once the tab reaches a higher tier. */
    fun shows(tier: Int?, dismissedTier: Int?): Boolean = when {
        tier == null -> false
        dismissedTier == null -> true
        else -> tier > dismissedTier
    }
}

/**
 * The Home card (iOS spec §29). Deadpan, like the filing it leads to: the
 * amount carries the point, and nothing says the user should have run more.
 */
@Composable
fun BankruptcyOfferCard(owed: String, onFile: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.forestDeep.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("This tab got away.", color = Palette.cream, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
            Text(
                "You owe $owed. Some tabs are better closed than run off.",
                color = Palette.cream.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                "Fresh Start", color = Palette.gold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp,
                modifier = Modifier.clickable(onClick = onFile).padding(vertical = 2.dp),
            )
            Text(
                "Not now", color = Palette.cream.copy(alpha = 0.6f), fontSize = 15.sp, lineHeight = 20.sp,
                modifier = Modifier.clickable(onClick = onDismiss).padding(vertical = 2.dp),
            )
        }
    }
}
