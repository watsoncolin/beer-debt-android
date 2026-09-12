package me.colinwatson.beerdebt

import me.colinwatson.beerdebt.data.LedgerStore
import me.colinwatson.beerdebt.engine.BalanceState
import me.colinwatson.beerdebt.engine.Ledger
import me.colinwatson.beerdebt.engine.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** The store on disk behaves like iOS LedgerStoreTests say it should. */
class LedgerStoreTest {
    private fun tempDir(): File = Files.createTempDirectory("beerdebt-tests").toFile()

    @Test fun freshStoreOpensTheBooksAtZero() {
        val store = LedgerStore(tempDir(), now = t0)
        assertTrue(store.current.beers.isEmpty())
        assertTrue(store.current.runs.isEmpty())
        assertEquals(1, store.current.rulesHistory.size)
        assertEquals(t0, store.current.booksOpenedAt)
        assertEquals(Rules.OPENING, store.currentRules)
        assertEquals(BalanceState.EVEN, store.report(at(HOUR)).balance.state)
    }

    @Test fun beersSurviveARelaunchWithTheSameBalance() {
        val dir = tempDir()
        val first = LedgerStore(dir, now = t0)
        val added = first.addBeer(at(HOUR))
        val second = LedgerStore(dir, now = at(DAY))
        assertEquals(listOf(added), second.current.beers)
        assertEquals(t0, second.current.booksOpenedAt)
        assertEquals(first.report(at(3 * DAY)), second.report(at(3 * DAY)))
    }

    @Test fun sameWorkoutIsNeverCountedTwice() {
        val store = LedgerStore(tempDir(), now = t0)
        val workout = UUID.randomUUID()
        val entry = run(3.0, at(HOUR), workout)
        assertEquals(1, store.importRuns(listOf(entry)).size)
        assertTrue(store.importRuns(listOf(entry)).isEmpty())
        assertTrue(store.importRuns(listOf(run(3.0, at(HOUR), workout))).isEmpty())
        assertEquals(1, store.current.runs.size)
        assertTrue(close(store.report(at(HOUR)).balance.creditMiles, 3.0))
    }

    @Test fun aWorkoutDeletedFromHealthComesOffTheBooks() {
        val dir = tempDir()
        val store = LedgerStore(dir, now = t0)
        store.addBeer(t0)
        val workout = UUID.randomUUID()
        store.importRuns(listOf(run(1.0, at(HOUR), workout)))
        assertEquals(BalanceState.EVEN, store.report(at(2 * HOUR)).balance.state)

        assertEquals(1, store.removeRuns(setOf(workout)))
        assertEquals(0, store.removeRuns(setOf(workout)))
        assertEquals(0, store.removeRuns(emptySet()))
        val r = store.report(at(2 * HOUR))
        assertEquals(BalanceState.DEBT, r.balance.state)
        assertTrue(close(r.balance.debtMiles, 1.0))
        assertFalse(r.beers[0].isPaid)
        assertTrue(LedgerStore(dir, now = at(DAY)).current.runs.isEmpty())
    }

    @Test fun aRunDeletedInTheAppStaysOffTheBooks() {
        val dir = tempDir()
        val store = LedgerStore(dir, now = t0)
        store.addBeer(t0)
        val workout = UUID.randomUUID()
        val entry = run(1.0, at(HOUR), workout)
        store.importRuns(listOf(entry))
        assertEquals(BalanceState.EVEN, store.report(at(2 * HOUR)).balance.state)

        assertTrue(store.deleteRun(entry.id))
        assertFalse(store.deleteRun(entry.id))
        assertEquals(BalanceState.DEBT, store.report(at(2 * HOUR)).balance.state)
        assertTrue(store.importRuns(listOf(run(1.0, at(HOUR), workout))).isEmpty())
        val reloaded = LedgerStore(dir, now = at(DAY))
        assertTrue(reloaded.current.runs.isEmpty())
        assertEquals(setOf(workout), reloaded.current.excludedWorkoutIDs)
    }

    @Test fun rulesChangesAppendForwardOnly() {
        val store = LedgerStore(tempDir(), now = t0)
        val rules = store.currentRules.copy(interestRate = 0.2)
        store.updateRules(rules, at(HOUR))
        store.updateRules(rules, at(2 * HOUR))
        assertEquals(2, store.current.rulesHistory.size)
        assertEquals(at(HOUR), store.current.rulesHistory.last().effectiveAt)
        assertEquals(0.2, store.currentRules.interestRate, 0.0)
    }

    @Test fun aForgottenBeerCanBeDatedBack() {
        val dir = tempDir()
        val store = LedgerStore(dir, now = t0)
        val beer = store.addBeer(at(2 * DAY))
        assertEquals(at(2 * DAY), beer.recordedAt)
        assertFalse(beer.isBackdated)

        val moved = store.updateBeerDate(beer.id, at(DAY).plusMillis(400), now = at(2 * DAY))
        assertNotNull(moved)
        assertEquals(at(DAY), moved!!.createdAt)
        assertEquals(at(2 * DAY), moved.recordedAt)
        assertTrue(moved.isBackdated)
        // The books are redone: it is a day old now, so interest has posted.
        assertTrue(close(store.report(at(2 * DAY)).balance.debtMiles, 1.10))
        assertEquals(store.current.beers, LedgerStore(dir, now = at(3 * DAY)).current.beers)
    }

    @Test fun aBeerCanBeTakenOffTheBooks() {
        val dir = tempDir()
        val store = LedgerStore(dir, now = t0)
        val keep = store.addBeer(at(HOUR))
        val mistake = store.addBeer(at(2 * HOUR))
        assertTrue(store.removeBeer(mistake.id))
        assertFalse(store.removeBeer(mistake.id))
        assertEquals(listOf(keep), store.current.beers)
        assertTrue(close(store.report(at(3 * HOUR)).balance.debtMiles, 1.0))
        assertEquals(listOf(keep), LedgerStore(dir, now = at(DAY)).current.beers)
    }

    @Test fun beerDatesAreClampedToTheLastThirtyDaysAndNow() {
        val store = LedgerStore(tempDir(), now = t0)
        val beer = store.addBeer(at(40 * DAY))
        assertEquals(at(10 * DAY), store.updateBeerDate(beer.id, at(-5 * DAY), now = at(40 * DAY))!!.createdAt)
        assertEquals(at(40 * DAY), store.updateBeerDate(beer.id, at(41 * DAY), now = at(40 * DAY))!!.createdAt)
    }

    @Test fun unreadableLedgerIsSetAsideNotDeleted() {
        val dir = tempDir()
        File(dir, "ledger.json").writeText("{ this is not json")
        val store = LedgerStore(dir, now = t0)
        assertNotNull(store.loadError)
        assertEquals(t0, store.current.booksOpenedAt)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("ledger-unreadable-") })
    }

    @Test fun timestampsAreWholeSecondsSoAReloadIsExact() {
        val dir = tempDir()
        val store = LedgerStore(dir, now = t0.plusMillis(789))
        val beer = store.addBeer(at(HOUR).plusMillis(123))
        assertEquals(at(HOUR), beer.createdAt)
        assertEquals(t0, store.current.booksOpenedAt)
        assertEquals(store.current, LedgerStore(dir, now = at(DAY)).current)
    }

    @Test fun theBooksCanBeOpenedEarlierButNotLater() {
        val dir = tempDir()
        val store = LedgerStore(dir, now = t0)
        assertFalse(store.reopenBooks(at(DAY), now = at(2 * DAY)))
        assertFalse(store.reopenBooks(t0, now = at(2 * DAY)))
        assertTrue(store.reopenBooks(at(-3 * DAY), now = at(2 * DAY)))
        assertEquals(at(-3 * DAY), store.current.booksOpenedAt)
        assertEquals(at(-3 * DAY), store.current.rulesHistory.first().effectiveAt)
        store.importRuns(listOf(run(2.0, at(-2 * DAY))))
        assertEquals(false, store.report(at(2 * DAY)).runs.first().ignored)
        assertEquals(at(-3 * DAY), LedgerStore(dir, now = at(3 * DAY)).current.booksOpenedAt)
    }

    @Test fun reopeningIsClampedToAYear() {
        val store = LedgerStore(tempDir(), now = t0)
        assertTrue(store.reopenBooks(at(-800 * DAY), now = t0))
        assertEquals(at(-365 * DAY), store.current.booksOpenedAt)
    }

    @Test fun anOldLedgerGetsStreakProtectionSwitchedOnForwardOnly() {
        val dir = tempDir()
        val old = Ledger.open(t0, Rules())   // written before the feature: protection off
        File(dir, "ledger.json").writeText(LedgerStore.json.encodeToString(Ledger.serializer(), old))
        val store = LedgerStore(dir, now = at(DAY))
        assertEquals(2, store.current.rulesHistory.size)
        assertEquals(false, store.current.rulesHistory[0].rules.streakProtection)
        assertEquals(true, store.currentRules.streakProtection)
        assertEquals(at(DAY), store.current.rulesHistory[1].effectiveAt)
    }
}
