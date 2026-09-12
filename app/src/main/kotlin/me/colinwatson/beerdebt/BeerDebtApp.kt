package me.colinwatson.beerdebt

import android.app.Application
import me.colinwatson.beerdebt.data.LedgerStore
import me.colinwatson.beerdebt.health.HealthSync
import me.colinwatson.beerdebt.notify.RunNotifier

/** Owns the services, the way `BeerDebtApp` does on iOS. No DI framework. */
class BeerDebtApp : Application() {
    lateinit var store: LedgerStore
        private set
    lateinit var sync: HealthSync
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = LedgerStore(filesDir.resolve("BeerDebt"))
        sync = HealthSync(this, store, RunNotifier(this))
        sync.scheduleBackgroundSync()
    }

    companion object {
        lateinit var instance: BeerDebtApp
            private set
    }
}
