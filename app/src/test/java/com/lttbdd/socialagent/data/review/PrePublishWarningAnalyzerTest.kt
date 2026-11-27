package com.lttbdd.socialagent.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrePublishWarningAnalyzerTest {

    @Test
    fun `clean post and prices or year sequences produce no warnings`() {
        val clean = "Hôm nay nhóm chia sẻ kinh nghiệm xây dựng ứng dụng Android offline-first bằng Room và WorkManager."
        assertTrue(PrePublishWarningAnalyzer.analyze(clean).isEmpty())
        assertTrue(PrePublishWarningAnalyzer.analyze("   ").isEmpty())

        // Chuỗi các năm liên tiếp không bị nhận nhầm là số thẻ (nhờ kiểm tra Luhn)
        val years = "Từ các năm 2021 2022 2023 2024 chúng tôi lớn mạnh."
        assertTrue(PrePublishWarningAnalyzer.analyze(years).isEmpty())

        // Giá sản phẩm không bị nhận nhầm là số liệu chưa nguồn
        val price = "Giá ưu đãi chỉ 5 triệu đồng cho học viên đăng ký sớm."
        assertTrue(PrePublishWarningAnalyzer.analyze(price).isEmpty())
    }

    @Test
    fun `detects unsourced statistics even with standalone theo or unrelated URL in another sentence`() {
        val unsourced = "Doanh thu quý này tăng 45% so với cùng kỳ năm ngoái."
        val warnings = PrePublishWarningAnalyzer.analyze(unsourced)
        assertEquals(1, warnings.size)
        assertEquals(PrePublishWarningType.UNSOURCED_STATISTIC, warnings.single().type)
        assertEquals(WarningSeverity.MEDIUM, warnings.single().severity)
        assertEquals("45%", warnings.single().matchedSnippet)

        // "Theo dõi" và "tiếp theo" KHÔNG được coi là nguồn trích dẫn
        val withTheoDoi = "Doanh thu tăng 60%. Theo dõi trang để cập nhật."
        assertEquals(
            listOf(PrePublishWarningType.UNSOURCED_STATISTIC),
            PrePublishWarningAnalyzer.analyze(withTheoDoi).map { it.type },
        )
        val withTiepTheo = "Tiếp theo, doanh thu tăng 60% nhờ làm theo cách này."
        assertEquals(
            listOf(PrePublishWarningType.UNSOURCED_STATISTIC),
            PrePublishWarningAnalyzer.analyze(withTiepTheo).map { it.type },
        )

        // Link khuyến mãi ở câu khác không tắt cảnh báo cho số liệu ở câu trước
        val unrelatedUrl = "Tăng 60% doanh thu. Đăng ký tại https://shop.vn/promo"
        assertEquals(
            listOf(PrePublishWarningType.UNSOURCED_STATISTIC),
            PrePublishWarningAnalyzer.analyze(unrelatedUrl).map { it.type },
        )

        // Nguồn rõ ràng hoặc URL trong cùng câu thì không cảnh báo
        val withVietnameseSource = "Theo báo cáo nội bộ Q3, doanh thu tăng 45% so với cùng kỳ."
        assertTrue(PrePublishWarningAnalyzer.analyze(withVietnameseSource).isEmpty())

        val withUrlSameSentence = "Revenue grew 45.5% year-over-year (https://example.com/report)."
        assertTrue(PrePublishWarningAnalyzer.analyze(withUrlSameSentence).isEmpty())
    }

    @Test
    fun `detects personal info including Luhn-valid cards and masks matched snippet`() {
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

        val cardWarnings = PrePublishWarningAnalyzer.analyze("Số thẻ thanh toán 4532 0151 1283 0366.")
        assertEquals(PrePublishWarningType.PERSONAL_INFO, cardWarnings.single().type)
        assertEquals("***0366", cardWarnings.single().matchedSnippet)
    }

    @Test
    fun `detects sensitive language including guaranteed 100 percent and uppercase Vietnamese`() {
        val viWarnings = PrePublishWarningAnalyzer.analyze("Sản phẩm này chữa khỏi 100% chỉ sau một tuần.")
        assertTrue(viWarnings.any { it.type == PrePublishWarningType.SENSITIVE_LANGUAGE })

        val enGuaranteed1 = PrePublishWarningAnalyzer.analyze("We offer guaranteed 100% results for everyone.")
        assertTrue(enGuaranteed1.any { it.type == PrePublishWarningType.SENSITIVE_LANGUAGE })

        val enGuaranteed2 = PrePublishWarningAnalyzer.analyze("This is guaranteed 100%. Buy now!")
        assertTrue(enGuaranteed2.any { it.type == PrePublishWarningType.SENSITIVE_LANGUAGE })

        val enIdiot = PrePublishWarningAnalyzer.analyze("Don't listen to that idiot on the internet.")
        assertEquals(1, enIdiot.size)
        assertEquals(PrePublishWarningType.SENSITIVE_LANGUAGE, enIdiot.single().type)
        assertEquals(WarningSeverity.HIGH, enIdiot.single().severity)

        // Chữ hoa tiếng Việt hoạt động nhất quán trên JVM (cờ (?iu))
        assertTrue(
            PrePublishWarningAnalyzer.analyze("ĐỒ NGU không biết gì!")
                .any { it.type == PrePublishWarningType.SENSITIVE_LANGUAGE },
        )
        assertTrue(
            PrePublishWarningAnalyzer.analyze("KHỐN NẠN thật sự.")
                .any { it.type == PrePublishWarningType.SENSITIVE_LANGUAGE },
        )
        assertTrue(
            PrePublishWarningAnalyzer.analyze("Tăng 60 PHẦN TRĂM tương tác.")
                .any { it.type == PrePublishWarningType.UNSOURCED_STATISTIC },
        )
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
