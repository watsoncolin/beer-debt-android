package me.colinwatson.beerdebt.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.colinwatson.beerdebt.BeerDebtApp

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        BeerDebtApp.instance.sync.syncIfConnected()
        return Result.success()
    }
}
