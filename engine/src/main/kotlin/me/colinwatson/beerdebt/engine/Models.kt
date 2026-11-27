@file:UseSerializers(InstantSerializer::class, UUIDSerializer::class)

package me.colinwatson.beerdebt.engine

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    /**
     * The workout's type, as iOS's `HKWorkoutActivityType` raw value (37 is
     * running) so the ledger means the same on both platforms; Health
     * Connect exercise types are mapped onto it at import. Null means
     * running: every entry written before calorie mode (spec §28) was a run.
     */
    val activityType: Int? = null,
    /** Active energy burned, in kilocalories. Null when none was recorded. */
    val activeEnergyKcal: Double? = null,
) {
    val distanceMiles: Double get() = distanceMeters / METERS_PER_MILE

    val isRunning: Boolean get() = activityType == null || activityType == RUNNING_ACTIVITY_TYPE

    companion object {
        const val METERS_PER_MILE = 1609.344
        /** `HKWorkoutActivityType.running.rawValue`. */
        const val RUNNING_ACTIVITY_TYPE = 37
    }
}

/** What pays down the tab (spec §28). */
@Serializable
enum class PaymentMode {
    /** Running workouts, by distance. The default. */
    @SerialName("runs") RUNS,
    /** Every workout: runs by distance, everything else by active energy. */
    @SerialName("calories") CALORIES,
}

/**
 * The mode and the calorie rate together: everything the engine needs to turn
 * a workout into miles paid. Forward-only like the rules (iOS decisions
 * §B.10): [Ledger.paymentHistory] records when each basis took effect, and a
 * workout pays under the basis in force when it ended. A switch never changes
 * what an earlier workout paid.
 */
data class PaymentBasis(
    val mode: PaymentMode = PaymentMode.RUNS,
    /** Calories worth one mile. */
    val caloriesPerMile: Double = DEFAULT_CALORIES_PER_MILE,
) {
    /**
     * Miles [workout] pays, or null if it doesn't count in this mode. A run
     * pays its distance in both modes; anything else pays nothing in runs
     * mode and its active energy at the rate in calorie mode.
     */
    fun milesPaidBy(workout: RunEntry): Double? {
        if (workout.isRunning) return workout.distanceMiles
        if (mode != PaymentMode.CALORIES) return null
        if (caloriesPerMile <= 0) return 0.0
        return (workout.activeEnergyKcal ?: 0.0) / caloriesPerMile
    }

    companion object {
        /** About what a mile of running burns. */
        const val DEFAULT_CALORIES_PER_MILE = 100.0
        val RUNS = PaymentBasis()
    }
}

/** A payment basis, effective from [effectiveAt] forward (iOS decisions §B.10). */
@Serializable
data class PaymentChange(val effectiveAt: Instant, val mode: PaymentMode, val caloriesPerMile: Double) {
    constructor(effectiveAt: Instant, payment: PaymentBasis) : this(effectiveAt, payment.mode, payment.caloriesPerMile)

    val payment: PaymentBasis get() = PaymentBasis(mode, caloriesPerMile)
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

@OptIn(ExperimentalSerializationApi::class)
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
    /**
     * What pays the tab, and at what calorie rate (spec §28). Sorted by
     * effectiveAt; the first entry is the opening basis. Forward-only like
     * [rulesHistory]: a workout pays under the entry in force when it ended
     * (iOS decisions §B.10). Read it through [payments], which fills in a
     * ledger written before the history existed.
     */
    val paymentHistory: List<PaymentChange> = emptyList(),
    /**
     * Written by 1.2, when one basis reinterpreted the whole history. Read
     * only: it becomes the opening entry of [payments], and is never written.
     */
    @SerialName("paymentMode") @EncodeDefault(EncodeDefault.Mode.NEVER) val legacyPaymentMode: PaymentMode? = null,
    @SerialName("caloriesPerMile") @EncodeDefault(EncodeDefault.Mode.NEVER) val legacyCaloriesPerMile: Double? = null,
) {
    val currentRules: Rules get() = rulesHistory.lastOrNull()?.rules ?: Rules()

    /**
     * The payment history, never empty. A 1.2 ledger replayed everything under
     * its one basis, so that basis has been in force since the books opened
     * and nobody's numbers move on upgrade.
     */
    val payments: List<PaymentChange>
        get() = paymentHistory.ifEmpty {
            listOf(PaymentChange(booksOpenedAt, legacyPaymentMode ?: PaymentMode.RUNS,
                legacyCaloriesPerMile ?: PaymentBasis.DEFAULT_CALORIES_PER_MILE))
        }

    /** The basis in force now: what Settings shows and what amounts display in. */
    val payment: PaymentBasis get() = payments.last().payment

    /** The basis a workout that ended at [instant] pays under. */
    fun paymentAt(instant: Instant): PaymentBasis =
        (payments.lastOrNull { !it.effectiveAt.isAfter(instant) } ?: payments.first()).payment

    /** Miles [workout] pays under the basis in force when it ended, or null if that basis doesn't count it. */
    fun milesPaidBy(workout: RunEntry): Double? = paymentAt(workout.endedAt).milesPaidBy(workout)

    /**
     * Whether calorie mode has ever been in force. Once it has, Health Connect
     * is read for every exercise type whatever the mode now, so a ride that
     * ended in calorie mode but syncs after a switch back still lands.
     */
    val hasCaloriePeriod: Boolean get() = payments.any { it.mode == PaymentMode.CALORIES }

    /**
     * Replaces the history with [payment] from the day the books opened. Right
     * only when nothing has been paid yet (onboarding) and for fixtures; a
     * switch on live books is [switchingPayment].
     */
    fun withPayment(payment: PaymentBasis): Ledger =
        copy(paymentHistory = listOf(PaymentChange(booksOpenedAt, payment)), legacyPaymentMode = null, legacyCaloriesPerMile = null)

    /** [payment] from [at] forward, appended; at the same instant as the last change, it replaces it. */
    fun switchingPayment(payment: PaymentBasis, at: Instant): Ledger {
        var history = payments
        val effectiveAt = maxOf(at.floored(), history.last().effectiveAt)
        if (history.size > 1 && history.last().effectiveAt == effectiveAt) history = history.dropLast(1)
        if (history.last().payment != payment) history = history + PaymentChange(effectiveAt, payment)
        return copy(paymentHistory = history, legacyPaymentMode = null, legacyCaloriesPerMile = null)
    }

    companion object {
        fun open(at: Instant, rules: Rules = Rules.OPENING, payment: PaymentBasis = PaymentBasis.RUNS): Ledger =
            Ledger(booksOpenedAt = at, rulesHistory = listOf(RulesChange(at, rules)),
                paymentHistory = listOf(PaymentChange(at, payment)))
    }
}

/** Event timestamps are whole seconds so the JSON round-trips exactly. */
fun Instant.floored(): Instant = Instant.ofEpochSecond(epochSecond)
