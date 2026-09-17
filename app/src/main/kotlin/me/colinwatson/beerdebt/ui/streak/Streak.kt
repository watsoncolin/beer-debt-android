package me.colinwatson.beerdebt.ui.streak

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.colinwatson.beerdebt.BeerDebtApp
import me.colinwatson.beerdebt.R
import me.colinwatson.beerdebt.engine.BalanceState
import me.colinwatson.beerdebt.engine.StreakStatus
import me.colinwatson.beerdebt.health.StreakCelebration
import me.colinwatson.beerdebt.ui.components.Card
import me.colinwatson.beerdebt.ui.components.ForestTopBar
import me.colinwatson.beerdebt.ui.components.GoldButton
import me.colinwatson.beerdebt.ui.theme.ForestBackground
import me.colinwatson.beerdebt.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/** The streak mark: the painted flame, lit or out. */
@Composable
fun StreakFlame(lit: Boolean = true, size: Int = 40) {
    Image(painterResource(if (lit) R.drawable.streak_flame else R.drawable.streak_flame_unlit), contentDescription = null, modifier = Modifier.size(size.dp))
}

/** Words for the streak, identical to iOS `StreakCopy`: a rate you earned, not a cheer. */
object StreakCopy {
    fun title(s: StreakStatus): String = when (s.currentStreakDays) { 0 -> "Start a streak"; 1 -> "1 day streak"; else -> "${s.currentStreakDays} day streak" }

    fun subtitle(s: StreakStatus, balance: BalanceState): String = when (s.currentStreakDays) {
        0 -> "Run 1+ mile today and tomorrow to pause interest."
        1 -> "Run 1+ mile tomorrow to unlock 0% APR."
        else -> if (s.todayProtected) (if (balance == BalanceState.DEBT) "Interest paused" else "0% APR — earned. Keep it going.")
                else (if (balance == BalanceState.DEBT) "Run 1+ mile today to keep interest paused." else "Run 1+ mile today to keep it.")
    }

    fun standing(s: StreakStatus): String = when (s.currentStreakDays) {
        0 -> "No streak yet."
        1 -> "Day one. Nothing earned yet."
        else -> if (s.todayProtected) "0% APR — earned." else "0% APR — earned, if you run today."
    }

    fun detail(s: StreakStatus): String = when (s.currentStreakDays) {
        0 -> "Run 1+ mile today and tomorrow. From day two, interest on your tab pauses."
        1 -> "Run 1+ mile tomorrow to unlock 0% APR."
        else -> "Keep running 1+ mile each day to keep interest paused."
    }
}

/** The Home card: prominent, but second to the balance. */
@Composable
fun StreakCard(streak: StreakStatus, balance: BalanceState, onClick: () -> Unit) {
    val lit = streak.currentStreakDays >= 1
    Row(
        Modifier.fillMaxWidth()
            .background(Palette.forestDeep.copy(alpha = if (lit) 0.85f else 0.6f), RoundedCornerShape(18.dp))
            .then(if (lit) Modifier else Modifier.border(1.dp, Palette.cream.copy(alpha = 0.35f), RoundedCornerShape(18.dp)))
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreakFlame(lit = lit, size = 36)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(StreakCopy.title(streak), color = Palette.cream, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(StreakCopy.subtitle(streak, balance), color = if (streak.interestProtectionActive && streak.todayProtected) Palette.gold else Palette.cream.copy(alpha = 0.7f), fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.cream.copy(alpha = 0.6f))
    }
}

/** "Your Streak": the number, what it has earned, this week, the records, and the rule. */
@Composable
fun StreakScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as BeerDebtApp
    val ledger by app.store.ledger.collectAsState()
    val now = Instant.now()
    val report = remember(ledger) { app.store.report(now) }
    val streak = report.streak

    ForestBackground {
        Scaffold(containerColor = Color.Transparent, topBar = { ForestTopBar("Your Streak", onBack) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Card {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StreakFlame(lit = streak.currentStreakDays >= 1, size = 64)
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(if (streak.currentStreakDays == 1) "1 day" else "${streak.currentStreakDays} days", color = Palette.cream, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                                    Text(StreakCopy.standing(streak), color = if (streak.interestProtectionActive) Palette.gold else Palette.cream.copy(alpha = 0.7f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(StreakCopy.detail(streak), color = Palette.cream.copy(alpha = 0.7f), fontSize = 13.sp)
                        }
                    }
                }
                item { SectionHeader("This week") }
                item { Card { WeekRow(streak, now) } }
                item {
                    Card {
                        Column {
                            ValueRow("Longest streak", if (streak.longestStreakDays == 1) "1 day" else "${streak.longestStreakDays} days")
                            HorizontalDivider(color = Palette.cream.copy(alpha = 0.1f))
                            ValueRow("Total streak days", "${streak.totalQualifyingDays}")
                        }
                    }
                }
                item { SectionHeader("How it works") }
                item {
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Rule("Run at least 1.0 mile of verified running each day. Two short runs add up.")
                            Rule("After two days in a row, interest on your beer debt pauses.")
                            Rule("Keep running daily to keep your 0% rate. Miss a day and the streak ends.")
                            Rule("Your streak never reduces what you already owe.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekRow(streak: StreakStatus, now: Instant) {
    val zone = streak.zone
    val today = now.atZone(zone).toLocalDate()
    // Read through the CompositionLocal, not Locale.getDefault(): the latter is
    // not observable state, so the week would keep its old first day and day
    // names until something else happened to recompose. Lint enforces this.
    val locale = LocalLocale.current.platformLocale
    val first = today.with(WeekFields.of(locale).dayOfWeek(), 1)
    Row(Modifier.fillMaxWidth()) {
        (0 until 7).forEach { i ->
            val day = first.plusDays(i.toLong())
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                DayCircle(day, today, streak.day(day.atStartOfDay(zone).toInstant()))
                Spacer(Modifier.height(6.dp))
                Text(day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), color = Palette.cream.copy(alpha = if (day.isAfter(today)) 0.4f else 0.7f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DayCircle(day: LocalDate, today: LocalDate, entry: me.colinwatson.beerdebt.engine.StreakDay?) {
    val m = Modifier.size(28.dp)
    when {
        entry?.qualifies == true -> Box(m.background(Palette.credit, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Palette.ink, modifier = Modifier.size(16.dp))
        }
        day == today -> Box(m.border(2.dp, Palette.gold, CircleShape))
        else -> Box(m.border(1.dp, Palette.cream.copy(alpha = 0.25f), CircleShape))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = Palette.cream.copy(alpha = 0.7f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, color = Palette.cream, modifier = Modifier.weight(1f))
        Text(value, color = Palette.cream.copy(alpha = 0.7f))
    }
}

@Composable
private fun Rule(text: String) {
    Row {
        Text("•", color = Palette.gold)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Palette.cream.copy(alpha = 0.85f), fontSize = 15.sp)
    }
}

/** Shown once when a synced run makes it two days in a row. Pairs with DebtFreeSheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreakActivatedSheet(celebration: StreakCelebration, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.forestDeep) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.streak_activated), contentDescription = null, modifier = Modifier.size(170.dp))
            Text("${celebration.days} day streak", color = Palette.gold, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
            Text("0% APR — earned.", color = Palette.cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Your debt interest is now paused. Keep running 1+ mile a day to keep it that way.", color = Palette.cream.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            GoldButton("Cheers!", onClick = onDismiss)
            Spacer(Modifier.height(16.dp))
        }
    }
}
