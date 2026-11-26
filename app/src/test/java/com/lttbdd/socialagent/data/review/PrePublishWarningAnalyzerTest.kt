package com.lttbdd.socialagent.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrePublishWarningAnalyzerTest {

    @Test
    fun `clean post produces no warnings`() {
        val text = "Hôm nay nhóm chia sẻ kinh nghiệm xây dựng ứng dụng Android offline-first bằng Room và WorkManager."
        assertTrue(PrePublishWarningAnalyzer.analyze(text).isEmpty())
        assertTrue(PrePublishWarningAnalyzer.analyze("   ").isEmpty())
    }

    @Test
    fun `detects unsourced statistics and clears warning when source or url is provided`() {
        val unsourced = "Doanh thu quý này tăng 45% so với cùng kỳ năm ngoái."
        val warnings = PrePublishWarningAnalyzer.analyze(unsourced)
        assertEquals(1, warnings.size)
        assertEquals(PrePublishWarningType.UNSOURCED_STATISTIC, warnings.single().type)
        assertEquals(WarningSeverity.MEDIUM, warnings.single().severity)
        assertEquals("45%", warnings.single().matchedSnippet)

        val withVietnameseSource = "Theo báo cáo nội bộ Q3, doanh thu tăng 45% so với cùng kỳ."
        assertTrue(PrePublishWarningAnalyzer.analyze(withVietnameseSource).isEmpty())

        val withUrl = "Revenue grew 45% year-over-year (https://example.com/report)."
        assertTrue(PrePublishWarningAnalyzer.analyze(withUrl).isEmpty())
    }

    @Test
    fun `detects personal info and masks matched snippet without exposing raw PII`() {
        val emailWarnings = PrePublishWarningAnalyzer.analyze("Liên hệ mình qua nguyenvana@company.com nhé.")
        assertEquals(1, emailWarnings.size)
        assertEquals(PrePublishWarningType.PERSONAL_INFO, emailWarnings.single().type)
        assertEquals(WarningSeverity.HIGH, emailWarnings.single().severity)
        assertFalse(emailWarnings.single().matchedSnippet.contains("nguyenvana"))
        assertEquals("ng***@company.com", emailWarnings.single().matchedSnippet)

        val phoneWarnings = PrePublishWarningAnalyzer.analyze("Gọi trực tiếp số 0912345678 để nhận tư vấn.")
        assertEquals(PrePublishWarningType.PERSONAL_INFO, phoneWarnings.single().type)
        assertFalse(phoneWarnings.single().matchedSnippet.contains("091234"))
        assertEquals("***5678", phoneWarnings.single().matchedSnippet)

        val idWarnings = PrePublishWarningAnalyzer.analyze("Số CCCD: 079123456789 của khách hàng.")
        assertEquals(PrePublishWarningType.PERSONAL_INFO, idWarnings.single().type)
        assertFalse(idWarnings.single().matchedSnippet.contains("079123"))
        assertEquals("***6789", idWarnings.single().matchedSnippet)
    }

    @Test
    fun `detects sensitive language in Vietnamese and English`() {
        val viWarnings = PrePublishWarningAnalyzer.analyze("Sản phẩm này chữa khỏi 100% chỉ sau một tuần.")
        assertTrue(viWarnings.any { it.type == PrePublishWarningType.SENSITIVE_LANGUAGE })

        val enWarnings = PrePublishWarningAnalyzer.analyze("Don't listen to that idiot on the internet.")
        assertEquals(1, enWarnings.size)
        assertEquals(PrePublishWarningType.SENSITIVE_LANGUAGE, enWarnings.single().type)
        assertEquals(WarningSeverity.HIGH, enWarnings.single().severity)
    }

    @Test
    fun `multiple categories are deduplicated and ordered with HIGH severity first`() {
        val riskyText = "Tăng 80% doanh số, cam kết 100% thành công! Nhắn ngay 0987654321."
        val warnings = PrePublishWarningAnalyzer.analyze(riskyText)

        assertEquals(3, warnings.size)
        assertEquals(
            listOf(
                PrePublishWarningType.PERSONAL_INFO,
                PrePublishWarningType.SENSITIVE_LANGUAGE,
                PrePublishWarningType.UNSOURCED_STATISTIC,
            ),
            warnings.map { it.type },
        )
    }
}
