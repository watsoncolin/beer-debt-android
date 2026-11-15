package me.colinwatson.beerdebt

import me.colinwatson.beerdebt.data.LedgerStore
import me.colinwatson.beerdebt.engine.Balance
import me.colinwatson.beerdebt.engine.BalanceEngine
import me.colinwatson.beerdebt.engine.BalanceState
import me.colinwatson.beerdebt.engine.Ledger
import me.colinwatson.beerdebt.engine.PaymentBasis
import me.colinwatson.beerdebt.engine.PaymentMode
import me.colinwatson.beerdebt.engine.RunEntry
import me.colinwatson.beerdebt.notify.RunNotifier
import me.colinwatson.beerdebt.notify.WeeklySummary
import me.colinwatson.beerdebt.ui.Units
import me.colinwatson.beerdebt.ui.milesRunInWeekOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Calorie mode outside the engine (iOS spec §28); same cases as iOS CalorieDisplayTests and CalorieModeStoreTests. */
class CalorieModeTest {
    private fun tempDir(): File = Files.createTempDirectory("beerdebt-tests").toFile()
    private val kcal = Units(PaymentBasis(PaymentMode.CALORIES, 100.0))

    private fun ride(kcal: Double?, endedAt: Instant, workoutID: UUID = UUID.randomUUID()) =
        RunEntry(UUID.randomUUID(), workoutID, endedAt.minusSeconds(45 * 60), endedAt, 0.0, endedAt, "Test",
            activityType = 13, activeEnergyKcal = kcal)

    private fun inCalories(l: Ledger, rate: Double = 100.0) = l.withPayment(PaymentBasis(PaymentMode.CALORIES, rate))

    @Test fun caloriesAreWholeNumbersAtTheRate() {
        assertEquals("2.3 mi", Units.MILES.amount(2.34))
        assertEquals("235 kcal", kcal.amount(2.345))
        assertEquals("10 kcal", kcal.amount(0.1, 2))
        assertEquals("1,234", kcal.number(12.34))
        assertEquals("120 kcal", Units(PaymentBasis(PaymentMode.CALORIES, 120.0)).amount(1.0))
    }

    @Test fun caloriesGoCompactAtFourFiguresNotFive() {
        assertEquals("450 kcal", kcal.compactAmount(4.5))
        assertEquals("1.2k kcal", kcal.compactAmount(12.4))
        assertEquals("124k kcal", kcal.compactAmount(1_240.0))
    }

    @Test fun theDailyGoalIsOneMilesWorth() {
        assertEquals("Run 1+ mile", Units.MILES.dailyGoal)
        assertEquals("Burn 100+ kcal", kcal.dailyGoal)
    }

    @Test fun aRideNotificationNamesTheRideInCalories() {
        val before = Balance(BalanceState.DEBT, 3.0, 3.0, 0.0, 0.3, 0.0, 0.0)
        val after = Balance(BalanceState.DEBT, 1.5, 1.5, 0.0, 0.15, 0.0, 0.0)
        val m = RunNotifier.message(RunNotifier.Change(listOf(ride(150.0, t0)), 0, before, after, 1.5, 0, payment = kcal.payment))
        assertEquals(RunNotifier.Message("Ride logged: 150 kcal", "Knocked 150 kcal off your tab. 150 kcal still owed."), m)
    }

    @Test fun theWeeklySummaryCountsRidesInCalorieMode() {
        val base = ledger(openedAt = at(-10 * DAY), runs = listOf(run(1.0, at(-3 * DAY)), ride(250.0, at(-2 * DAY))))
        assertTrue(WeeklySummary.message(base, t0).body.startsWith("0 beers, 1.0 mi run."))
        assertTrue(WeeklySummary.message(inCalories(base), t0).body.startsWith("0 beers, 350 kcal burned."))
    }

    @Test fun aWeeksWorkoutsCountRidesOnlyInCalorieMode() {
        val base = ledger(openedAt = at(-10 * DAY), runs = listOf(run(1.0, at(-HOUR)), ride(250.0, at(-2 * HOUR))))
        val zone = ZoneId.of("UTC")
        assertTrue(close(1.0, BalanceEngine.report(base, t0, zone).milesRunInWeekOf(t0)))
        assertTrue(close(3.5, BalanceEngine.report(inCalories(base), t0, zone).milesRunInWeekOf(t0)))
    }

    @Test fun switchingIsSavedAndClamped() {
        val dir = tempDir()
        val store = LedgerStore(dir, now = t0)
        store.updatePayment(PaymentBasis(PaymentMode.CALORIES, 99.6))
        assertEquals(PaymentBasis(PaymentMode.CALORIES, 100.0), store.current.payment)
        store.updatePayment(PaymentBasis(PaymentMode.CALORIES, 5.0))
        assertEquals(LedgerStore.CALORIES_PER_MILE_RANGE.start, store.current.caloriesPerMile, 0.0)
        // Not a rules change: nothing is appended to the history.
        assertEquals(1, store.current.rulesHistory.size)
        assertEquals(PaymentMode.CALORIES, LedgerStore(dir, now = at(HOUR)).current.paymentMode)
    }

    @Test fun bankruptcyKeepsTheBasisAndWritesOffRides() {
        val store = LedgerStore(tempDir(), now = t0)
        store.updatePayment(PaymentBasis(PaymentMode.CALORIES, 120.0))
        val workout = UUID.randomUUID()
        store.importRuns(listOf(ride(300.0, at(HOUR), workout)))
        assertTrue(store.declareBankruptcy(at(2 * HOUR)))
        assertEquals(PaymentBasis(PaymentMode.CALORIES, 120.0), store.current.payment)
        assertTrue(workout in store.current.excludedWorkoutIDs)
    }

    @Test fun aLedgerFromBeforeCalorieModeReadsAsRuns() {
        val json = """
            {"version":1,"booksOpenedAt":"2026-09-12T20:00:00Z","beers":[],"excludedWorkoutIDs":[],
             "rulesHistory":[{"effectiveAt":"2026-09-12T20:00:00Z","rules":{"creditDecayRatePerWeek":0.1,"gracePeriod":86400,
               "interestPeriod":"daily","interestRate":0.1,"maximumCreditBeers":3,"milesPerBeer":1,"streakProtection":true}}],
             "runs":[{"id":"00000000-0000-4000-8000-000000000001","healthKitWorkoutID":"00000000-0000-4000-8000-000000000002",
               "startedAt":"2026-09-12T20:00:00Z","endedAt":"2026-09-12T20:30:00Z","distanceMeters":1609.344,
               "importedAt":"2026-09-12T20:30:00Z"}]}
        """.trimIndent()
        val l = LedgerStore.json.decodeFromString<Ledger>(json)
        assertEquals(PaymentBasis.RUNS, l.payment)
        assertTrue(l.runs[0].isRunning)
    }
}
