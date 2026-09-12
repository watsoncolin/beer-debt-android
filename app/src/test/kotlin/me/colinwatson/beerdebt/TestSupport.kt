package me.colinwatson.beerdebt

import me.colinwatson.beerdebt.engine.Balance
import me.colinwatson.beerdebt.engine.BalanceState
import me.colinwatson.beerdebt.engine.BeerEntry
import me.colinwatson.beerdebt.engine.Ledger
import me.colinwatson.beerdebt.engine.Rules
import me.colinwatson.beerdebt.engine.RunEntry
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

// The same fixtures as the iOS BeerDebtTests/TestSupport.swift.
const val HOUR = 3_600L
const val DAY = 86_400L
const val WEEK = 7 * DAY

/** 2026-09-12 20:00:00 UTC, a Saturday night at the bar. */
val t0: Instant = Instant.ofEpochSecond(1_789_243_200)

fun at(offset: Long): Instant = t0.plusSeconds(offset)

fun ledger(rules: Rules = Rules(), openedAt: Instant = t0, beers: List<BeerEntry> = emptyList(), runs: List<RunEntry> = emptyList()): Ledger =
    Ledger.open(openedAt, rules).copy(beers = beers, runs = runs)

fun beer(date: Instant): BeerEntry = BeerEntry(UUID.randomUUID(), date, date)

fun run(miles: Double, endedAt: Instant, workoutID: UUID = UUID.randomUUID()): RunEntry =
    RunEntry(UUID.randomUUID(), workoutID, endedAt.minusSeconds(30 * 60), endedAt, miles * RunEntry.METERS_PER_MILE, endedAt, "Test")

fun balance(state: BalanceState, debt: Double = 0.0, credit: Double = 0.0): Balance =
    Balance(state, debt, debt, 0.0, credit, credit)

fun close(a: Double, b: Double, tolerance: Double = 1e-6): Boolean = abs(a - b) <= tolerance
