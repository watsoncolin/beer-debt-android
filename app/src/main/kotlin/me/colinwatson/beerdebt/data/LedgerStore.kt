package me.colinwatson.beerdebt.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import me.colinwatson.beerdebt.engine.BalanceEngine
import me.colinwatson.beerdebt.engine.BeerEntry
import me.colinwatson.beerdebt.engine.Ledger
import me.colinwatson.beerdebt.engine.Report
import me.colinwatson.beerdebt.engine.Rules
import me.colinwatson.beerdebt.engine.RulesChange
import me.colinwatson.beerdebt.engine.RunEntry
import me.colinwatson.beerdebt.telemetry.Telemetry
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Owns the persisted ledger and derives reports; a port of the Swift
 * `LedgerStore`. One JSON file, the same shape iOS writes, written atomically.
 */
class LedgerStore(private val directory: File, now: Instant = Instant.now()) {
    private val file = File(directory, "ledger.json")
    private val _ledger = MutableStateFlow(load(now))
    val ledger: StateFlow<Ledger> = _ledger.asStateFlow()

    init {
        // Streak protection arrived after 1.0. Ledgers from before decode it as
        // off; switch it on from now, as a rules change, so history stands.
        if (!current.currentRules.streakProtection) updateRules(current.currentRules.copy(streakProtection = true), now)
    }

    /** Set when an existing file couldn't be read; it is kept, renamed. */
    var loadError: String? = null
        private set

    private fun load(now: Instant): Ledger {
        directory.mkdirs()
        if (!file.exists()) return Ledger.open(now.floored()).also { write(it) }
        return runCatching { json.decodeFromString<Ledger>(file.readText()) }.getOrElse { error ->
            val aside = File(directory, "ledger-unreadable-${now.epochSecond}.json")
            file.renameTo(aside)
            loadError = "Couldn't read the ledger, so the books were reopened. The old file was kept as ${aside.name}."
            loadFailure = error
            Ledger.open(now.floored()).also { write(it) }
        }
    }

    val current: Ledger get() = _ledger.value
    val currentRules: Rules get() = current.currentRules

    fun report(at: Instant = Instant.now()): Report = BalanceEngine.report(current, at)

    // MARK: Events

    fun addBeer(at: Instant = Instant.now()): BeerEntry {
        val beer = BeerEntry(id = UUID.randomUUID(), createdAt = at.floored(), recordedAt = at.floored())
        mutate { it.copy(beers = it.beers + beer) }
        return beer
    }

    fun beer(id: UUID): BeerEntry? = current.beers.firstOrNull { it.id == id }

    /** Clamped to the last 30 days and no later than now; may predate the books. */
    fun updateBeerDate(id: UUID, to: Instant, now: Instant = Instant.now()): BeerEntry? {
        val index = current.beers.indexOfFirst { it.id == id }
        if (index < 0) return null
        val earliest = earliestBeerDate(now)
        val clamped = maxOf(minOf(to.floored(), now.floored()), earliest)
        val updated = current.beers[index].copy(createdAt = clamped)
        if (updated != current.beers[index]) mutate { l -> l.copy(beers = l.beers.mapIndexed { i, b -> if (i == index) updated else b }) }
        return updated
    }

    fun earliestBeerDate(now: Instant = Instant.now()): Instant = now.minusSeconds(BACKDATING_WINDOW_SECONDS).floored()

    fun removeBeer(id: UUID): Boolean {
        if (current.beers.none { it.id == id }) return false
        mutate { it.copy(beers = it.beers.filterNot { b -> b.id == id }) }
        return true
    }

    /** Idempotent; returns the runs that were new. Excluded workouts are refused. */
    fun importRuns(runs: List<RunEntry>): List<RunEntry> {
        val known = current.runs.map { it.healthKitWorkoutID }.toSet()
        val added = runs.filter { it.healthKitWorkoutID !in known && it.healthKitWorkoutID !in current.excludedWorkoutIDs }
            .distinctBy { it.healthKitWorkoutID }
        if (added.isNotEmpty()) mutate { it.copy(runs = it.runs + added) }
        return added
    }

    /** Workouts deleted from Health Connect come off the books. */
    fun removeRuns(workoutIDs: Set<UUID>): Int {
        if (workoutIDs.isEmpty()) return 0
        val before = current.runs.size
        mutate { it.copy(runs = it.runs.filterNot { r -> r.healthKitWorkoutID in workoutIDs }) }
        return before - current.runs.size
    }

    /** The user's choice: off the books and never re-imported. */
    fun deleteRun(id: UUID): Boolean {
        val run = current.runs.firstOrNull { it.id == id } ?: return false
        mutate { it.copy(runs = it.runs.filterNot { r -> r.id == id }, excludedWorkoutIDs = it.excludedWorkoutIDs + run.healthKitWorkoutID) }
        return true
    }

    /**
     * Moves the opening of the books earlier so runs from the newly covered
     * days can count. Earlier only, a year at most; the opening rules move
     * with it. The caller re-reads Health Connect from scratch afterwards.
     */
    fun reopenBooks(at: Instant, now: Instant = Instant.now()): Boolean {
        val earliest = now.minusSeconds(REOPENING_WINDOW_SECONDS).floored()
        val opened = maxOf(at.floored(), earliest)
        if (!opened.isBefore(current.booksOpenedAt)) return false
        val previous = current.booksOpenedAt
        mutate { l ->
            val history = l.rulesHistory.toMutableList()
            if (history.isNotEmpty() && history[0].effectiveAt == previous) history[0] = RulesChange(opened, history[0].rules)
            l.copy(booksOpenedAt = opened, rulesHistory = history)
        }
        return true
    }

    /** Forward-only: appended as an event, never rewrites history. */
    fun updateRules(rules: Rules, at: Instant = Instant.now()) {
        if (rules == currentRules) return
        val last = current.rulesHistory.lastOrNull()?.effectiveAt ?: current.booksOpenedAt
        val effectiveAt = maxOf(at.floored(), last)
        mutate { it.copy(rulesHistory = it.rulesHistory + RulesChange(effectiveAt, rules)) }
    }

    private fun mutate(transform: (Ledger) -> Ledger) {
        _ledger.update(transform)
        write(_ledger.value)
    }

    /**
     * False when the file no longer matches memory, i.e. the last write
     * failed. Mirrors iOS `LedgerStore.isPersisted`.
     */
    var isPersisted: Boolean = true
        private set

    /**
     * Retries a failed write and reports whether the file now matches memory.
     * A no-op when nothing is outstanding, so it is cheap to call often.
     *
     * Call it before throwing away the only record of how to rebuild what was
     * written, the way the Health Connect changes token is the only record of
     * which workouts have been read. A disk write can fail for reasons the app
     * does not control, and the failure is silent: the books look right until
     * the next launch reads the file back.
     */
    fun persist(): Boolean {
        if (!isPersisted) write(_ledger.value)
        return isPersisted
    }

    private fun write(ledger: Ledger) {
        val tmp = File(directory, "ledger.json.tmp")
        try {
            tmp.writeText(json.encodeToString(ledger))
            // Never delete the file we still have. The previous version of
            // this did `file.delete(); tmp.renameTo(file)` as a fallback, so a
            // second failed rename left no ledger at all -- the whole history
            // gone, silently. A rename onto an existing path is atomic on the
            // app's own filesystem anyway; if it fails, copy into place and
            // keep the old bytes until the new ones are written.
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            isPersisted = true
        } catch (e: Exception) {
            // Not a crash: a full or read-only filesystem is the environment
            // misbehaving, not a bug here. It is recorded, retried by
            // [persist], and surfaced to callers that would otherwise lose data.
            isPersisted = false
            Telemetry.report(e, context = "store", values = mapOf(
                "op" to "write", "beers" to ledger.beers.size, "runs" to ledger.runs.size,
            ))
        }
        onChange?.invoke()
    }

    /** Hook for widget refresh etc. */
    var onChange: (() -> Unit)? = null
    /** The exception behind [loadError], for the app to report. */
    var loadFailure: Throwable? = null
        private set

    companion object {
        const val BACKDATING_WINDOW_SECONDS = 30L * 24 * 60 * 60
        const val REOPENING_WINDOW_SECONDS = 365L * 24 * 60 * 60
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** Event timestamps are whole seconds so the JSON round-trips exactly. */
fun Instant.floored(): Instant = Instant.ofEpochSecond(epochSecond)
