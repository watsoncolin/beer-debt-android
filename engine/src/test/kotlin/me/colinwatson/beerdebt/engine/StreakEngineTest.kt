package me.colinwatson.beerdebt.engine

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Swift StreakEngineTests, case for case. t0 is Saturday 2026-09-12 20:00 UTC. */
class StreakEngineTest {
    private val utc: ZoneId = ZoneId.of("UTC")
    private val t0: Instant = Instant.ofEpochSecond(1_789_243_200)
    private val hour = 3_600L
    private val day = 86_400L
    private fun at(offset: Long) = t0.plusSeconds(offset)
    private fun morning(n: Int) = at(n * day - 13 * hour)   // 07:00 UTC that day
    private fun run(miles: Double, endedAt: Instant) =
        RunEntry(UUID.randomUUID(), UUID.randomUUID(), endedAt.minusSeconds(1800), endedAt, miles * RunEntry.METERS_PER_MILE, endedAt, "Test")
    private fun streak(runs: List<RunEntry>, at: Instant) = StreakEngine.calculate(runs, at, utc)
    private fun close(a: Double, b: Double) = abs(a - b) <= 1e-6

    @Test fun exactlyOneMileQualifiesAndJustUnderDoesNot() {
        assertTrue(streak(listOf(run(1.0, morning(0))), t0).todayQualifies)
        val close = RunEntry(UUID.randomUUID(), UUID.randomUUID(), morning(0), morning(0), RunEntry.METERS_PER_MILE - 0.5, t0)
        assertTrue(streak(listOf(close), t0).todayQualifies)
        assertFalse(streak(listOf(run(0.999, morning(0))), t0).todayQualifies)
    }

    @Test fun twoRunsInOneDayAddUp() {
        val s = streak(listOf(run(0.4, morning(0)), run(0.7, at(-2 * hour))), t0)
        assertTrue(s.todayQualifies); assertTrue(close(s.todayMiles, 1.1)); assertEquals(1, s.currentStreakDays)
    }

    @Test fun dayTwoActivatesProtection() {
        val one = streak(listOf(run(1.2, morning(0))), t0)
        assertEquals(1, one.currentStreakDays); assertFalse(one.interestProtectionActive); assertFalse(one.todayProtected)
        val two = streak(listOf(run(1.2, morning(0)), run(1.1, morning(1))), at(day))
        assertEquals(2, two.currentStreakDays); assertTrue(two.interestProtectionActive); assertTrue(two.todayProtected)
        assertFalse(two.isProtected(morning(0))); assertTrue(two.isProtected(morning(1)))
    }

    @Test fun aShortDayBreaksTheStreakAndTheNextMileIsDayOne() {
        val runs = listOf(run(1.3, morning(0)), run(2.0, morning(1)), run(1.1, morning(2)), run(0.4, morning(3)), run(2.2, morning(4)))
        val s = streak(runs, at(4 * day))
        assertEquals(listOf(1, 2, 3, 0, 1), s.days.map { it.streakNumber })
        assertEquals(listOf(false, true, true, false, false), s.days.map { it.interestProtected })
        assertEquals(1, s.currentStreakDays); assertEquals(3, s.longestStreakDays); assertEquals(4, s.totalQualifyingDays)
    }

    @Test fun aStreakStaysAliveUntilTodayIsOver() {
        val runs = listOf(run(1.0, morning(-3)), run(1.0, morning(-2)), run(1.0, morning(-1)))
        val s = streak(runs, t0)
        assertEquals(3, s.currentStreakDays); assertTrue(s.interestProtectionActive); assertFalse(s.todayProtected)
        assertEquals(0, streak(runs, at(day)).currentStreakDays)
    }

    @Test fun daysFollowTheCalendarNotTheClock() {
        val runs = listOf(run(1.0, at(-2 * hour)), run(1.0, at(5 * hour)))   // Sat 18:00 UTC, Sun 01:00 UTC
        assertEquals(2, StreakEngine.calculate(runs, at(6 * hour), utc).currentStreakDays)
        assertEquals(1, StreakEngine.calculate(runs, at(6 * hour), ZoneId.of("America/New_York")).currentStreakDays)
    }

    @Test fun runsAreNumberedOnlyOnceAStreakReachesTwoDays() {
        val s = streak(listOf(run(1.3, morning(0)), run(1.0, morning(2)), run(1.0, morning(3)), run(0.5, morning(4))), at(4 * day))
        assertNull(s.streakDayNumber(morning(0))); assertEquals(1, s.streakDayNumber(morning(2)))
        assertEquals(2, s.streakDayNumber(morning(3))); assertNull(s.streakDayNumber(morning(4)))
    }

    @Test fun protectedDaysSkipThePostingAndNothingElseChanges() {
        val five = (1..5).map { BeerEntry(UUID.randomUUID(), t0, t0) }
        val l = Ledger.open(t0).copy(beers = five, runs = listOf(run(1.2, morning(1)), run(1.2, morning(2))))
        assertTrue(close(BalanceEngine.report(l, at(day + hour), utc).balance.debtMiles, 4.18))
        assertTrue(close(BalanceEngine.report(l, at(2 * day + hour), utc).balance.debtMiles, 2.98))
        val tuesday = BalanceEngine.report(l, at(3 * day + hour), utc)
        assertTrue(close(tuesday.balance.debtMiles, 3.278)); assertTrue(close(tuesday.balance.interestMiles, 0.498))
    }

    @Test fun protectionIsOffUntilTheRuleTurnsOn() {
        val five = (1..5).map { BeerEntry(UUID.randomUUID(), t0, t0) }
        val runs = listOf(run(1.2, morning(1)), run(1.2, morning(2)))
        val off = Ledger.open(t0, Rules()).copy(beers = five, runs = runs)
        assertTrue(close(BalanceEngine.report(off, at(2 * day + hour), utc).balance.debtMiles, 2.98 * 1.1))
        val on = off.copy(rulesHistory = off.rulesHistory + RulesChange(at(2 * day - 8 * hour), Rules(streakProtection = true)))
        assertTrue(close(BalanceEngine.report(on, at(2 * day + hour), utc).balance.debtMiles, 2.98))
    }
}
