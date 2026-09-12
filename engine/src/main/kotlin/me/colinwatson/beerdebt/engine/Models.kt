@file:UseSerializers(InstantSerializer::class, UUIDSerializer::class)

package me.colinwatson.beerdebt.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

/**
 * The ledger model, field for field the iOS app's `Ledger` (see
 * beer-debt-ios/docs/decisions.md §B). The JSON is shared: an iOS
 * ledger.json decodes here unchanged, and vice versa.
 */

@Serializable
enum class CompoundingPeriod {
    @SerialName("daily") DAILY,
    @SerialName("weekly") WEEKLY;

    /** Fixed lengths, not calendar units. */
    val seconds: Double
        get() = when (this) {
            DAILY -> 24 * 60 * 60.0
            WEEKLY -> 7 * 24 * 60 * 60.0
        }
}

@Serializable
data class Rules(
    val milesPerBeer: Double = 1.0,
    /** Per compounding period on the outstanding amount. 0 = off. */
    val interestRate: Double = 0.10,
    val interestPeriod: CompoundingPeriod = CompoundingPeriod.DAILY,
    /** Seconds after a beer before interest can post. */
    val gracePeriod: Double = 24 * 60 * 60.0,
    val maximumCreditBeers: Double = 3.0,
    /** Fraction of banked credit lost per week, applied continuously. 0 = off. */
    val creditDecayRatePerWeek: Double = 0.10,
) {
    val interestEnabled: Boolean get() = interestRate > 0
    val creditDecayEnabled: Boolean get() = creditDecayRatePerWeek > 0
    val creditCapMiles: Double get() = maximumCreditBeers * milesPerBeer

    /** Interest can post neither before grace ends nor before a full period has elapsed. */
    val firstPostingDelay: Double get() = maxOf(gracePeriod, interestPeriod.seconds)
}

@Serializable
data class BeerEntry(
    val id: UUID,
    /** When the beer happened; its position on the timeline. */
    val createdAt: Instant,
    /** When it was logged. Missing in older files. */
    val recordedAt: Instant? = null,
) {
    val isBackdated: Boolean get() = recordedAt != null && recordedAt != createdAt
}

@Serializable
data class RunEntry(
    val id: UUID,
    /** The platform workout id (HealthKit UUID on iOS; a UUID derived from the Health Connect record id here). Dedup key. */
    val healthKitWorkoutID: UUID,
    val startedAt: Instant,
    val endedAt: Instant,
    val distanceMeters: Double,
    val importedAt: Instant,
    val sourceName: String? = null,
) {
    val distanceMiles: Double get() = distanceMeters / METERS_PER_MILE

    companion object {
        const val METERS_PER_MILE = 1609.344
    }
}

@Serializable
data class RulesChange(val effectiveAt: Instant, val rules: Rules)

@Serializable
data class Ledger(
    val version: Int = 1,
    /** First launch. Runs that ended before this are ignored. */
    val booksOpenedAt: Instant,
    /** Sorted by effectiveAt; the first entry is the opening rules. */
    val rulesHistory: List<RulesChange>,
    val beers: List<BeerEntry> = emptyList(),
    val runs: List<RunEntry> = emptyList(),
    /** Workouts the user took off the books in the app; never re-imported. */
    val excludedWorkoutIDs: Set<UUID> = emptySet(),
) {
    val currentRules: Rules get() = rulesHistory.lastOrNull()?.rules ?: Rules()

    companion object {
        fun open(at: Instant, rules: Rules = Rules()): Ledger =
            Ledger(booksOpenedAt = at, rulesHistory = listOf(RulesChange(at, rules)))
    }
}
