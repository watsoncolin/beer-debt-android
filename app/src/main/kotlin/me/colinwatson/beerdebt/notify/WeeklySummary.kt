package me.colinwatson.beerdebt.notify

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import me.colinwatson.beerdebt.engine.BalanceEngine
import me.colinwatson.beerdebt.engine.BalanceState
import me.colinwatson.beerdebt.engine.Ledger
import me.colinwatson.beerdebt.ui.Format
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * A once-a-week local notification with the week's beers, miles, and where
 * the tab stands; the port of iOS `WeeklySummary`. iOS has to pre-compute the
 * copy for the next four fire dates; here `WeeklySummaryWorker` runs at the
 * fire time and builds the copy from the ledger as it is then, so it is
 * always exact and nothing needs rescheduling when the ledger changes.
 */
class WeeklySummary(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("weekly", Context.MODE_PRIVATE)

    val enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)

    /** 1 = Sunday … 7 = Saturday, the iOS `Calendar` numbering, so the preference is portable. */
    var weekday: Int
        get() = prefs.getInt(KEY_WEEKDAY, 1)
        set(value) { prefs.edit().putInt(KEY_WEEKDAY, value.coerceIn(1, 7)).apply(); reschedule() }
    var hour: Int
        get() = prefs.getInt(KEY_HOUR, 18)
        set(value) { prefs.edit().putInt(KEY_HOUR, value.coerceIn(0, 23)).apply(); reschedule() }
    var minute: Int
        get() = prefs.getInt(KEY_MINUTE, 0)
        set(value) { prefs.edit().putInt(KEY_MINUTE, value.coerceIn(0, 59)).apply(); reschedule() }

    fun setEnabled(on: Boolean) { prefs.edit().putBoolean(KEY_ENABLED, on).apply(); reschedule() }

    /** Queues the next fire time as one-off work, or clears it when off. */
    fun reschedule(now: Instant = Instant.now()) {
        val manager = WorkManager.getInstance(app)
        if (!enabled) { manager.cancelUniqueWork(WORK); return }
        val next = nextFireDates(now, weekday, hour, minute, 1).first()
        val request = OneTimeWorkRequestBuilder<WeeklySummaryWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val WORK = "weekly-summary"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_WEEKDAY = "weekday"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"

        val weekdayNames: List<String> = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

        /** The next [count] instants matching weekday/hour/minute after [now], strictly later than it. */
        fun nextFireDates(now: Instant, weekday: Int, hour: Int, minute: Int, count: Int, zone: ZoneId = ZoneId.systemDefault()): List<Instant> {
            val day = DayOfWeek.of(if (weekday == 1) 7 else weekday - 1)   // iOS 1=Sunday → java SUNDAY(7)
            val time = LocalTime.of(hour, minute)
            val dates = mutableListOf<Instant>()
            var cursor = now.atZone(zone)
            while (dates.size < count) {
                var candidate = cursor.with(TemporalAdjusters.nextOrSame(day)).with(time)
                if (!candidate.toInstant().isAfter(cursor.toInstant())) candidate = cursor.with(TemporalAdjusters.next(day)).with(time)
                dates += candidate.toInstant()
                cursor = candidate
            }
            return dates
        }

        /** Pure, so the copy can be tested. Same words as iOS. */
        fun message(ledger: Ledger, fireDate: Instant): RunNotifier.Message {
            val weekStart = fireDate.minusSeconds(7 * 86_400)
            val beers = ledger.beers.count { it.createdAt >= weekStart && it.createdAt < fireDate }
            val miles = ledger.runs
                .filter { it.endedAt >= weekStart && it.endedAt < fireDate && it.endedAt >= ledger.booksOpenedAt }
                .sumOf { it.distanceMiles }
            val balance = BalanceEngine.report(ledger, fireDate).balance

            val standing = when (balance.state) {
                BalanceState.DEBT -> "You're at ${Format.miles(balance.debtMiles)} owed."
                BalanceState.CREDIT -> "You've got ${Format.beersLabel(balance.creditBeers)} banked."
                BalanceState.EVEN -> "Books are clean."
            }
            val tally = "${if (beers == 1) "1 beer" else "$beers beers"}, ${Format.miles(miles)} run."
            val body = when {
                beers == 0 && miles < 0.05 -> "$tally Nothing to report. Suspicious."
                beers > 0 && miles < 0.05 -> "$tally The running part is optional, apparently. $standing"
                else -> "$tally $standing"
            }
            return RunNotifier.Message("Your week in beer", body)
        }
    }
}
