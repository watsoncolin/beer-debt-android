package me.colinwatson.beerdebt

import me.colinwatson.beerdebt.engine.BalanceState.CREDIT
import me.colinwatson.beerdebt.engine.BalanceState.DEBT
import me.colinwatson.beerdebt.engine.BalanceState.EVEN
import me.colinwatson.beerdebt.notify.RunNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The notification copy is a pure function of what a sync changed; same cases as iOS RunNotifierTests. */
class RunNotifierTest {
    private fun message(added: List<Double>, removed: Int, before: me.colinwatson.beerdebt.engine.Balance, after: me.colinwatson.beerdebt.engine.Balance, paidOff: Int = 0) =
        RunNotifier.message(RunNotifier.Change(added.map { run(it, t0) }, removed, before, after, paidOff))

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
}
