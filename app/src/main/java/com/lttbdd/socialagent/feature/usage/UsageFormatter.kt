package com.lttbdd.socialagent.feature.usage

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/**
 * Định dạng số token, tiền USD và ngày/tháng theo ngôn ngữ đang dùng (VI/EN).
 * Không an toàn đa luồng (NumberFormat); chỉ dùng trên main thread của Fragment.
 */
class UsageFormatter(locale: Locale) {
    private val integers = NumberFormat.getIntegerInstance(locale)

    /** Chi phí một lượt gọi thường rất nhỏ (vài phần nghìn USD) nên cho tới 6 chữ số thập phân. */
    private val usd = NumberFormat.getCurrencyInstance(locale).apply {
        currency = Currency.getInstance("USD")
        minimumFractionDigits = 2
        maximumFractionDigits = MICROS_SCALE
    }
    private val days = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val months = DateTimeFormatter.ofPattern("MM/yyyy", locale)

    fun count(value: Long): String = integers.format(value)

    fun usd(micros: Long): String = usd.format(BigDecimal.valueOf(micros, MICROS_SCALE))

    fun day(date: LocalDate): String = days.format(date)

    fun month(month: YearMonth): String = months.format(month)

    private companion object {
        const val MICROS_SCALE = 6
    }
}
