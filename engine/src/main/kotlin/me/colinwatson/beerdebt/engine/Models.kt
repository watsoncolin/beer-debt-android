@file:UseSerializers(InstantSerializer::class, UUIDSerializer::class)

package me.colinwatson.beerdebt.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.ZoneId
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

    /** "10% a day" — the words the per-period interest figure carries. */
    val perLabel: String
        get() = when (this) {
            DAILY -> "a day"
            WEEKLY -> "a week"
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
    /**
     * A running streak of two or more days pauses debt interest (spec §25).
     * Absent in ledgers written before the feature, which decode as off so
     * their history stands; the store turns it on forward-only at upgrade.
     * New ledgers open with [OPENING], which has it on.
     */
    val streakProtection: Boolean = false,
) {
    val interestEnabled: Boolean get() = interestRate > 0
    val creditDecayEnabled: Boolean get() = creditDecayRatePerWeek > 0
    val creditCapMiles: Double get() = maximumCreditBeers * milesPerBeer

    /** Interest can post neither before grace ends nor before a full period has elapsed. */
    val firstPostingDelay: Double get() = maxOf(gracePeriod, interestPeriod.seconds)

    companion object {
        /** The rules a new ledger opens with (iOS `Rules.default`). */
        val OPENING = Rules(streakProtection = true)
    }
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

/**
 * The user's decision to spend a streak freeze on one calendar day
 * (spec §25.1, APPS-6). Append-only and immutable like every other event.
 *
 * This is the *only* freeze state on disk. How many freezes have been earned,
 * which applications were honoured, and which were refunded are all derived
 * from the runs by [StreakEngine] every replay -- because all three change
 * when the runs do. A late Health Connect import that brings a frozen day to a
 * mile makes that day qualify on its own, so its application is simply ignored
 * and the freeze is back in inventory: the refund needs no event and no
 * reconciliation pass.
 */
@Serializable
data class FreezeApplication(
    val id: UUID,
    /**
     * An instant inside the frozen local day -- midday, not midnight.
     *
     * The engine re-buckets this through whatever zone it is replaying with,
     * exactly as it buckets a run by `endedAt`. Midnight would sit on a day
     * boundary, so a user who crossed a time zone between the tap and the
     * replay would see the freeze slide onto the day before.
     */
    val day: Instant,
    /** When the user tapped; the engine keys off [day]. */
    val appliedAt: Instant,
) {
    companion object {
        /** The application for the local day containing [instant], keyed to midday. */
        fun forDay(containing: Instant, zone: ZoneId, appliedAt: Instant, id: UUID = UUID.randomUUID()) =
            FreezeApplication(
                id = id,
                day = containing.atZone(zone).toLocalDate().atTime(12, 0).atZone(zone).toInstant().floored(),
                appliedAt = appliedAt.floored(),
            )
    }
}

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
    /**
     * Days the user spent a streak freeze on (spec §25.1). The only freeze
     * state stored; everything else about freezes is derived from the runs.
     * Absent in every ledger written before freezes existed.
     */
    val freezeApplications: List<FreezeApplication> = emptyList(),
) {
    val currentRules: Rules get() = rulesHistory.lastOrNull()?.rules ?: Rules()

    companion object {
        fun open(at: Instant, rules: Rules = Rules.OPENING): Ledger =
            Ledger(booksOpenedAt = at, rulesHistory = listOf(RulesChange(at, rules)))
    }
}

/** Event timestamps are whole seconds so the JSON round-trips exactly. */
fun Instant.floored(): Instant = Instant.ofEpochSecond(epochSecond)
