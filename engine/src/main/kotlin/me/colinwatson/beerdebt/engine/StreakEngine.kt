package me.colinwatson.beerdebt.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One local calendar day in the streak history. */
data class StreakDay(
    val day: LocalDate,
    val miles: Double,
    /** At least a mile of verified running that day. */
    val qualifies: Boolean,
    /** 1 on the first qualifying day of a run of days, counting up; 0 if the day doesn't qualify. */
    val streakNumber: Int,
    /**
     * Interest postings on this day are skipped: it qualifies and so did the
     * day before, or a freeze is holding it.
     */
    val interestProtected: Boolean,
    /**
     * A freeze was spent here: the day did not qualify on its own, and the
     * streak survives it (spec §25.1). Never true on a day that qualifies --
     * a late import that brings the day to a mile refunds the freeze instead.
     */
    val frozen: Boolean = false,
) {
    /**
     * A day that counts as running. Frozen days deliberately do not: they earn
     * no mileage, no repayment, no credit, and no progress toward the next freeze.
     */
    val isRunningDay: Boolean get() = qualifies
}

/**
 * The running streak (beer-debt-ios/docs/spec.md §25): a mile a day builds
 * it, two days in a row pauses debt interest. Derived from the runs on the
 * books, never stored. A port of the Swift `StreakStatus`.
 */
data class StreakStatus(
    /** Days from the first counted run through today, in order, no gaps. */
    val days: List<StreakDay>,
    /** Alive right now: today's if today qualifies, else yesterday's (today can still be run), else 0. */
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val totalQualifyingDays: Int,
    val todayMiles: Double,
    val todayQualifies: Boolean,
    /** Today has already earned its protection (a posting today is skipped). */
    val todayProtected: Boolean,
    /** Freezes in hand: 0 or 1, never more (spec §25.1). */
    val freezesHeld: Int = 0,
    /**
     * Qualifying running days banked toward the next freeze. Stays at 0 while
     * one is held: holding a freeze stops the next accruing.
     */
    val freezeProgressDays: Int = 0,
    /** Today is a rest day paid for with a freeze. */
    val todayFrozen: Boolean = false,
    /**
     * The day a retrospective freeze would repair: the first missed day that
     * broke the streak before it, and only when that is the single break (one
     * freeze cannot repair two days). Null when there is nothing to repair or
     * no freeze to spend.
     */
    val repairableDay: LocalDate? = null,
    val zone: ZoneId,
) {
    /** Two or more days and still alive: the "0% APR — earned" state. */
    val interestProtectionActive: Boolean get() = currentStreakDays >= 2

    /**
     * Offer "Use Freeze Today": a freeze in hand, a streak alive to protect,
     * and today's mile not yet run (spec §25.1). Never consumed automatically.
     */
    val canFreezeToday: Boolean
        get() = freezesHeld > 0 && !todayQualifies && !todayFrozen && currentStreakDays >= 1

    private val index: Map<LocalDate, Int> by lazy { days.withIndex().associate { (i, d) -> d.day to i } }

    fun day(on: Instant): StreakDay? = index[on.atZone(zone).toLocalDate()]?.let { days[it] }
    fun qualifies(on: Instant): Boolean = day(on)?.qualifies ?: false
    fun isProtected(on: Instant): Boolean = day(on)?.interestProtected ?: false
    fun isFrozen(on: Instant): Boolean = day(on)?.frozen ?: false

    /**
     * Which day of a streak [on] was, counting only streaks that reached two
     * days: a lone mile is day one of nothing yet, so it gets no number until
     * the next day qualifies.
     */
    fun streakDayNumber(on: Instant): Int? {
        val i = index[on.atZone(zone).toLocalDate()] ?: return null
        val d = days[i]
        if (!d.qualifies) return null
        if (d.streakNumber >= 2) return d.streakNumber
        val continued = i + 1 < days.size && days[i + 1].streakNumber == 2
        return if (continued) 1 else null
    }

    companion object {
        fun none(zone: ZoneId) = StreakStatus(
            days = emptyList(), currentStreakDays = 0, longestStreakDays = 0, totalQualifyingDays = 0,
            todayMiles = 0.0, todayQualifies = false, todayProtected = false, zone = zone,
        )
    }
}

object StreakEngine {
    /** A mile, less a metre of GPS slack: a watch that says 1.00 mi counts. */
    const val QUALIFYING_METERS = RunEntry.METERS_PER_MILE - 1.0

    /** Qualifying running days that earn one freeze (spec §25.1). */
    const val DAYS_PER_FREEZE = 5
    /** Never more than one in hand. */
    const val MAXIMUM_FREEZES = 1

    /** Pure. Runs that ended by [at], grouped into calendar days in [zone], walked in order. */
    fun calculate(
        runs: List<RunEntry>,
        freezeApplications: List<FreezeApplication> = emptyList(),
        at: Instant,
        zone: ZoneId,
    ): StreakStatus {
        val counted = runs.filter { !it.endedAt.isAfter(at) }
        val today = at.atZone(zone).toLocalDate()
        // Applications are bucketed by local day so they survive a device whose
        // clock or zone moved between the tap and the replay.
        val appliedDays = freezeApplications
            .map { it.day.atZone(zone).toLocalDate() }
            .filter { !it.isAfter(today) }
            .toSet()
        if (counted.isEmpty() && appliedDays.isEmpty()) return StreakStatus.none(zone)

        val metersByDay = HashMap<LocalDate, Double>()
        for (run in counted) {
            val day = run.endedAt.atZone(zone).toLocalDate()
            metersByDay[day] = (metersByDay[day] ?: 0.0) + run.distanceMeters
        }
        val firstDay = minOf(metersByDay.keys.minOrNull() ?: today, appliedDays.minOrNull() ?: today, today)

        val days = ArrayList<StreakDay>()
        var cursor = firstDay
        var previous: StreakDay? = null
        // Freeze inventory as of the day being walked. Both are derived here and
        // never stored, so a deleted or late-imported run simply changes the
        // answer -- the same contract the streak itself has always had.
        var held = 0
        var progress = 0

        while (!cursor.isAfter(today)) {
            val meters = metersByDay[cursor] ?: 0.0
            val qualifies = meters >= QUALIFYING_METERS
            // A day that qualifies on its own never spends a freeze. This is the
            // refund: late Health data brings the day to a mile, the application
            // stops being honoured, and the freeze is back.
            val frozen = !qualifies && appliedDays.contains(cursor) && held > 0

            val number = when {
                qualifies ->
                    if (previous?.qualifies == true || previous?.frozen == true) (previous.streakNumber) + 1 else 1
                // Continuity without credit: the streak keeps its length.
                frozen -> previous?.streakNumber ?: 0
                else -> 0
            }

            val carriedProtection = (previous?.streakNumber ?: 0) >= 2 || (qualifies && number >= 2)
            val entry = StreakDay(
                day = cursor,
                miles = meters / RunEntry.METERS_PER_MILE,
                qualifies = qualifies,
                streakNumber = number,
                interestProtected = (qualifies && number >= 2) || (frozen && carriedProtection),
                frozen = frozen,
            )
            days += entry

            if (frozen) {
                // Spent. The next one needs five new running days.
                held = 0
                progress = 0
            } else if (qualifies && held < MAXIMUM_FREEZES) {
                // Holding one stops the next accruing, so nothing is banked
                // secretly. Progress is cumulative running days, not a streak:
                // a break costs the streak, not the freeze being worked toward.
                progress += 1
                if (progress >= DAYS_PER_FREEZE) {
                    held = MAXIMUM_FREEZES
                    progress = 0
                }
            }

            previous = entry
            cursor = cursor.plusDays(1)
        }

        val todayEntry = days.last()
        val yesterday = if (days.size >= 2) days[days.size - 2] else null
        val current = if (todayEntry.qualifies || todayEntry.frozen) todayEntry.streakNumber
        else (yesterday?.streakNumber ?: 0)

        return StreakStatus(
            days = days,
            currentStreakDays = current,
            longestStreakDays = days.maxOf { it.streakNumber },
            totalQualifyingDays = days.count { it.qualifies },
            todayMiles = todayEntry.miles,
            todayQualifies = todayEntry.qualifies,
            todayProtected = todayEntry.interestProtected,
            freezesHeld = held,
            freezeProgressDays = progress,
            todayFrozen = todayEntry.frozen,
            repairableDay = repairable(days, held),
            zone = zone,
        )
    }

    /**
     * The single missed day a freeze in hand could repair.
     *
     * Only the first day that broke the streak, and only when it is the only
     * break: one freeze protects one day, so if the day after is also missed
     * the streak breaks there anyway and repairing the first buys nothing.
     */
    private fun repairable(days: List<StreakDay>, held: Int): LocalDate? {
        if (held <= 0 || days.size < 2) return null
        // Skip today, which is not a miss yet.
        val history = days.subList(0, days.size - 1)
        val breakIndex = history.indexOfLast { !it.qualifies && !it.frozen }
        if (breakIndex < 0) return null
        // The streak it broke has to have been worth protecting.
        val before = if (breakIndex > 0) history[breakIndex - 1] else null
        if (before == null || !(before.qualifies || before.frozen) || before.streakNumber < 1) return null
        // Every day since the break must already count, or one freeze is not enough.
        if (!history.drop(breakIndex + 1).all { it.qualifies || it.frozen }) return null
        return history[breakIndex].day
    }
}
