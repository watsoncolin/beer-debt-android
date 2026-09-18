package me.colinwatson.beerdebt

import me.colinwatson.beerdebt.data.LedgerStore
import me.colinwatson.beerdebt.engine.RunEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The store's half of streak freezes (spec §25.1): it never spends one on its
 * own, and it refuses any day but today or the single break a freeze could
 * repair — the MVP guard against arbitrary history editing.
 *
 * The engine's rules are pinned by the 84 Swift fixtures, so these cover only
 * what the store adds. Mirrors iOS `StreakFreezeStoreTests`.
 */
class StreakFreezeStoreTest {
    private val utc: ZoneId = ZoneId.of("UTC")

    /** 07:00 UTC on t0 + n days, so a run lands mid-day in the pinned zone. */
    private fun morning(n: Long): Instant = t0.plusSeconds(n * DAY - 13 * HOUR)

    private fun tempDir(): File = Files.createTempDirectory("beerdebt-freeze").toFile()

    /**
     * The books open at the start of day 0. t0 itself is 20:00, which would
     * drop that morning's run as pre-books and shift every count by one.
     */
    private fun store(runDays: List<Long>, dir: File = tempDir()): LedgerStore {
        val opened = morning(0).atZone(utc).toLocalDate().atStartOfDay(utc).toInstant()
        val store = LedgerStore(dir, now = opened)
        store.importRuns(runDays.map { n ->
            RunEntry(
                UUID.randomUUID(), UUID.randomUUID(), morning(n).minusSeconds(30 * 60), morning(n),
                RunEntry.METERS_PER_MILE, morning(n), "Test",
            )
        })
        return store
    }

    @Test fun `today is frozen only when a freeze is held`() {
        // Four running days: nothing earned, so nothing to spend.
        val short = store(listOf(0, 1, 2, 3))
        assertFalse(short.freezeToday(morning(4), utc))
        assertTrue(short.current.freezeApplications.isEmpty())

        // The fifth earns one, and today can be frozen -- once.
        val earned = store(listOf(0, 1, 2, 3, 4))
        assertTrue(earned.freezeToday(morning(5), utc))
        assertEquals(1, earned.current.freezeApplications.size)
        assertFalse(earned.freezeToday(morning(5), utc))
        assertEquals(1, earned.current.freezeApplications.size)
    }

    @Test fun `the break that ended the streak can be repaired`() {
        // Days 0-4 run (earning the freeze on day 4), day 5 missed, day 6 run.
        val s = store(listOf(0, 1, 2, 3, 4, 6))
        assertTrue(s.applyFreeze(morning(5), morning(6), utc))
        assertTrue(s.report(morning(6)).streak.isFrozen(morning(5)))
        assertEquals(6, s.report(morning(6)).streak.currentStreakDays)
    }

    @Test fun `an arbitrary historical day is refused`() {
        val s = store((0L..11L).toList())
        assertEquals(1, s.report(morning(11)).streak.freezesHeld)
        // Day 2 is neither today nor a break: refused, and nothing written.
        assertFalse(s.applyFreeze(morning(2), morning(11), utc))
        assertTrue(s.current.freezeApplications.isEmpty())
    }

    @Test fun `a freeze cannot be spent on a day before it was earned`() {
        // Days 1-4 and 6 run, day 5 missed. The fifth running day is day 6, so
        // the freeze is not in hand until after the break it would repair.
        val dir = tempDir()
        val opened = morning(1).atZone(utc).toLocalDate().atStartOfDay(utc).toInstant()
        val s = LedgerStore(dir, now = opened)
        s.importRuns(listOf(1L, 2, 3, 4, 6).map { n ->
            RunEntry(UUID.randomUUID(), UUID.randomUUID(), morning(n).minusSeconds(1800), morning(n), RunEntry.METERS_PER_MILE, morning(n), "Test")
        })
        assertEquals(1, s.report(morning(6)).streak.freezesHeld)
        // The store allows the tap, since day 5 is the break...
        assertTrue(s.applyFreeze(morning(5), morning(6), utc))
        // ...but the replay refuses to honour it: nothing was held on day 5.
        val streak = s.report(morning(6)).streak
        assertFalse(streak.isFrozen(morning(5)))
        assertEquals(1, streak.currentStreakDays)
    }

    @Test fun `a freeze survives a relaunch`() {
        val dir = tempDir()
        val first = store(listOf(0, 1, 2, 3, 4), dir)
        assertTrue(first.freezeToday(morning(5), utc))

        val second = LedgerStore(dir, now = morning(5))
        assertEquals(1, second.current.freezeApplications.size)
        assertTrue(second.report(morning(5)).streak.todayFrozen)
    }
}
