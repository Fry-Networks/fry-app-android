package com.frynetworks.fryapp.domain

import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssetsTest {

    @Test
    fun `fromId resolves every known asset`() {
        assertEquals(FryAsset.TFRY, FryAsset.fromId(2681521901L))
        assertEquals(FryAsset.FNODE, FryAsset.fromId(2485202024L))
        assertEquals(FryAsset.FRY2, FryAsset.fromId(2485314946L))
        assertEquals(FryAsset.FRY1, FryAsset.fromId(924268058L))
        assertEquals(FryAsset.FRY3, FryAsset.fromId(3612979527L))
        assertEquals(FryAsset.FVPN, FryAsset.fromId(2485198745L))
    }

    @Test
    fun `fromId returns null for an unknown asset id`() {
        assertNull(FryAsset.fromId(0L))
        assertNull(FryAsset.fromId(-1L))
    }

    @Test
    fun `every asset defaults to 6 decimals`() {
        FryAsset.entries.forEach { assertEquals(6, it.decimals) }
    }

    @Test
    fun `fromDisplay converts display units to micro units`() {
        assertEquals(12_340_000L, MicroAmount.fromDisplay(BigDecimal("12.34")).micro)
        assertEquals(1_000_000L, MicroAmount.fromDisplay(BigDecimal("1")).micro)
        assertEquals(0L, MicroAmount.fromDisplay(BigDecimal("0")).micro)
    }

    @Test
    fun `fromDisplay rounds half up beyond 6 decimals`() {
        // 0.0000005 -> 0.5 micro -> rounds up to 1
        assertEquals(1L, MicroAmount.fromDisplay(BigDecimal("0.0000005")).micro)
    }

    @Test
    fun `toDisplay converts micro units back to display units`() {
        assertEquals(BigDecimal("12.340000"), MicroAmount(12_340_000L).toDisplay())
        assertEquals(BigDecimal("0.000001"), MicroAmount(1L).toDisplay())
    }

    @Test
    fun `fromDisplay and toDisplay round trip`() {
        val amount = BigDecimal("42.5")
        val micro = MicroAmount.fromDisplay(amount)
        assertEquals(0, amount.compareTo(micro.toDisplay()))
    }

    @Test
    fun `format trims trailing zeros and applies grouping`() {
        assertEquals("12.34", MicroAmount(12_340_000L).format())
        assertEquals("1,234.5", MicroAmount(1_234_500_000L).format())
        assertEquals("1,000", MicroAmount(1_000_000_000L).format())
    }

    @Test
    fun `format truncates beyond maxFraction without rounding up`() {
        // 1.999999 with maxFraction=2 should truncate to 1.99, not round to 2.00
        assertEquals("1.99", MicroAmount(1_999_999L).format(maxFraction = 2))
    }

    @Test
    fun `format honors a custom maxFraction`() {
        assertEquals("12.3456", MicroAmount(12_345_600L).format(maxFraction = 4))
        assertEquals("12", MicroAmount(12_340_000L).format(maxFraction = 0))
    }

    @Test
    fun `format uses the given locale grouping and decimal separators`() {
        // German locale: '.' groups thousands, ',' is the decimal separator.
        assertEquals("1.234,5", MicroAmount(1_234_500_000L).format(locale = Locale.GERMANY))
    }

    @Test
    fun `fromDashboardAmount parses a nullable display-unit BigDecimal defaulting to zero`() {
        assertEquals(0L, MicroAmount.fromDashboardAmount(null).micro)
        assertEquals(12_340_000L, MicroAmount.fromDashboardAmount(BigDecimal("12.34")).micro)
    }

    @Test
    fun `MicroAmount is a value class wrapping a single Long`() {
        val amount = MicroAmount(500L)
        assertEquals(500L, amount.micro)
    }
}
