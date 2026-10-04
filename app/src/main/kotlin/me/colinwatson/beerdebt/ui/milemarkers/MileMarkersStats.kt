package me.colinwatson.beerdebt.ui.milemarkers

import me.colinwatson.beerdebt.engine.BalanceEngine
import me.colinwatson.beerdebt.engine.BeerStatement
import me.colinwatson.beerdebt.engine.Ledger
import me.colinwatson.beerdebt.engine.Report
import me.colinwatson.beerdebt.engine.RulesChange
import me.colinwatson.beerdebt.engine.RunStatement
import me.colinwatson.beerdebt.engine.StreakStatus
import me.colinwatson.beerdebt.ui.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How far back "Mile Markers" looks. The picker at the top of the screen. */
enum class MileMarkersRange(val label: String) {
    MONTH("1M"), QUARTER("3M"), YEAR("1Y"), ALL("All");

    /** Spoken mid-sentence: "down 6.2 mi over the last month". */
    val phrase: String
        get() = when (this) {
            MONTH -> "the last month"
            QUARTER -> "the last 3 months"
            YEAR -> "the last year"
            ALL -> "all time"
        }

    val days: Long?
        get() = when (this) {
            MONTH -> 30L
            QUARTER -> 90L
            YEAR -> 365L
            ALL -> null
        }

    /**
     * How many points the balance curve is sampled at.
     *
     * Deliberately small and fixed. Each point is a full replay -- the engine
     * has no way to emit a series from one pass -- and a replay costs roughly
     * one interest posting per unpaid beer per period, so a tab that has been
     * left to run grows the per-point cost without bound. Forty-odd points draw
     * a smooth curve at phone width anyway.
     */
    val samples: Int
        get() = when (this) {
            MONTH -> 30
            QUARTER -> 45
            YEAR -> 52
            ALL -> 40
        }
}

/**
 * Everything "Mile Markers" draws, computed once off the main thread.
 *
 * Pure: built from a [Ledger] snapshot and an instant, with no clock and no
 * I/O, the same contract the engine keeps. Deliberately *not* in `engine/`: it
 * is a reading of the books rather than part of them, so it stays out of the
 * cross-platform fixture contract and can change without regenerating
 * `cases.json`. A port of the Swift `MileMarkersStats`.
 */
data class MileMarkersReport(
    val range: MileMarkersRange,
    val start: Instant,
    val end: Instant,
    /** Oldest first. */
    val points: List<Point>,
    /** Every calendar day in the range, for the grid. */
    val days: List<Day>,
    /** Protected stretches and frozen days, for the wash behind the curve. */
    val spans: List<Span>,
    val events: List<Event>,
    // Totals over the range.
    val beersAdded: Int,
    val runCount: Int,
    val milesRun: Double,
    /**
     * Interest that posted during the range. Interest only ever accrues, so
     * this is the end total less the start total.
     */
    val interestChargedMiles: Double,
    /**
     * What the streak's protected days kept off the books: the same figure
     * replayed with protection off, less what was actually charged.
     */
    val interestWaivedMiles: Double,
    /** End of range less start of range. Negative is progress. */
    val debtChangeMiles: Double,
    val debtAtStartMiles: Double,
    val debtAtEndMiles: Double,
    // Records. All-time, not range-scoped: a record set last year still stands.
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val totalStreakDays: Int,
    val freezesHeld: Int,
    val freezesSpent: Int,
    // Superlatives, within the range.
    val priciestBeer: BeerStatement?,
    val priciestBeerInterestMiles: Double,
    val longestDrySpellDays: Int,
    val biggestDayBeers: Int,
    val biggestDay: LocalDate?,
    val longestRun: RunStatement?,
) {
    val isEmpty: Boolean get() = beersAdded == 0 && runCount == 0

    /** One sampled instant on the curve, with the flows in the bucket ending there. */
    data class Point(
        val date: Instant,
        val debtMiles: Double,
        val principalMiles: Double,
        /** Drawn as the gap up to the owed line: the wedge is what waiting cost. */
        val interestMiles: Double,
        val creditMiles: Double,
        val beers: Int,
        val runMiles: Double,
        val cumulativeBeerMiles: Double,
        val cumulativeRunMiles: Double,
    )

    /** One calendar day in the streak grid. */
    data class Day(
        val date: LocalDate,
        val miles: Double,
        val qualifies: Boolean,
        /** Interest postings on this day were skipped (spec §25). */
        val interestProtected: Boolean,
        val frozen: Boolean,
    )

    /**
     * A stretch worth shading behind the balance curve. Protected spans are why
     * the curve flattens; a freeze is a single day that held the line without a
     * run.
     */
    data class Span(val start: Instant, val end: Instant, val kind: Kind) {
        enum class Kind { PROTECTED, FROZEN }

        val days: Double get() = (end.toEpochMilli() - start.toEpochMilli()) / 86_400_000.0

        /**
         * A streak has to have held this long to earn an icon in the chart's
         * marker lane. Over a long range an icon per stretch crowds the lane
         * into a smear, and the ones worth pointing at are the ones that
         * lasted; the wash behind the curve still shows every stretch.
         */
        val isMarked: Boolean get() = kind == Kind.FROZEN || days >= MARKABLE_STREAK_DAYS

        /**
         * Where the icon sits: the middle of the span it belongs to, so it
         * reads as a label on its band rather than hanging off the edge.
         */
        val markerDate: Instant
            get() = start.plusMillis((end.toEpochMilli() - start.toEpochMilli()) / 2)

        /**
         * Which row of the marker lane the icon belongs on.
         *
         * Freezes go below. A frozen day sits *inside* the stretch it protects,
         * so a freeze near the middle of a streak lands on the flame centred on
         * that same stretch. Two fixed rows beat collision-dodging: nothing
         * jitters as the range changes.
         */
        val markerRow: Int get() = if (kind == Kind.FROZEN) 1 else 0

        companion object {
            const val MARKABLE_STREAK_DAYS = 3.0
        }
    }

    /** Something worth a line under the chart. */
    data class Event(val date: Instant, val kind: Kind) {
        sealed interface Kind {
            data class BigDay(val beers: Int) : Kind
            data class StreakStarted(val days: Int) : Kind
            data object RestDay : Kind
            data class LowPoint(val miles: Double) : Kind
            data object Clean : Kind
        }

        val title: String
            get() = when (kind) {
                is Kind.BigDay -> "Big one: +${kind.beers} beers"
                is Kind.StreakStarted -> "${kind.days} day streak started"
                Kind.RestDay -> "Rest day — freeze spent"
                is Kind.LowPoint ->
                    if (kind.miles < 0.05) "Lowest tab of the range"
                    else "Lowest tab: ${Format.miles(kind.miles)}"
                Kind.Clean -> "Books went clean"
            }
    }
}

object MileMarkersStats {
    /**
     * Builds the whole screen's data from one ledger.
     *
     * Cost is dominated by [MileMarkersRange.samples] replays plus three: the
     * two ends of the counterfactual used for interest waived, and the start of
     * the range. Call it off the main thread.
     */
    fun build(
        ledger: Ledger,
        range: MileMarkersRange,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MileMarkersReport {
        val start = startOfRange(range, ledger, now, zone)
        val final = BalanceEngine.report(ledger, now, zone)

        // The curve. Sampled, because the engine reports one instant at a time.
        val sampleCount = range.samples.coerceIn(2, 400)
        val span = (now.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1L)
        val instants = (0..sampleCount).map { i ->
            if (i == sampleCount) now else start.plusMillis(span * i / sampleCount)
        }
        val balances = instants.map { BalanceEngine.report(ledger, it, zone).balance }

        // Flows per bucket, read off the final report's statements rather than
        // replayed: what was logged when doesn't depend on the instant.
        val beersInRange = final.beers.filter { it.createdAt >= start && it.createdAt <= now }
        val runsInRange = final.runs.filter { !it.ignored && it.run.endedAt >= start && it.run.endedAt <= now }

        var cumulativeBeerMiles = 0.0
        var cumulativeRunMiles = 0.0
        val points = instants.mapIndexed { i, instant ->
            // Buckets are half-open, (from, instant], so nothing is counted
            // twice. The first takes its lower edge too, or an event landing
            // exactly on the opening instant would belong to no bucket at all.
            val from = if (i == 0) start else instants[i - 1]
            fun inBucket(at: Instant) =
                (if (i == 1) at >= from else at > from) && at <= instant
            val beers = if (i == 0) emptyList() else beersInRange.filter { inBucket(it.createdAt) }
            val runs = if (i == 0) emptyList() else runsInRange.filter { inBucket(it.run.endedAt) }
            cumulativeBeerMiles += beers.sumOf { it.costMiles }
            cumulativeRunMiles += runs.sumOf { it.run.distanceMiles }
            val balance = balances[i]
            MileMarkersReport.Point(
                date = instant,
                debtMiles = balance.debtMiles,
                principalMiles = balance.principalMiles,
                interestMiles = balance.interestMiles,
                creditMiles = balance.creditMiles,
                beers = beers.size,
                runMiles = runs.sumOf { it.run.distanceMiles },
                cumulativeBeerMiles = cumulativeBeerMiles,
                cumulativeRunMiles = cumulativeRunMiles,
            )
        }

        // Interest charged, and what the streak kept off.
        val interestAtStart = interestAccrued(BalanceEngine.report(ledger, start, zone))
        val charged = (interestAccrued(final) - interestAtStart).coerceAtLeast(0.0)
        val bare = unprotected(ledger)
        val bareStart = interestAccrued(BalanceEngine.report(bare, start, zone))
        val bareEnd = interestAccrued(BalanceEngine.report(bare, now, zone))
        val waived = ((bareEnd - bareStart) - charged).coerceAtLeast(0.0)

        val days = grid(start, now, final.streak, zone)
        val priciest = beersInRange.maxByOrNull { it.interestAccruedMiles }
        val big = biggestDay(beersInRange, zone)

        return MileMarkersReport(
            range = range,
            start = start,
            end = now,
            points = points,
            days = days,
            spans = spans(days, zone),
            events = events(points, days, beersInRange, zone),
            beersAdded = beersInRange.size,
            runCount = runsInRange.size,
            milesRun = runsInRange.sumOf { it.run.distanceMiles },
            interestChargedMiles = charged,
            interestWaivedMiles = waived,
            debtChangeMiles = (balances.lastOrNull()?.debtMiles ?: 0.0) - (balances.firstOrNull()?.debtMiles ?: 0.0),
            debtAtStartMiles = balances.firstOrNull()?.debtMiles ?: 0.0,
            debtAtEndMiles = balances.lastOrNull()?.debtMiles ?: 0.0,
            currentStreakDays = final.streak.currentStreakDays,
            longestStreakDays = final.streak.longestStreakDays,
            totalStreakDays = final.streak.totalQualifyingDays,
            freezesHeld = final.streak.freezesHeld,
            freezesSpent = final.streak.days.count { it.frozen },
            priciestBeer = priciest,
            priciestBeerInterestMiles = priciest?.interestAccruedMiles ?: 0.0,
            longestDrySpellDays = longestDrySpell(beersInRange, start, now, zone),
            biggestDayBeers = big?.second ?: 0,
            biggestDay = big?.first,
            longestRun = runsInRange.maxByOrNull { it.run.distanceMiles },
        )
    }

    /**
     * Where the range opens. "All" starts at the first thing on the books,
     * which may predate the opening: a beer can be backdated behind it.
     */
    fun startOfRange(range: MileMarkersRange, ledger: Ledger, now: Instant, zone: ZoneId): Instant {
        val earliest = minOf(
            ledger.booksOpenedAt,
            ledger.beers.minOfOrNull { it.createdAt } ?: ledger.booksOpenedAt,
        )
        val days = range.days ?: return minOf(earliest, now)
        val windowed = now.minus(days, ChronoUnit.DAYS)
        // Never open the range before the books: an empty run-up reads as a
        // flat line the user never lived through.
        return maxOf(windowed, minOf(earliest, now))
    }

    /**
     * Total interest ever posted, paid or not. Monotonic over time, which is
     * what lets two of these be subtracted to get "charged during the range".
     */
    fun interestAccrued(report: Report): Double = report.beers.sumOf { it.interestAccruedMiles }

    /**
     * The same books with streak protection switched off throughout -- the
     * counterfactual behind "your streak waived N miles". Replaying it is pure,
     * so this costs a replay and changes nothing.
     */
    fun unprotected(ledger: Ledger): Ledger = ledger.copy(
        rulesHistory = ledger.rulesHistory.map {
            RulesChange(it.effectiveAt, it.rules.copy(streakProtection = false))
        },
    )

    fun grid(start: Instant, end: Instant, streak: StreakStatus, zone: ZoneId): List<MileMarkersReport.Day> {
        val first = start.atZone(zone).toLocalDate()
        val last = end.atZone(zone).toLocalDate()
        val byDay = streak.days.associateBy { it.day }
        val out = mutableListOf<MileMarkersReport.Day>()
        var cursor = first
        while (!cursor.isAfter(last) && out.size <= 4_000) {
            val d = byDay[cursor]
            out += MileMarkersReport.Day(
                date = cursor,
                miles = d?.miles ?: 0.0,
                qualifies = d?.qualifies ?: false,
                interestProtected = d?.interestProtected ?: false,
                frozen = d?.frozen ?: false,
            )
            cursor = cursor.plusDays(1)
        }
        return out
    }

    /**
     * Contiguous protected stretches, plus every frozen day on its own. A span
     * runs to the end of its last day so the shading covers it.
     */
    fun spans(days: List<MileMarkersReport.Day>, zone: ZoneId): List<MileMarkersReport.Span> {
        fun startOf(d: LocalDate) = d.atStartOfDay(zone).toInstant()
        fun endOf(d: LocalDate) = d.plusDays(1).atStartOfDay(zone).toInstant()

        val spans = mutableListOf<MileMarkersReport.Span>()
        var open: LocalDate? = null
        var previous: LocalDate? = null
        for (day in days) {
            if (day.interestProtected) {
                if (open == null) open = day.date
                previous = day.date
            } else if (open != null && previous != null) {
                spans += MileMarkersReport.Span(startOf(open), endOf(previous), MileMarkersReport.Span.Kind.PROTECTED)
                open = null
                previous = null
            }
        }
        if (open != null && previous != null) {
            spans += MileMarkersReport.Span(startOf(open), endOf(previous), MileMarkersReport.Span.Kind.PROTECTED)
        }
        // Freezes are drawn over the protection they create, because the point
        // is that this one was bought rather than run for.
        for (day in days.filter { it.frozen }) {
            spans += MileMarkersReport.Span(startOf(day.date), endOf(day.date), MileMarkersReport.Span.Kind.FROZEN)
        }
        return spans
    }

    /** The longest stretch of consecutive days with no beer on them. */
    fun longestDrySpell(beers: List<BeerStatement>, start: Instant, end: Instant, zone: ZoneId): Int {
        val wet = beers.map { it.createdAt.atZone(zone).toLocalDate() }.toSet()
        var longest = 0
        var current = 0
        var cursor = start.atZone(zone).toLocalDate()
        val last = end.atZone(zone).toLocalDate()
        while (!cursor.isAfter(last)) {
            if (cursor in wet) current = 0 else { current++; longest = maxOf(longest, current) }
            cursor = cursor.plusDays(1)
        }
        return longest
    }

    fun biggestDay(beers: List<BeerStatement>, zone: ZoneId): Pair<LocalDate, Int>? =
        beers.groupBy { it.createdAt.atZone(zone).toLocalDate() }
            .maxByOrNull { it.value.size }
            ?.let { it.key to it.value.size }

    /**
     * A handful of lines for under the chart. Capped, because a wall of them
     * says nothing -- the point is the two or three days that explain the shape
     * of the curve.
     */
    fun events(
        points: List<MileMarkersReport.Point>,
        days: List<MileMarkersReport.Day>,
        beers: List<BeerStatement>,
        zone: ZoneId,
    ): List<MileMarkersReport.Event> {
        val events = mutableListOf<MileMarkersReport.Event>()

        biggestDay(beers, zone)?.let { (day, count) ->
            if (count >= 3) {
                events += MileMarkersReport.Event(
                    day.atStartOfDay(zone).toInstant(),
                    MileMarkersReport.Event.Kind.BigDay(count),
                )
            }
        }

        streakStarts(days).maxByOrNull { it.second }?.let { (day, length) ->
            if (length >= 2) {
                events += MileMarkersReport.Event(
                    day.atStartOfDay(zone).toInstant(),
                    MileMarkersReport.Event.Kind.StreakStarted(length),
                )
            }
        }

        days.lastOrNull { it.frozen }?.let {
            events += MileMarkersReport.Event(
                it.date.atStartOfDay(zone).toInstant(),
                MileMarkersReport.Event.Kind.RestDay,
            )
        }

        val rest = points.drop(1)
        val clean = rest.firstOrNull { it.debtMiles < BalanceEngine.EPSILON }
        if (clean != null && (points.firstOrNull()?.debtMiles ?: 0.0) > BalanceEngine.EPSILON) {
            events += MileMarkersReport.Event(clean.date, MileMarkersReport.Event.Kind.Clean)
        } else {
            rest.minByOrNull { it.debtMiles }?.let {
                events += MileMarkersReport.Event(it.date, MileMarkersReport.Event.Kind.LowPoint(it.debtMiles))
            }
        }

        return events.sortedBy { it.date }.take(4)
    }

    /** First day of each streak that reached two days, with how long it ran. */
    fun streakStarts(days: List<MileMarkersReport.Day>): List<Pair<LocalDate, Int>> {
        val result = mutableListOf<Pair<LocalDate, Int>>()
        var runStart: LocalDate? = null
        var length = 0
        for (day in days) {
            if (day.qualifies || day.frozen) {
                if (runStart == null) runStart = day.date
                if (day.qualifies) length++
            } else {
                if (runStart != null && length >= 2) result += runStart to length
                runStart = null
                length = 0
            }
        }
        if (runStart != null && length >= 2) result += runStart to length
        return result
    }
}
