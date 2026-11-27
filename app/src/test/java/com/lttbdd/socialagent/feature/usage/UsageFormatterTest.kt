package com.lttbdd.socialagent.feature.usage

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageFormatterTest {
    private val english = UsageFormatter(Locale.US)
    private val vietnamese = UsageFormatter(Locale.forLanguageTag("vi-VN"))

    @Test
    fun `token counts use locale grouping`() {
        assertEquals("1,234,567", english.count(1_234_567))
        assertEquals("1.234.567", vietnamese.count(1_234_567))
    }

    @Test
    fun `small costs keep enough decimals to be visible`() {
        assertEquals("$0.00035", english.usd(350))
        assertEquals("$0.000001", english.usd(1))
        assertEquals("$0.00", english.usd(0))
        assertEquals("$1,234.50", english.usd(1_234_500_000))
    }

    @Test
    fun `Vietnamese cost uses comma decimals and stays in USD`() {
        val text = vietnamese.usd(350)
        assertTrue(text, text.contains("0,00035"))
        assertTrue(text, text.contains("US$") || text.contains("$"))
    }

    @Test
    fun `month label is numeric and locale independent`() {
        assertEquals("11/2026", english.month(YearMonth.of(2026, 11)))
        assertEquals("11/2026", vietnamese.month(YearMonth.of(2026, 11)))
    }

    @Test
    fun `day label is localized`() {
        assertTrue(english.day(LocalDate.of(2026, 11, 27)).contains("Nov"))
        assertTrue(vietnamese.day(LocalDate.of(2026, 11, 27)).contains("27"))
    }
}
