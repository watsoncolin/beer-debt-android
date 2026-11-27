package me.colinwatson.beerdebt

import me.colinwatson.beerdebt.engine.Balance
import me.colinwatson.beerdebt.engine.BalanceState
import me.colinwatson.beerdebt.ui.home.BankruptcyOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When Home offers Fresh Start (iOS spec §29); same cases as iOS BankruptcyOfferTests. */
class BankruptcyOfferTest {
    private fun owing(miles: Double) =
        Balance(BalanceState.DEBT, miles, minOf(miles, 5.0), maxOf(miles - 5, 0.0), miles * 0.1, 0.0, 0.0)

    @Test fun theOfferStartsAtAHundredMiles() {
        assertEquals(100.0, BankruptcyOffer.THRESHOLD_MILES, 0.0)
        assertNull(BankruptcyOffer.tier(owing(99.9)))
        assertEquals(0, BankruptcyOffer.tier(owing(100.0)))
    }

    @Test fun eachTierIsTenTimesTheLast() {
        assertEquals(0, BankruptcyOffer.tier(owing(999.9)))
        assertEquals(1, BankruptcyOffer.tier(owing(1_000.0)))
        assertEquals(1, BankruptcyOffer.tier(owing(9_999.0)))
        assertEquals(2, BankruptcyOffer.tier(owing(10_000.0)))
        assertEquals(3, BankruptcyOffer.tier(owing(174_417.9)))
    }

    @Test fun creditAndCleanBooksAreNeverOffered() {
        assertNull(BankruptcyOffer.tier(Balance.EVEN))
        assertNull(BankruptcyOffer.tier(Balance(BalanceState.CREDIT, 0.0, 0.0, 0.0, 0.0, 300.0, 3.0)))
    }

    @Test fun notNowHoldsUntilTheNextTier() {
        assertTrue(BankruptcyOffer.shows(0, null))
        assertFalse(BankruptcyOffer.shows(0, 0))
        assertTrue(BankruptcyOffer.shows(1, 0))
        assertFalse(BankruptcyOffer.shows(1, 1))
        assertFalse(BankruptcyOffer.shows(null, null))
    }
}
