package me.colinwatson.beerdebt

import me.colinwatson.beerdebt.data.LedgerStore
import me.colinwatson.beerdebt.engine.RunEntry
import me.colinwatson.beerdebt.ui.PaidSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/**
 * The bands behind the paid list's colour, and the figure they read. Mirrors
 * the Swift `PaidSeverityTests` / `PaidMilesTests` so the two cannot drift.
 */
class PaidSeverityTest {
    @Test fun `a beer that cost about a mile is ordinary`() {
        assertEquals(PaidSeverity.ORDINARY, PaidSeverity.of(0.0))
        assertEquals(PaidSeverity.ORDINARY, PaidSeverity.of(1.0))
        assertEquals(PaidSeverity.ORDINARY, PaidSeverity.of(1.99))
    }

    @Test fun `two miles is dear and three got away`() {
        // The boundaries belong to the harsher band, so "over 2" reads as dear
        // from 2.00 exactly.
        assertEquals(PaidSeverity.DEAR, PaidSeverity.of(2.0))
        assertEquals(PaidSeverity.DEAR, PaidSeverity.of(2.99))
        assertEquals(PaidSeverity.STEEP, PaidSeverity.of(3.0))
        assertEquals(PaidSeverity.STEEP, PaidSeverity.of(12.4))
    }

    @Test fun `the bands are the ones the spec names`() {
        assertEquals(2.0, PaidSeverity.DEAR_MILES, 0.0)
        assertEquals(3.0, PaidSeverity.STEEP_MILES, 0.0)
    }

    // --- the figure itself ---

    private fun tempDir(): File = Files.createTempDirectory("beerdebt-paid").toFile()

    private fun run(miles: Double, endedAt: Instant) = RunEntry(
        UUID.randomUUID(), UUID.randomUUID(), endedAt.minusSeconds(1800), endedAt,
        miles * RunEntry.METERS_PER_MILE, endedAt, "Test",
    )

    @Test fun `a beer left to accrue costs more running than its price`() {
        val store = LedgerStore(tempDir(), now = t0)
        store.addBeer(t0)
        store.importRuns(listOf(run(6.0, at(11 * DAY))))
        val paid = store.report(at(12 * DAY)).beers[0]
        assertTrue(paid.isPaid)
        assertEquals("the price at the bar never changes", 1.0, paid.costMiles, 1e-9)
        assertTrue("but it took more than two miles to clear", paid.paidMiles > 2.0)
        assertTrue(PaidSeverity.of(paid.paidMiles) != PaidSeverity.ORDINARY)
    }

    @Test fun `a beer run off the same day costs its price`() {
        val store = LedgerStore(tempDir(), now = t0)
        store.addBeer(t0)
        store.importRuns(listOf(run(1.0, at(HOUR))))
        val paid = store.report(at(2 * HOUR)).beers[0]
        assertTrue(paid.isPaid)
        assertEquals(1.0, paid.paidMiles, 1e-6)
        assertEquals(PaidSeverity.ORDINARY, PaidSeverity.of(paid.paidMiles))
    }

    @Test fun `a beer settled from credit took no running at all`() {
        val store = LedgerStore(tempDir(), now = t0)
        store.importRuns(listOf(run(2.0, at(HOUR))))
        store.addBeer(at(2 * HOUR))
        val paid = store.report(at(3 * HOUR)).beers[0]
        assertTrue(paid.settledByCredit)
        // Honest: no run went into it. The row says "from credit" beside it.
        assertEquals(0.0, paid.paidMiles, 1e-9)
    }
}
