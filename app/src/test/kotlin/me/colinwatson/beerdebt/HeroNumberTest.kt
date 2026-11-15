package me.colinwatson.beerdebt

import me.colinwatson.beerdebt.engine.PaymentBasis
import me.colinwatson.beerdebt.engine.PaymentMode
import me.colinwatson.beerdebt.ui.Units
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's hero and daily charge (iOS spec §29); same cases as iOS HeroNumberTests. */
class HeroNumberTest {
    private val miles = Units(PaymentBasis(PaymentMode.RUNS, 100.0))
    private val kcal = Units(PaymentBasis(PaymentMode.CALORIES, 100.0))

    @Test fun fiveFiguresStayInFull() {
        assertEquals("12.3", miles.hero(12.34))
        assertEquals("99999.9", miles.hero(99_999.9))
        assertEquals("97,000", kcal.hero(970.0))
    }

    @Test fun sixFiguresGoCompact() {
        assertEquals("174k", miles.hero(174_417.9))
        assertEquals("100k", kcal.hero(1_000.0))
        assertEquals("17M", kcal.hero(174_417.88))
    }

    @Test fun theDailyChargeGoesCompactAtFourFigures() {
        assertEquals("0.10", miles.charge(0.1))
        assertEquals("999.99", miles.charge(999.99))
        assertEquals("1.2k", miles.charge(1_234.0))
        assertEquals("18k", miles.charge(17_699.0))
        assertEquals("10", kcal.charge(0.1))
        assertEquals("1.8k", kcal.charge(17.7))
    }
}
