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

    private fun write(ledger: Ledger) {
        val tmp = File(directory, "ledger.json.tmp")
        tmp.writeText(json.encodeToString(ledger))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        onChange?.invoke()
    }

    /** Hook for widget refresh etc. */
    var onChange: (() -> Unit)? = null

    companion object {
        const val BACKDATING_WINDOW_SECONDS = 30L * 24 * 60 * 60
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** Event timestamps are whole seconds so the JSON round-trips exactly. */
fun Instant.floored(): Instant = Instant.ofEpochSecond(epochSecond)
