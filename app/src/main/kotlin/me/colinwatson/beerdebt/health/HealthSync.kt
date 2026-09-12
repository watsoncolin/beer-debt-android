package me.colinwatson.beerdebt.health

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import me.colinwatson.beerdebt.data.LedgerStore
import me.colinwatson.beerdebt.engine.BalanceState
import me.colinwatson.beerdebt.notify.RunNotifier
import me.colinwatson.beerdebt.notify.WeeklySummary
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

@Serializable
data class DebtFreeCelebration(val totalBeers: Int, val milesRepaid: Double, val creditBeers: Double)

data class SyncState(
    val isConnected: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncAt: Instant? = null,
    val lastError: String? = null,
    val runNotificationsEnabled: Boolean = false,
    val celebration: DebtFreeCelebration? = null,
)

/**
 * Drives Health Connect into the ledger: connect on onboarding, sync when
 * the app comes to the foreground, and periodically in the background
 * (Health Connect has no push, so WorkManager polls the Changes API).
 */
class HealthSync(context: Context, private val store: LedgerStore, private val notifier: RunNotifier) {
    private val app = context.applicationContext
    val health = HealthConnectService(app)
    /** The weekly recap; owned here as on iOS so Settings has one place to reach it. */
    val weekly = WeeklySummary(app)
    private val prefs = app.getSharedPreferences("health", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        SyncState(
            isConnected = prefs.getBoolean(KEY_CONNECTED, false),
            runNotificationsEnabled = prefs.getBoolean(KEY_NOTIFY, false),
        )
    )
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** True while the app is in the foreground; syncs then update the UI instead of notifying. */
    @Volatile var isAppActive = false

    val isAvailable: Boolean get() = health.isAvailable

    /** Called after the permission dialog returns. */
    suspend fun connected() {
        val ok = health.hasPermissions()
        prefs.edit().putBoolean(KEY_CONNECTED, ok).apply()
        _state.update { it.copy(isConnected = ok, lastError = if (ok) null else "Health Connect access wasn't granted.") }
        if (ok) { scheduleBackgroundSync(); sync() }
    }

    fun setRunNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY, enabled).apply()
        _state.update { it.copy(runNotificationsEnabled = enabled) }
    }

    fun clearCelebration() = _state.update { it.copy(celebration = null) }

    fun showPendingCelebration() {
        val raw = prefs.getString(KEY_PENDING_CELEBRATION, null) ?: return
        prefs.edit().remove(KEY_PENDING_CELEBRATION).apply()
        runCatching { Json.decodeFromString<DebtFreeCelebration>(raw) }.getOrNull()?.let { party ->
            _state.update { it.copy(celebration = party) }
        }
    }

    suspend fun syncIfConnected() { if (_state.value.isConnected) sync() }

    suspend fun sync() {
        if (!_state.value.isConnected || _state.value.isSyncing) return
        _state.update { it.copy(isSyncing = true) }
        try {
            val result = health.fetchRunningWorkouts(prefs.getString(KEY_TOKEN, null), store.current.booksOpenedAt)
            val before = store.report()
            val eligible = result.runs.filter { it.endedAt >= store.current.booksOpenedAt }
            val added = store.importRuns(eligible)
            val removed = store.removeRuns(result.deletedWorkoutIDs.toSet())
            prefs.edit().putString(KEY_TOKEN, result.token).apply()
            _state.update { it.copy(lastSyncAt = Instant.now(), lastError = null) }

            if (added.isEmpty() && removed == 0) return
            val after = store.report()

            if (added.isNotEmpty() && before.balance.state == BalanceState.DEBT && after.balance.state != BalanceState.DEBT) {
                val party = DebtFreeCelebration(after.beers.size, after.runs.sumOf { it.debtPaidMiles }, after.balance.creditBeers)
                if (isAppActive) _state.update { it.copy(celebration = party) }
                else prefs.edit().putString(KEY_PENDING_CELEBRATION, Json.encodeToString(party)).apply()
            }
            if (!isAppActive && _state.value.runNotificationsEnabled) {
                val change = RunNotifier.Change(
                    addedRuns = added, removedRuns = removed, before = before.balance, after = after.balance,
                    beersPaidOff = maxOf(0, after.beers.count { it.isPaid } - before.beers.count { it.isPaid }),
                )
                RunNotifier.message(change)?.let(notifier::post)
            }
        } catch (e: Exception) {
            _state.update { it.copy(lastError = e.message ?: e.toString()) }
        } finally {
            _state.update { it.copy(isSyncing = false) }
        }
    }

    /** Polls Health Connect roughly hourly so a run pays the tab while the app is closed. */
    fun scheduleBackgroundSync() {
        if (!_state.value.isConnected) return
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork("health-sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        private const val KEY_CONNECTED = "connected"
        private const val KEY_TOKEN = "changesToken"
        private const val KEY_NOTIFY = "runNotifications"
        private const val KEY_PENDING_CELEBRATION = "pendingCelebration"
    }
}
