package me.colinwatson.beerdebt.engine

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Pure, deterministic ledger replay; a port of the Swift `BalanceEngine`.
 * The rules are written up in beer-debt-ios/docs/decisions.md §B, and the
 * fixtures in src/test/resources/cases.json pin this port to the Swift
 * engine's numbers.
 *
 * Time is Double seconds since the epoch inside the replay, exactly as the
 * Swift engine does its arithmetic, so the two agree to floating-point
 * precision. Interest postings that land on an interest-protected streak day
 * (spec §25) are skipped while the `streakProtection` rule is on; streak days
 * are calendar days in [zone].
 */
object BalanceEngine {
    const val EPSILON = 1e-9
    const val WEEK = 7 * 24 * 60 * 60.0
    /** Below this much banked credit the books read as clean. */
    const val EVEN_THRESHOLD_BEERS = 0.05
    /** A remnant smaller than this left after a run or a credit cover is written off. */
    const val WRITE_OFF_THRESHOLD_MILES = 0.05

    fun report(ledger: Ledger, at: Instant, zone: ZoneId = ZoneId.systemDefault()): Report = Replay(ledger, at.seconds, zone).run()
}

internal val Instant.seconds: Double get() = epochSecond.toDouble() + nano / 1e9

internal fun Double.toInstant(): Instant {
    val whole = floor(this)
    return Instant.ofEpochSecond(whole.toLong(), ((this - whole) * 1e9).roundToLong())
}

/** Swift's `String(Double)`: "1789243200.0" for whole numbers. Only used to break same-instant ties. */
private fun Double.swiftString(): String =
    if (this == floor(this) && !isInfinite()) "${toLong()}.0" else toString()

private sealed class Event(val time: Double, val rank: Int, val tieBreaker: String) {
    class RulesEvent(val change: RulesChange) : Event(change.effectiveAt.seconds, 0, change.effectiveAt.seconds.swiftString())
    class BeerEvent(val beer: BeerEntry) : Event(beer.createdAt.seconds, 1, beer.id.toString().uppercase())
    class RunEvent(val run: RunEntry) : Event(run.endedAt.seconds, 2, run.id.toString().uppercase())
}

private class DebtAccount(
    val beerID: UUID,
    val createdAt: Double,
    val costMiles: Double,
    val coveredByCredit: Double,
    val principalOriginal: Double,
    var principalRemaining: Double,
    var nextPostingAt: Double,
) {
    var interestRemaining = 0.0
    var interestAccrued = 0.0
    var paid = 0.0
    var lastPostingAt: Double? = null
    var paidAt: Double? = null
    var writtenOff = 0.0

    val outstanding: Double get() = principalRemaining + interestRemaining

    fun settle(at: Double) {
        writtenOff += outstanding
        principalRemaining = 0.0
        interestRemaining = 0.0
        paidAt = at
    }
}

private class Replay(private val ledger: Ledger, private val now: Double, zone: ZoneId) {
    private var rules: Rules = ledger.rulesHistory.firstOrNull()?.rules ?: Rules()
    private val streak: StreakStatus = StreakEngine.calculate(
        ledger.runs.filter { it.endedAt.seconds >= ledger.booksOpenedAt.seconds }, now.toInstant(), zone,
    )
    private val open = mutableListOf<DebtAccount>()
    private val closed = mutableListOf<DebtAccount>()
    private var credit = 0.0
    private var clock: Double = minOf(ledger.booksOpenedAt.seconds, now)
    private val runStatements = mutableListOf<RunStatement>()

    fun run(): Report {
        val events: List<Event> = (ledger.rulesHistory.map { Event.RulesEvent(it) } +
            ledger.beers.map { Event.BeerEvent(it) } +
            ledger.runs.map { Event.RunEvent(it) })
            .filter { it.time <= now }
            .sortedWith(compareBy<Event> { it.time }.thenBy { it.rank }.thenBy { it.tieBreaker })

        for (event in events) {
            advance(event.time)
            when (event) {
                is Event.RulesEvent -> apply(event.change)
                is Event.BeerEvent -> apply(event.beer)
                is Event.RunEvent -> apply(event.run)
            }
        }
        advance(now)
        return summary()
    }

    /** Bring every open debt and the credit pool forward to [t]. */
    private fun advance(t: Double) {
        if (t < clock) return
        for (account in open) {
            while (account.nextPostingAt <= t) {
                val paused = rules.streakProtection && streak.isProtected(account.nextPostingAt.toInstant())
                if (rules.interestEnabled && !paused) {
                    val step = account.outstanding * rules.interestRate
                    account.interestRemaining += step
                    account.interestAccrued += step
                }
                account.lastPostingAt = account.nextPostingAt
                account.nextPostingAt = account.nextPostingAt + rules.interestPeriod.seconds
            }
        }
        if (t > clock && credit > 0 && rules.creditDecayEnabled) {
            val weeks = (t - clock) / BalanceEngine.WEEK
            credit *= (1 - rules.creditDecayRatePerWeek).pow(weeks)
            if (credit < BalanceEngine.EPSILON) credit = 0.0
        }
        clock = t
    }

    private fun apply(change: RulesChange) {
        rules = change.rules
        val effectiveAt = change.effectiveAt.seconds
        for (account in open) {
            val last = account.lastPostingAt
            val scheduled = if (last != null) last + rules.interestPeriod.seconds
                            else account.createdAt + rules.firstPostingDelay
            account.nextPostingAt = maxOf(scheduled, effectiveAt)
        }
    }

    private fun apply(beer: BeerEntry) {
        val createdAt = beer.createdAt.seconds
        val cost = rules.milesPerBeer
        val fromCredit = minOf(credit, cost)
        credit -= fromCredit
        if (credit < BalanceEngine.EPSILON) credit = 0.0
        val remainder = cost - fromCredit

        val account = DebtAccount(
            beerID = beer.id, createdAt = createdAt, costMiles = cost, coveredByCredit = fromCredit,
            principalOriginal = remainder, principalRemaining = remainder,
            nextPostingAt = createdAt + rules.firstPostingDelay,
        )
        if (remainder > BalanceEngine.WRITE_OFF_THRESHOLD_MILES) {
            open.add(account)
        } else {
            account.settle(createdAt)
            closed.add(account)
        }
    }

    private fun apply(run: RunEntry) {
        val endedAt = run.endedAt.seconds
        if (endedAt < ledger.booksOpenedAt.seconds) {
            runStatements.add(RunStatement(run, 0.0, 0.0, 0.0, ignored = true, streakDayNumber = null))
            return
        }
        var miles = run.distanceMiles
        var debtPaid = 0.0
        var i = 0
        while (miles > BalanceEngine.EPSILON && i < open.size) {
            val account = open[i]
            val payment = minOf(miles, account.outstanding)
            val toInterest = minOf(payment, account.interestRemaining)
            account.interestRemaining = maxOf(0.0, account.interestRemaining - toInterest)
            account.principalRemaining = maxOf(0.0, account.principalRemaining - (payment - toInterest))
            account.paid += payment
            miles -= payment
            debtPaid += payment
            if (account.outstanding <= BalanceEngine.WRITE_OFF_THRESHOLD_MILES) {
                open.removeAt(i)
                account.settle(endedAt)
                closed.add(account)
            } else {
                i += 1
            }
        }
        val leftover = maxOf(miles, 0.0)
        val room = maxOf(0.0, rules.creditCapMiles - credit)
        val earned = minOf(leftover, room)
        credit += earned
        runStatements.add(RunStatement(run, debtPaid, earned, leftover - earned, ignored = false, streakDayNumber = streak.streakDayNumber(run.endedAt)))
    }

    private fun summary(): Report {
        val accounts = (open + closed).sortedWith(
            compareBy<DebtAccount> { it.createdAt }.thenBy { it.beerID.toString().uppercase() }
        )
        val statements = accounts.mapIndexed { index, a ->
            BeerStatement(
                id = a.beerID, number = index + 1, createdAt = a.createdAt.toInstant(),
                costMiles = a.costMiles, coveredByCreditMiles = a.coveredByCredit, principalMiles = a.principalOriginal,
                interestAccruedMiles = a.interestAccrued, paidMiles = a.paid,
                principalRemainingMiles = a.principalRemaining, interestRemainingMiles = a.interestRemaining,
                writtenOffMiles = a.writtenOff, paidAt = a.paidAt?.toInstant(),
                nextInterestAt = if (a.paidAt == null && rules.interestEnabled) a.nextPostingAt.toInstant() else null,
            )
        }
        val debt = open.sumOf { it.outstanding }
        val principal = open.sumOf { it.principalRemaining }
        val interest = open.sumOf { it.interestRemaining }
        val creditBeers = if (rules.milesPerBeer > 0) credit / rules.milesPerBeer else 0.0
        val state = when {
            debt > BalanceEngine.EPSILON -> BalanceState.DEBT
            creditBeers >= BalanceEngine.EVEN_THRESHOLD_BEERS -> BalanceState.CREDIT
            else -> BalanceState.EVEN
        }
        return Report(
            at = now.toInstant(),
            // Each open debt posts once a period, so the whole tab's per-period
            // charge is just the rate on what's outstanding. Gross: a protected
            // day skips the posting, and the UI says so.
            balance = Balance(state, debt, principal, interest, debt * rules.interestRate, credit, creditBeers),
            beers = statements,
            runs = runStatements.sortedBy { it.run.endedAt.seconds },
            rules = rules,
            nextInterestAt = if (rules.interestEnabled) open.minOfOrNull { it.nextPostingAt }?.toInstant() else null,
            creditExpiringThisWeekMiles = credit * rules.creditDecayRatePerWeek,
            streak = streak,
        )
    }
}
