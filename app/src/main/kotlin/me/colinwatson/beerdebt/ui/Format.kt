package me.colinwatson.beerdebt.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How dear a paid beer turned out to be, by the miles actually run to clear
 * it. Thresholds are absolute miles, not a multiple of the price, because what
 * the reader is judging is the run they had to go on.
 *
 * Mirrors iOS `Format.PaidSeverity`, so the bands cannot drift apart.
 */
enum class PaidSeverity {
    /** About what a beer should cost. */
    ORDINARY,
    /** It sat long enough to matter. */
    DEAR,
    /** It got away. */
    STEEP;

    companion object {
        const val DEAR_MILES = 2.0
        const val STEEP_MILES = 3.0

        fun of(milesRun: Double): PaidSeverity = when {
            milesRun >= STEEP_MILES -> STEEP
            milesRun >= DEAR_MILES -> DEAR
            else -> ORDINARY
        }
    }
}

/** Display formatting, matching iOS `Format`. */
object Format {
    fun number(value: Double, decimals: Int = 1): String = String.format(Locale.getDefault(), "%.${decimals}f", value)
    fun miles(value: Double, decimals: Int = 1): String = "${number(value, decimals)} mi"

    /** 2 → "2", 2.5 → "2.5", 2.37 → "2.4" */
    fun beers(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else number(rounded, 1)
    }

    fun beersLabel(value: Double): String { val t = beers(value); return t + if (t == "1") " beer" else " beers" }
    fun percent(rate: Double): String = "${(rate * 100).roundToInt()}%"

    fun gracePeriod(seconds: Double): String {
        if (seconds <= 0) return "None"
        val hours = seconds / 3600
        if (hours < 48) return "${hours.toInt()} hours"
        val days = (seconds / 86_400).toInt()
        return if (days == 1) "1 day" else "$days days"
    }

    fun age(from: Instant, to: Instant): String {
        val days = ((to.epochSecond - from.epochSecond) / 86_400).toInt()
        return when { days < 1 -> "today"; days == 1 -> "1 day old"; else -> "$days days old" }
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dateTimeFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a")
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
    private val dayFmt = DateTimeFormatter.ofPattern("MMM d")
    private val dayHeaderFmt = DateTimeFormatter.ofPattern("EEEE, MMM d")

    fun dateTime(instant: Instant): String = dateTimeFmt.format(instant.atZone(zone))
    fun time(instant: Instant): String = timeFmt.format(instant.atZone(zone))
    /** A time of day with no date, e.g. "6:00 PM". */
    fun clock(hour: Int, minute: Int): String = timeFmt.format(java.time.LocalTime.of(hour, minute))
    fun day(instant: Instant): String = dayFmt.format(instant.atZone(zone))

    fun dayHeader(instant: Instant, now: Instant = Instant.now()): String {
        val d = instant.atZone(zone).toLocalDate(); val today = now.atZone(zone).toLocalDate()
        return when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> dayHeaderFmt.format(d) }
    }

    /**
     * "yesterday", "Tuesday", "Sep 9" — the day named mid-sentence, so it stays
     * lowercase where [dayHeader] is capitalised for a header.
     */
    fun dayPhrase(instant: Instant, now: Instant = Instant.now()): String {
        val d = instant.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when {
            d == today -> "today"
            d == today.minusDays(1) -> "yesterday"
            // Within the last week a weekday is clearer than a date.
            !d.isBefore(today.minusDays(6)) ->
                d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
            else -> day(instant)
        }
    }

    fun relative(instant: Instant, now: Instant = Instant.now()): String {
        val d = instant.atZone(zone).toLocalDate(); val today = now.atZone(zone).toLocalDate()
        val clock = time(instant)
        return when (d) { today -> "today at $clock"; today.plusDays(1) -> "tomorrow at $clock"; else -> "${day(instant)} at $clock" }
    }

    fun sameCalendarWeek(a: Instant, b: Instant): Boolean {
        val wf = java.time.temporal.WeekFields.of(Locale.getDefault())
        val da = a.atZone(zone).toLocalDate(); val db = b.atZone(zone).toLocalDate()
        return da.get(wf.weekBasedYear()) == db.get(wf.weekBasedYear()) && da.get(wf.weekOfWeekBasedYear()) == db.get(wf.weekOfWeekBasedYear())
    }
}
