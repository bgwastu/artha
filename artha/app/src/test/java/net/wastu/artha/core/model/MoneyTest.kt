package net.wastu.artha.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun wholeRupiahIsConvertedExactlyToMinorUnits() {
        assertEquals(Money(12_345_600), Money.fromRupiah("123456"))
        assertEquals(Money(100), Money.fromRupiah("1"))
    }

    @Test
    fun invalidAndOverflowingInputIsRejected() {
        listOf("", "0", "-1", "12.5", "1,000", " 12", "92233720368547759", "9999999999999999999999")
            .forEach { assertNull("Should reject $it", Money.fromRupiah(it)) }
        assertEquals(Money(9_223_372_036_854_775_800), Money.fromRupiah("92233720368547758"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun directionMustCarryTheSign() {
        Money(-100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownCurrencyIsRejected() {
        Money(100, "BAD")
    }
}
