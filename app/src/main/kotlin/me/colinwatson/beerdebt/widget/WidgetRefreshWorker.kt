package me.colinwatson.beerdebt.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Re-renders the widget at the instant interest posts. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        BalanceWidget.refresh(applicationContext)
        return Result.success()
    }
}
