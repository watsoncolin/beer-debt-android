package me.colinwatson.beerdebt.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.colinwatson.beerdebt.BeerDebtApp
import java.time.Instant

/** Fires at the chosen weekly time: post the recap for the ledger as it stands, then queue next week's. */
class WeeklySummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = BeerDebtApp.instance
        val weekly = app.sync.weekly
        if (weekly.enabled) {
            app.sync.syncIfConnected()   // the week's last run may not have been pulled yet
            app.notifier.post(WeeklySummary.message(app.store.current, Instant.now()), RunNotifier.CHANNEL_WEEKLY)
            weekly.reschedule()
        }
        return Result.success()
    }
}
