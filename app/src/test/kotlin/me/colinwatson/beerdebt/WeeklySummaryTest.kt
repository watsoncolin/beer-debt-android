package me.colinwatson.beerdebt

import me.colinwatson.beerdebt.notify.WeeklySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId

/** The recap copy is a pure function of the ledger and the fire time; same cases as iOS WeeklySummaryTests. */
class WeeklySummaryTest {
    private val utc = ZoneId.of("UTC")

    @Test fun aTypicalWeek() {
        val l = ledger(openedAt = at(-10 * DAY), beers = listOf(beer(at(-2 * DAY)), beer(at(-DAY))), runs = listOf(run(1.5, at(-3 * DAY))))
        val m = WeeklySummary.message(l, t0)
        assertEquals("Your week in beer", m.title)
        assertTrue(m.body, m.body.startsWith("2 beers, 1.5 mi run."))
        assertTrue(m.body, m.body.endsWith("owed."))
    }

    @Test fun aQuietWeekIsSuspicious() {
        assertEquals("0 beers, 0.0 mi run. Nothing to report. Suspicious.", WeeklySummary.message(ledger(), t0).body)
    }

    @Test fun beersWithoutRunningGetsANudge() {
        val l = ledger(openedAt = at(-10 * DAY), beers = listOf(beer(at(-DAY))))
        // One posting has landed by the time it fires: 1.0 → 1.1.
        assertEquals("1 beer, 0.0 mi run. The running part is optional, apparently. You're at 1.1 mi owed.", WeeklySummary.message(l, t0).body)
    }

    @Test fun standingIsProjectedToTheFireTime() {
        // Beer on Saturday; the summary fires a week later, after seven daily postings (1.1^7 = 1.95).
        val m = WeeklySummary.message(ledger(beers = listOf(beer(t0))), at(WEEK))
        assertTrue(m.body, m.body.endsWith("You're at 1.9 mi owed."))
    }

    @Test fun nextFourSundaysAtSix() {
        val dates = WeeklySummary.nextFireDates(t0, weekday = 1, hour = 18, minute = 0, count = 4, zone = utc)
        assertEquals(4, dates.size)
        dates.forEachIndexed { i, d ->
            val z = d.atZone(utc)
            assertEquals(DayOfWeek.SUNDAY, z.dayOfWeek)
            assertEquals(18, z.hour)
            assertEquals(0, z.minute)
            if (i > 0) assertEquals(WEEK, d.epochSecond - dates[i - 1].epochSecond)
        }
        assertTrue(dates[0].isAfter(t0))
    }

    @Test fun aFireTimeEqualToNowRollsToNextWeek() {
        // t0 is Saturday 20:00 UTC; asking for Saturday 20:00 must give next Saturday, not now.
        val next = WeeklySummary.nextFireDates(t0, weekday = 7, hour = 20, minute = 0, count = 1, zone = utc).first()
        assertEquals(at(WEEK), next)
    }
}
