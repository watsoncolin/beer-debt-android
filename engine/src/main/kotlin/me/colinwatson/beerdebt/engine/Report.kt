package me.colinwatson.beerdebt.engine

import java.time.Instant
import java.util.UUID

enum class BalanceState { CREDIT, EVEN, DEBT }

/** The headline numbers for one instant. All miles. */
data class Balance(
    val state: BalanceState,
    val debtMiles: Double,
    val principalMiles: Double,
    val interestMiles: Double,
    /**
     * What one compounding period adds to the open tab at the rate in force.
     * Every open debt posts exactly once per period, so this is the rate on
     * what is outstanding: exact, not a projection. The rate Home leads with,
     * since a total that barely moves says less than what standing still
     * costs. Struck through when [Report.streak].todayProtected says today's
     * postings are skipped, so the number doubles as what the streak is saving.
     * Reported gross: a protected day would otherwise report zero, which
     * leaves nothing to strike through and nothing to lose by stopping.
     */
    val interestPerPeriodMiles: Double,
    val creditMiles: Double,
    val creditBeers: Double,
) {
    companion object {
        val EVEN = Balance(BalanceState.EVEN, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/** One beer's line on the books. */
data class BeerStatement(
    val id: UUID,
    /** 1-based, in order of consumption. */
    val number: Int,
    val createdAt: Instant,
    val costMiles: Double,
    val coveredByCreditMiles: Double,
    val principalMiles: Double,
    val interestAccruedMiles: Double,
    val paidMiles: Double,
    val principalRemainingMiles: Double,
    val interestRemainingMiles: Double,
    val writtenOffMiles: Double,
    val paidAt: Instant?,
    val nextInterestAt: Instant?,
) {
    val outstandingMiles: Double get() = principalRemainingMiles + interestRemainingMiles
    val isPaid: Boolean get() = paidAt != null
    val settledByCredit: Boolean get() = paidAt == createdAt && coveredByCreditMiles > 0
}

/** One run's line on the books. */
data class RunStatement(
    val run: RunEntry,
    val debtPaidMiles: Double,
    val creditEarnedMiles: Double,
    val discardedMiles: Double,
    /** Ended before the books opened; contributes nothing. */
    val ignored: Boolean,
    /** Which day of a two-or-more-day streak this run's day was; null for a short day or a lone mile. */
    val streakDayNumber: Int? = null,
) {
    val id: UUID get() = run.id
}

data class Report(
    val at: Instant,
    val balance: Balance,
    /** Chronological, oldest first. */
    val beers: List<BeerStatement>,
    /** Chronological by end time. */
    val runs: List<RunStatement>,
    val rules: Rules,
    val nextInterestAt: Instant?,
    val creditExpiringThisWeekMiles: Double,
    /** The running streak as of [at] (spec §25). */
    val streak: StreakStatus,
) {
    fun statement(beerID: UUID): BeerStatement? = beers.firstOrNull { it.id == beerID }
}
