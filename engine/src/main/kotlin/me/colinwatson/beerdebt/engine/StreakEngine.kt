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
    /** Interest postings on this day are skipped: it qualifies and so did the day before. */
    val interestProtected: Boolean,
)

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
    val zone: ZoneId,
) {
    /** Two or more days and still alive: the "0% APR — earned" state. */
    val interestProtectionActive: Boolean get() = currentStreakDays >= 2

    private val index: Map<LocalDate, Int> by lazy { days.withIndex().associate { (i, d) -> d.day to i } }

    fun day(on: Instant): StreakDay? = index[on.atZone(zone).toLocalDate()]?.let { days[it] }
    fun qualifies(on: Instant): Boolean = day(on)?.qualifies ?: false
    fun isProtected(on: Instant): Boolean = day(on)?.interestProtected ?: false

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
        fun none(zone: ZoneId) = StreakStatus(emptyList(), 0, 0, 0, 0.0, false, false, zone)
    }
}

object StreakEngine {
    /** A mile, less a metre of GPS slack: a watch that says 1.00 mi counts. */
    const val QUALIFYING_METERS = RunEntry.METERS_PER_MILE - 1.0

    /** Pure. Runs that ended by [at], grouped into calendar days in [zone], walked in order. */
    fun calculate(runs: List<RunEntry>, at: Instant, zone: ZoneId): StreakStatus {
        val counted = runs.filter { !it.endedAt.isAfter(at) }
        if (counted.isEmpty()) return StreakStatus.none(zone)

        val metersByDay = HashMap<LocalDate, Double>()
        for (run in counted) {
            val day = run.endedAt.atZone(zone).toLocalDate()
            metersByDay[day] = (metersByDay[day] ?: 0.0) + run.distanceMeters
        }
        val today = at.atZone(zone).toLocalDate()
        val firstDay = minOf(metersByDay.keys.min(), today)

        val days = ArrayList<StreakDay>()
        var cursor = firstDay
        var previous: StreakDay? = null
        while (!cursor.isAfter(today)) {
            val meters = metersByDay[cursor] ?: 0.0
            val qualifies = meters >= QUALIFYING_METERS
            val number = when {
                !qualifies -> 0
                previous?.qualifies == true -> previous.streakNumber + 1
                else -> 1
            }
            val entry = StreakDay(cursor, meters / RunEntry.METERS_PER_MILE, qualifies, number, qualifies && number >= 2)
            days += entry
            previous = entry
            cursor = cursor.plusDays(1)
        }

        val todayEntry = days.last()
        val yesterday = if (days.size >= 2) days[days.size - 2] else null
        val current = if (todayEntry.qualifies) todayEntry.streakNumber else (yesterday?.streakNumber ?: 0)
        return StreakStatus(
            days = days,
            currentStreakDays = current,
            longestStreakDays = days.maxOf { it.streakNumber },
            totalQualifyingDays = days.count { it.qualifies },
            todayMiles = todayEntry.miles,
            todayQualifies = todayEntry.qualifies,
            todayProtected = todayEntry.interestProtected,
            zone = zone,
        )
    }
}
