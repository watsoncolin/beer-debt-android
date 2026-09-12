package me.colinwatson.beerdebt.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import me.colinwatson.beerdebt.BeerDebtApp
import me.colinwatson.beerdebt.MainActivity
import me.colinwatson.beerdebt.R
import me.colinwatson.beerdebt.engine.Balance
import me.colinwatson.beerdebt.engine.BalanceState
import me.colinwatson.beerdebt.engine.Report
import me.colinwatson.beerdebt.ui.Format
import me.colinwatson.beerdebt.ui.milesRunInWeekOf
import me.colinwatson.beerdebt.ui.theme.Palette
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Home screen glance at the tab (spec §22), the port of the iOS WidgetKit
 * small and medium faces. Replays the same ledger the app uses, so the
 * numbers are exactly the app's. Refreshed whenever the ledger is saved, at
 * the next interest posting, and on the launcher's half-hourly tick.
 */
class BalanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val now = Instant.now()
        val report = BeerDebtApp.instance.store.report(now)
        scheduleRefresh(context, report.nextInterestAt, now)
        provideContent { Face(report, now) }
    }

    @Composable
    private fun Face(report: Report, now: Instant) {
        val medium = LocalSize.current.width >= MEDIUM.width
        val balance = report.balance
        Column(
            GlanceModifier.fillMaxSize()
                .background(ImageProvider(R.drawable.widget_background))
                .padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            if (medium) {
                Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight()) {
                        Header()
                        Spacer(GlanceModifier.defaultWeight())
                        Hero(balance, 44)
                    }
                    Spacer(GlanceModifier.width(16.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        when (balance.state) {
                            BalanceState.DEBT -> Row {
                                Stat(Format.number(balance.principalMiles), "principal")
                                Spacer(GlanceModifier.width(14.dp))
                                Stat(Format.number(balance.interestMiles), "interest")
                            }
                            BalanceState.CREDIT -> Text("Credit slowly expires.", style = caption(Palette.cream.copy(alpha = 0.7f)))
                            BalanceState.EVEN -> Text("Drink now. Run later.", style = caption(Palette.cream.copy(alpha = 0.7f)))
                        }
                        Spacer(GlanceModifier.height(10.dp))
                        Text("🏃 ${runLine(report, now)}", style = TextStyle(color = ColorProvider(Palette.cream), fontSize = 12.sp, fontWeight = FontWeight.Medium))
                    }
                }
            } else {
                Header()
                Spacer(GlanceModifier.defaultWeight())
                Hero(balance, 40)
                Spacer(GlanceModifier.defaultWeight())
                Text(runLine(report, now), style = caption(Palette.cream.copy(alpha = 0.7f)))
            }
        }
    }

    @Composable
    private fun Header() {
        Text("🍺 Beer Debt", style = TextStyle(color = ColorProvider(Palette.cream), fontSize = 12.sp, fontWeight = FontWeight.Bold))
    }

    @Composable
    private fun Hero(balance: Balance, numberSize: Int) {
        Column {
            Text(heroNumber(balance), style = TextStyle(color = ColorProvider(Palette.cream), fontSize = numberSize.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Text(heroCaption(balance), style = TextStyle(color = ColorProvider(heroCaptionColor(balance)), fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
    }

    @Composable
    private fun Stat(value: String, label: String) {
        Column {
            Text(value, style = TextStyle(color = ColorProvider(Palette.cream), fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Text(label, style = caption(Palette.cream.copy(alpha = 0.6f)))
        }
    }

    private fun caption(color: Color) = TextStyle(color = ColorProvider(color), fontSize = 11.sp)

    /** Queues a refresh at the next interest posting so the number ticks over on time. */
    private fun scheduleRefresh(context: Context, nextInterestAt: Instant?, now: Instant) {
        val manager = WorkManager.getInstance(context)
        if (nextInterestAt == null || !nextInterestAt.isAfter(now)) { manager.cancelUniqueWork(REFRESH_WORK); return }
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInitialDelay(Duration.between(now, nextInterestAt).toMillis() + 1_000, TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniqueWork(REFRESH_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)
        const val REFRESH_WORK = "widget-refresh"

        // Copy, identical to iOS BalanceWidgetView.
        fun heroNumber(balance: Balance): String = when (balance.state) {
            BalanceState.DEBT -> Format.number(balance.debtMiles)
            BalanceState.CREDIT -> Format.beers(balance.creditBeers)
            BalanceState.EVEN -> "0"
        }

        fun heroCaption(balance: Balance): String = when (balance.state) {
            BalanceState.DEBT -> "mi owed"
            BalanceState.CREDIT -> if (balance.creditBeers < 1.05) "beer banked" else "beers banked"
            BalanceState.EVEN -> "BOOKS ARE CLEAN"
        }

        fun heroCaptionColor(balance: Balance): Color = when (balance.state) {
            BalanceState.DEBT -> Palette.debt
            BalanceState.CREDIT -> Palette.credit
            BalanceState.EVEN -> Palette.gold
        }

        fun runLine(report: Report, now: Instant): String {
            val miles = report.milesRunInWeekOf(now)
            return "${Format.miles(miles)} ${if (report.balance.state == BalanceState.DEBT) "paid" else "run"} this week"
        }

        /** Re-render every placed widget; called after each ledger save. */
        suspend fun refresh(context: Context) = BalanceWidget().updateAll(context)
    }
}

class BalanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BalanceWidget()
}
