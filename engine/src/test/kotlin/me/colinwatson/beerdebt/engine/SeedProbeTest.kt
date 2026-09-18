package me.colinwatson.beerdebt.engine

import kotlinx.serialization.json.Json
import kotlin.test.Test

class SeedProbeTest {
    @Test
    fun `decode the phone seed`() {
        val text = javaClass.getResource("/seed-probe.json")!!.readText()
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        try {
            val l = json.decodeFromString<Ledger>(text)
            println("DECODED OK: beers=${l.beers.size} runs=${l.runs.size} freezes=${l.freezeApplications.size}")
        } catch (e: Exception) {
            println("DECODE FAILED: ${e::class.simpleName}: ${e.message}")
            throw e
        }
    }
}
