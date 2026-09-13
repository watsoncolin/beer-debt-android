package me.colinwatson.beerdebt

import android.app.Application
import me.colinwatson.beerdebt.data.LedgerStore
import me.colinwatson.beerdebt.health.HealthSync
import me.colinwatson.beerdebt.notify.RunNotifier
import me.colinwatson.beerdebt.telemetry.Telemetry
import me.colinwatson.beerdebt.widget.BalanceWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Owns the services, the way `BeerDebtApp` does on iOS. No DI framework. */
class BeerDebtApp : Application() {
    lateinit var store: LedgerStore
        private set
    lateinit var sync: HealthSync
        private set
    lateinit var notifier: RunNotifier
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Telemetry.configure(this)
        store = LedgerStore(filesDir.resolve("BeerDebt"))
        store.loadFailure?.let { Telemetry.report(it, context = "store", values = mapOf("action" to "load")) }
        notifier = RunNotifier(this)
        sync = HealthSync(this, store, notifier)
        sync.scheduleBackgroundSync()
        store.onChange = { scope.launch { BalanceWidget.refresh(this@BeerDebtApp) } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        lateinit var instance: BeerDebtApp
            private set
    }
}
