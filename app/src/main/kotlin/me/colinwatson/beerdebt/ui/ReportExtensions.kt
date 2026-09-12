package me.colinwatson.beerdebt.ui

import me.colinwatson.beerdebt.engine.Report
import java.time.Instant

/** Miles from counted runs that ended in the same calendar week as [now]. */
fun Report.milesRunInWeekOf(now: Instant): Double =
    runs.filter { !it.ignored && Format.sameCalendarWeek(it.run.endedAt, now) }.sumOf { it.run.distanceMiles }
