package me.colinwatson.beerdebt.ui

import me.colinwatson.beerdebt.engine.Report
import java.time.Instant

/**
 * Miles from counted workouts that ended in the same calendar week as [now]:
 * a run's distance, or a ride's calories at the rate (iOS spec §28).
 */
fun Report.milesRunInWeekOf(now: Instant): Double =
    runs.filter { !it.ignored && Format.sameCalendarWeek(it.run.endedAt, now) }.sumOf { it.paidMiles }
