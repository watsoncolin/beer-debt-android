package me.colinwatson.beerdebt

import me.colinwatson.beerdebt.engine.BalanceEngine
import me.colinwatson.beerdebt.engine.BalanceState
import me.colinwatson.beerdebt.engine.FreezeApplication
import me.colinwatson.beerdebt.engine.Ledger
import me.colinwatson.beerdebt.engine.Rules
import me.colinwatson.beerdebt.engine.RunEntry
import me.colinwatson.beerdebt.ui.milemarkers.MileMarkersRange
import me.colinwatson.beerdebt.ui.milemarkers.MileMarkersReport
import me.colinwatson.beerdebt.ui.milemarkers.MileMarkersStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * "Mile Markers" reads the books; it never changes them. The same cases as the
 * iOS `MileMarkersStatsTests`, especially the two readings that aren't a plain
 * sum: interest charged during a range, and what the streak waived.
 *
 * Every ledger here names [Rules.OPENING]. The bare `Rules()` has
 * streakProtection off (that is how a pre-feature ledger decodes), so a
 * default-constructed one would quietly measure books with no protection.
 */
class MileMarkersStatsTest {
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun books(
        beers: List<me.colinwatson.beerdebt.engine.BeerEntry> = emptyList(),
        runs: List<RunEntry> = emptyList(),
        openedAt: Instant = t0,
    ): Ledger = Ledger.open(openedAt, Rules.OPENING).copy(beers = beers, runs = runs)

    private fun build(ledger: Ledger, range: MileMarkersRange = MileMarkersRange.ALL, at: Instant) =
        MileMarkersStats.build(ledger, range, at, utc)

    /** 07:00 UTC on t0 + n days, so a run lands mid-day rather than on a boundary. */
    private fun morning(n: Long): Instant = at(n * DAY - 13 * HOUR)

    @Test fun emptyBooksReadAsEmpty() {
        val r = build(books(), at = at(DAY))
        assertTrue(r.isEmpty)
        assertEquals(0, r.beersAdded)
        assertEquals(0, r.runCount)
        assertEquals(0.0, r.interestChargedMiles, 0.0)
        assertEquals(0.0, r.interestWaivedMiles, 0.0)
        // Still a curve, so the chart has something to draw.
        assertTrue(r.points.size >= 2)
        assertTrue(r.points.all { it.debtMiles == 0.0 })
    }

    @Test fun flowsAreCountedOnceAndTotalled() {
        val r = build(
            books(
                beers = listOf(beer(at(HOUR)), beer(at(2 * HOUR)), beer(at(26 * HOUR))),
                runs = listOf(run(2.0, morning(1)), run(3.5, morning(2))),
            ),
            at = at(3 * DAY),
        )
        assertEquals(3, r.beersAdded)
        assertEquals(2, r.runCount)
        assertTrue(close(r.milesRun, 5.5))
        // Every beer and every run lands in exactly one bucket.
        assertEquals(3, r.points.sumOf { it.beers })
        assertTrue(close(r.points.sumOf { it.runMiles }, 5.5))
        assertTrue(close(r.points.last().cumulativeRunMiles, 5.5))
        assertTrue(close(r.points.last().cumulativeBeerMiles, 3.0))
    }

    @Test fun theCurveEndsOnTheLiveBalance() {
        val ledger = books(beers = listOf(beer(t0), beer(at(HOUR))))
        val now = at(5 * DAY)
        val r = build(ledger, at = now)
        val live = BalanceEngine.report(ledger, now, utc)
        assertEquals(now, r.points.last().date)
        assertTrue(close(r.points.last().debtMiles, live.balance.debtMiles))
        assertTrue(close(r.points.last().principalMiles, live.balance.principalMiles))
        assertTrue(close(r.debtAtEndMiles, live.balance.debtMiles))
    }

    @Test fun interestChargedGrowsWithTheWindow() {
        val ledger = books(beers = listOf(beer(t0)))
        val early = build(ledger, at = at(3 * DAY))
        val later = build(ledger, at = at(10 * DAY))
        assertTrue(later.interestChargedMiles > early.interestChargedMiles)
    }

    @Test fun aStreakWaivesInterestAndSaysHowMuch() {
        // Ten beers, then a mile a day: from day two the streak pauses
        // interest. The runs are too small to clear the tab, so there is still
        // a debt for the protection to be worth something on.
        val runs = (1L..6L).map { run(1.1, morning(it)) }
        val ledger = books(beers = (0..9).map { beer(at(it * 60L)) }, runs = runs)
        val now = at(7 * DAY)
        val r = build(ledger, at = now)
        assertTrue(r.interestWaivedMiles > 0)

        // And it is exactly the difference against the same books unprotected.
        val bare = MileMarkersStats.unprotected(ledger)
        val bareInterest = MileMarkersStats.interestAccrued(BalanceEngine.report(bare, now, utc))
        val realInterest = MileMarkersStats.interestAccrued(BalanceEngine.report(ledger, now, utc))
        assertTrue(close(r.interestWaivedMiles, bareInterest - realInterest))
    }

    @Test fun noStreakWaivesNothing() {
        val r = build(books(beers = listOf(beer(t0))), at = at(6 * DAY))
        assertTrue(r.interestChargedMiles > 0)
        assertEquals(0.0, r.interestWaivedMiles, 0.0)
    }

    @Test fun theCounterfactualNeverTouchesTheRealBooks() {
        val ledger = books(beers = listOf(beer(t0)), runs = listOf(run(1.0, morning(1))))
        val before = ledger.copy()
        build(ledger, at = at(3 * DAY))
        assertEquals(before, ledger)
        val bare = MileMarkersStats.unprotected(ledger)
        assertTrue(bare.rulesHistory.none { it.rules.streakProtection })
        assertTrue(ledger.rulesHistory.all { it.rules.streakProtection })
        assertEquals(ledger.beers, bare.beers)
        assertEquals(ledger.runs, bare.runs)
    }

    @Test fun theGridCoversEveryDayInTheRange() {
        val r = build(books(runs = listOf(run(2.0, morning(1)), run(0.3, morning(3)))), at = at(5 * DAY))
        for ((a, b) in r.days.zipWithNext()) assertEquals(a.date.plusDays(1), b.date)
        // The two-mile day qualifies; the third-of-a-mile day doesn't.
        assertEquals(1, r.days.count { it.qualifies })
        assertTrue(close(r.days.first { it.qualifies }.miles, 2.0))
    }

    @Test fun rangesClampToTheBooksRatherThanInventingHistory() {
        val r = build(books(beers = listOf(beer(t0))), MileMarkersRange.YEAR, at(2 * DAY))
        assertEquals(t0, r.start)
        assertEquals(at(2 * DAY), r.end)
    }

    @Test fun aBackdatedBeerPullsTheAllTimeRangeBackBehindTheBooks() {
        // A beer may predate the opening (30-day backdating); runs may not.
        val ledger = books(beers = listOf(beer(at(-5 * DAY))))
        val r = build(ledger, MileMarkersRange.ALL, at(DAY))
        assertEquals(at(-5 * DAY), r.start)
        assertEquals(1, r.beersAdded)
    }

    @Test fun theDrySpellIsTheLongestStretchWithNoBeer() {
        // Beers on day 0 and day 5: four clear days between them.
        val r = build(books(beers = listOf(beer(at(HOUR)), beer(at(5 * DAY + HOUR)))), at = at(5 * DAY + 2 * HOUR))
        assertEquals(4, r.longestDrySpellDays)
    }

    @Test fun theBiggestDayAndPriciestBeerAreNamed() {
        val r = build(
            books(beers = listOf(beer(t0), beer(at(HOUR)), beer(at(2 * HOUR)), beer(at(3 * DAY)))),
            at = at(6 * DAY),
        )
        assertEquals(3, r.biggestDayBeers)
        assertEquals(t0.atZone(utc).toLocalDate(), r.biggestDay)
        // The oldest unpaid beer has had the longest to accrue.
        assertEquals(t0, r.priciestBeer?.createdAt)
        assertTrue(r.priciestBeerInterestMiles > 0)
    }

    @Test fun eventsStayFewAndInOrder() {
        val runs = (1L..5L).map { run(1.5, morning(it)) }
        val r = build(
            books(beers = listOf(beer(t0), beer(at(HOUR)), beer(at(2 * HOUR))), runs = runs),
            at = at(6 * DAY),
        )
        assertTrue(r.events.size <= 4)
        assertEquals(r.events.sortedBy { it.date }, r.events)
        assertTrue(r.events.any { (it.kind as? MileMarkersReport.Event.Kind.BigDay)?.beers == 3 })
    }

    @Test fun recordsAreAllTimeNotRangeScoped() {
        // A six-day streak a month ago, nothing since.
        val runs = (1L..6L).map { run(1.5, morning(it)) }
        val month = build(books(runs = runs), MileMarkersRange.MONTH, at(40 * DAY))
        assertEquals(0, month.runCount)              // the window holds no runs...
        assertEquals(6, month.longestStreakDays)     // ...but the record stands
        assertEquals(6, month.totalStreakDays)
    }

    @Test fun sampleCountIsBoundedWhateverTheSpan() {
        val beers = (0 until 150).map { beer(at(it * 7L * DAY)) }
        val r = build(books(beers = beers), MileMarkersRange.ALL, at(1_095 * DAY))
        assertEquals(MileMarkersRange.ALL.samples + 1, r.points.size)
        assertTrue(r.points.size <= 64)
    }

    // Marker lane rules (spec §27).

    @Test fun protectedStretchesBecomeSpansBehindTheCurve() {
        // Days 1-5 qualify, day 6 is missed, days 7-8 qualify again.
        val runs = listOf(1L, 2L, 3L, 4L, 5L, 7L, 8L).map { run(1.4, morning(it)) }
        val r = build(books(beers = listOf(beer(t0)), runs = runs), at = at(9 * DAY))
        val protected = r.spans.filter { it.kind == MileMarkersReport.Span.Kind.PROTECTED }
        // Protection starts on day two of each streak, so two separate washes.
        assertEquals(2, protected.size)
        assertTrue(protected.all { it.end > it.start })
        // Every protected day the grid reports is covered by exactly one span.
        for (day in r.days.filter { it.interestProtected }) {
            val at = day.date.atStartOfDay(utc).toInstant()
            assertEquals(1, protected.count { it.start <= at && at < it.end })
        }
    }

    @Test fun aFrozenDayGetsItsOwnSpanOverTheProtectionItBuys() {
        val runs = (1L..5L).map { run(1.4, morning(it)) }
        val ledger = books(beers = listOf(beer(t0)), runs = runs).copy(
            freezeApplications = listOf(FreezeApplication.forDay(morning(6), utc, morning(6))),
        )
        val r = build(ledger, at = at(7 * DAY))
        val frozen = r.spans.filter { it.kind == MileMarkersReport.Span.Kind.FROZEN }
        assertEquals(1, frozen.size)
        val day = r.days.first { it.date == frozen[0].start.atZone(utc).toLocalDate() }
        // The day it covers is still protected, so the wash sits on top of a
        // protected span rather than replacing it.
        assertTrue(day.frozen)
        assertTrue(day.interestProtected)
    }

    @Test fun noStreakDrawsNoSpans() {
        assertTrue(build(books(beers = listOf(beer(t0))), at = at(5 * DAY)).spans.isEmpty())
    }

    @Test fun onlyStreaksWorthPointingAtAreMarked() {
        fun span(days: Long, kind: MileMarkersReport.Span.Kind) =
            MileMarkersReport.Span(t0, at(days * DAY), kind)
        // A brief protected stretch keeps its wash but earns no icon, so a long
        // range doesn't smear the lane into a row of flames.
        assertFalse(span(2, MileMarkersReport.Span.Kind.PROTECTED).isMarked)
        assertTrue(span(3, MileMarkersReport.Span.Kind.PROTECTED).isMarked)
        // Freezes are rare enough that every one is marked.
        assertTrue(span(1, MileMarkersReport.Span.Kind.FROZEN).isMarked)
    }

    @Test fun anIconSitsInTheMiddleOfItsSpan() {
        val freeze = MileMarkersReport.Span(t0, at(DAY), MileMarkersReport.Span.Kind.FROZEN)
        assertEquals(at(DAY / 2), freeze.markerDate)
        val streak = MileMarkersReport.Span(t0, at(5 * DAY), MileMarkersReport.Span.Kind.PROTECTED)
        assertEquals(at(5 * DAY / 2), streak.markerDate)
    }

    @Test fun aFreezeIconSitsOnTheRowBelowTheFlame() {
        // A frozen day is inside the stretch it protects, so a freeze near the
        // middle of a streak would otherwise land on that streak's own flame.
        val streak = MileMarkersReport.Span(t0, at(30 * DAY), MileMarkersReport.Span.Kind.PROTECTED)
        val freeze = MileMarkersReport.Span(at(15 * DAY), at(16 * DAY), MileMarkersReport.Span.Kind.FROZEN)
        assertEquals(0, streak.markerRow)
        assertEquals(1, freeze.markerRow)
        assertTrue(close(freeze.markerDate.epochSecond - streak.markerDate.epochSecond.toDouble(), 0.5 * DAY))
    }
}
