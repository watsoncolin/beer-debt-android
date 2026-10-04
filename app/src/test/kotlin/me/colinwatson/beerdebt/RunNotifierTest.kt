package me.colinwatson.beerdebt

import me.colinwatson.beerdebt.engine.BalanceEngine
import me.colinwatson.beerdebt.engine.Rules
import me.colinwatson.beerdebt.engine.BalanceState.CREDIT
import me.colinwatson.beerdebt.engine.BalanceState.DEBT
import me.colinwatson.beerdebt.engine.BalanceState.EVEN
import me.colinwatson.beerdebt.notify.RunNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The notification copy is a pure function of what a sync changed; same cases as iOS RunNotifierTests. */
class RunNotifierTest {
    private fun message(
        added: List<Double>, removed: Int,
        before: me.colinwatson.beerdebt.engine.Balance, after: me.colinwatson.beerdebt.engine.Balance,
        paidOff: Int = 0, debtPaid: Double = added.sum(),
    ) = RunNotifier.message(RunNotifier.Change(added.map { run(it, t0) }, removed, before, after, debtPaid, paidOff))

    @Test fun runThatPaysSomeBeersButNotAll() {
        val m = message(listOf(3.2), 0, balance(DEBT, debt = 4.5), balance(DEBT, debt = 1.3), paidOff = 2)
        assertEquals(RunNotifier.Message("Run logged: 3.2 mi", "Paid off 2 beers. 1.3 mi still owed."), m)
    }

    @Test fun runThatOnlyChipsAwayAtOneBeer() {
        assertEquals("Knocked 0.6 mi off your tab. 1.3 mi still owed.", message(listOf(0.6), 0, balance(DEBT, debt = 1.9), balance(DEBT, debt = 1.3))?.body)
    }

    @Test fun runThatClearsTheTabAndBanksSome() {
        assertEquals("Tab paid, and 1.4 beers banked. Cheers.", message(listOf(3.0), 0, balance(DEBT, debt = 1.6), balance(CREDIT, credit = 1.4), paidOff = 2)?.body)
    }

    @Test fun runThatClearsTheTabExactly() {
        assertEquals("Tab paid. Books are clean.", message(listOf(1.0), 0, balance(DEBT, debt = 1.0), balance(EVEN), paidOff = 1)?.body)
    }

    @Test fun runWithNothingOwedBanksBeers() {
        assertEquals("2 beers banked for later.", message(listOf(2.0), 0, balance(EVEN), balance(CREDIT, credit = 2.0))?.body)
    }

    @Test fun runWhenAlreadyMaxedOut() {
        assertEquals("You're maxed out at 3 beers banked. Time to drink one.", message(listOf(5.0), 0, balance(CREDIT, credit = 3.0), balance(CREDIT, credit = 3.0))?.body)
    }

    @Test fun deletedWorkoutPutsTheBeerBack() {
        val m = message(emptyList(), 1, balance(EVEN), balance(DEBT, debt = 2.3))
        assertEquals(RunNotifier.Message("A run was removed from Health Connect", "You're at 2.3 mi owed."), m)
    }

    @Test fun nothingChangedMeansNoNotification() {
        assertNull(message(emptyList(), 0, balance(EVEN), balance(EVEN)))
    }

    // Streaks (spec §25)

    @Test fun dayTwoActivationGetsItsOwnTitle() {
        val m = RunNotifier.message(RunNotifier.Change(listOf(run(1.2, t0)), 0, balance(DEBT, debt = 3.0), balance(DEBT, debt = 1.8), 1.2, 1,
            streakDays = 2, streakDay = true, streakActivated = true))
        assertEquals("2 day streak: 0% APR, earned", m?.title)
        assertEquals("Run logged: 1.2 mi. Paid off 1 beer. 1.8 mi still owed. Your debt interest is now paused.", m?.body)
    }

    @Test fun aStreakDayAddsOneLine() {
        val m = RunNotifier.message(RunNotifier.Change(listOf(run(1.5, t0)), 0, balance(EVEN), balance(CREDIT, credit = 1.5), 0.0, 0, streakDays = 5, streakDay = true))
        assertEquals(RunNotifier.Message("Run logged: 1.5 mi", "1.5 beers banked for later. 🔥 5 day streak, interest paused."), m)
    }

    @Test fun dayOneNudgesTowardTomorrow() {
        val m = RunNotifier.message(RunNotifier.Change(listOf(run(1.0, t0)), 0, balance(DEBT, debt = 2.0), balance(DEBT, debt = 1.0), 1.0, 1, streakDays = 1, streakDay = true))
        assertEquals(true, m?.body?.endsWith("🔥 Day one of a streak. Run 1+ mile tomorrow to pause interest."))
    }

    /**
     * A run that carries the streak to two days makes today interest-protected,
     * so the replay drops today's postings the earlier report had already made.
     * The balance then falls by far more than was run, and the notification
     * used to hand the whole drop to the run.
     */
    @Test fun aStreakDayReportsTheRunNotTheInterestItPaused() {
        val m = RunNotifier.message(RunNotifier.Change(
            listOf(run(1.4, t0)), 0,
            balance(DEBT, debt = 34.35), balance(DEBT, debt = 29.83),
            1.4, 0, streakDays = 3, streakDay = true,
        ))
        // 4.52 mi came off the tab; only 1.4 of it was the run.
        assertEquals("Knocked 1.4 mi off your tab. 29.8 mi still owed. 🔥 3 day streak, interest paused.", m?.body)
    }

    /**
     * The engine behaviour that makes the balance delta unusable, pinned here
     * so a change to it is noticed: protection is derived at replay time, so
     * the run that earns it retroactively removes today's postings the earlier
     * report had already made.
     */
    @Test fun theBalanceDropExceedsWhatTheRunPaidOnADayTheStreakProtects() {
        val utc = ZoneId.of("UTC")
        val midnight = t0.atZone(utc).toLocalDate().atStartOfDay(utc)
        fun d(days: Long, hour: Long) = midnight.plusDays(days).plusHours(hour).toInstant()

        // Evening beers, so their daily postings land at 21:00 -- today's
        // included -- and a streak alive through the two days before today.
        // Rules.OPENING, not Rules(): the bare constructor has streakProtection
        // off, which is how an old ledger decodes. Swift's `ledger()` helper
        // defaults the other way, so a ported test that omits this quietly
        // measures a ledger with no protection at all.
        val books = ledger(rules = Rules.OPENING, openedAt = d(-40, 0)).copy(
            beers = (1L..14L).map { beer(d(-it - 3, 21)) },
            runs = listOf(run(1.2, d(-2, 7)), run(1.3, d(-1, 7))),
        )
        val now = d(0, 22)                      // after today's postings
        val before = BalanceEngine.report(books, now, utc)
        assertEquals(2, before.streak.currentStreakDays)
        assertEquals(false, before.streak.todayProtected)

        val todays = run(1.4, d(0, 7))
        val after = BalanceEngine.report(books.copy(runs = books.runs + todays), now, utc)
        assertEquals(3, after.streak.currentStreakDays)
        assertEquals(true, after.streak.todayProtected)

        val paid = after.runs.first { it.run.id == todays.id }.debtPaidMiles
        val drop = before.balance.debtMiles - after.balance.debtMiles
        assertTrue(close(paid, 1.4))
        // The tab fell by well over the 1.4 that was run: the rest is today's
        // interest, un-posted. Reporting `drop` as the run's doing overstated
        // it by more than the run itself.
        assertTrue("drop $drop should exceed paid $paid by over a mile", drop > paid + 1.0)
    }

    @Test fun aShortRunSaysNothingAboutStreaks() {
        val m = RunNotifier.message(RunNotifier.Change(listOf(run(0.5, t0)), 0, balance(DEBT, debt = 2.0), balance(DEBT, debt = 1.5), 0.5, 0, streakDays = 3, streakDay = false))
        assertEquals("Knocked 0.5 mi off your tab. 1.5 mi still owed.", m?.body)
    }
}
