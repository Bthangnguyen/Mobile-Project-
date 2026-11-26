package com.lttbdd.socialagent.data.review

import java.util.Locale

/**
 * Bộ phân tích cảnh báo ngoại tuyến trước khi đăng bài (F4, Issue #65):
 * 1. Số liệu/thống kê chưa có nguồn trích dẫn ([PrePublishWarningType.UNSOURCED_STATISTIC]).
 * 2. Thông tin cá nhân nhạy cảm ([PrePublishWarningType.PERSONAL_INFO]) — luôn che mờ đoạn khớp.
 * 3. Lời lẽ nhạy cảm, công kích hoặc cam kết tuyệt đối ([PrePublishWarningType.SENSITIVE_LANGUAGE]).
 *
 * Thuần Kotlin, không gọi mạng/LLM, trả về tối đa 1 cảnh báo đại diện cho mỗi nhóm và sắp xếp
 * cảnh báo mức [WarningSeverity.HIGH] lên trước.
 */
object PrePublishWarningAnalyzer {

    private val EMAIL_REGEX = Regex(
        """[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""",
    )

    private val VN_PHONE_REGEX = Regex(
        """(?<!\d)(?:\+84|0)(?:3|5|7|8|9)(?:[\s.-]?\d){8}(?!\d)""",
    )

    private val INTL_PHONE_REGEX = Regex(
        """(?<!\d)\+\d{1,3}[\s.-]?\(?\d{2,4}\)?[\s.-]?\d{3,4}[\s.-]?\d{3,4}(?!\d)""",
    )

    private val CARD_NUMBER_REGEX = Regex(
        """(?<!\d)(?:\d{4}[\s-]?){3}\d{3,4}(?!\d)""",
    )

    private val ID_CONTEXT_REGEX = Regex(
        """(?i)\b(?:cccd|cmnd|căn cước|hộ chiếu|passport|ssn|stk|số tài khoản|bank account|iban)\b[^\n\r\d]{0,16}(\d{6,16})""",
    )

    private val STATISTIC_REGEX = Regex(
        """(?i)\b\d+(?:[.,]\d+)?\s*(?:%|(?:phần trăm|percent|triệu|tỷ|tỉ|nghìn|million|billion|thousand)(?!\p{L}))""",
    )

    private val SOURCE_CUE_REGEX = Regex(
        """(?i)(?:https?://\S+|(?<!\p{L})(?:nguồn|theo|báo cáo|khảo sát của|nghiên cứu của|thống kê của|source|according to|report by|study by|survey by|via)(?!\p{L}))""",
    )

    private val SENSITIVE_PATTERNS: List<Regex> = listOf(
        // Công kích, xúc phạm, thù ghét (VI & EN)
        Regex("""(?i)(?<!\p{L})(?:đồ ngu|ngu ngốc|óc chó|khốn nạn|mất dạy|kỳ thị|phân biệt chủng tộc|đánh chết|giết chết)(?!\p{L})"""),
        Regex("""(?i)\b(?:idiot|moron|stupid|racist|sexist|hate speech|kill yourself|scammer)\b"""),
        // Tuyên bố tuyệt đối / gây hiểu lầm dễ bị nền tảng gắn cờ
        Regex("""(?i)(?<!\p{L})(?:chữa khỏi 100%|cam kết 100%|làm giàu nhanh|không bao giờ thua|đảm bảo thắng 100%)(?!\p{L})"""),
        Regex("""(?i)\b(?:guaranteed 100%|100% cure|get rich quick|zero risk guaranteed)\b"""),
    )

    /** Phân tích nội dung [text] và trả về danh sách cảnh báo đã chuẩn hóa (không trùng loại). */
    fun analyze(text: String): List<PrePublishWarning> {
        if (text.isBlank()) return emptyList()

        val warnings = mutableListOf<PrePublishWarning>()

        detectPersonalInfo(text)?.let { masked ->
            warnings += PrePublishWarning(
                type = PrePublishWarningType.PERSONAL_INFO,
                severity = WarningSeverity.HIGH,
                matchedSnippet = masked,
            )
        }

        detectSensitiveLanguage(text)?.let { phrase ->
            warnings += PrePublishWarning(
                type = PrePublishWarningType.SENSITIVE_LANGUAGE,
                severity = WarningSeverity.HIGH,
                matchedSnippet = phrase,
            )
        }

        detectUnsourcedStatistic(text)?.let { stat ->
            warnings += PrePublishWarning(
                type = PrePublishWarningType.UNSOURCED_STATISTIC,
                severity = WarningSeverity.MEDIUM,
                matchedSnippet = stat,
            )
        }

        return warnings.sortedByDescending { it.severity == WarningSeverity.HIGH }
    }

    private fun detectPersonalInfo(text: String): String? {
        EMAIL_REGEX.find(text)?.let { match ->
            return maskEmail(match.value)
        }
        VN_PHONE_REGEX.find(text)?.let { match ->
            return maskDigits(match.value)
        }
        INTL_PHONE_REGEX.find(text)?.let { match ->
            return maskDigits(match.value)
        }
        CARD_NUMBER_REGEX.find(text)?.let { match ->
            return maskDigits(match.value)
        }
        ID_CONTEXT_REGEX.find(text)?.let { match ->
            val digits = match.groupValues.getOrNull(1).orEmpty()
            return maskDigits(digits)
        }
        return null
    }

    private fun detectSensitiveLanguage(text: String): String? {
        for (pattern in SENSITIVE_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                return match.value.trim().lowercase(Locale.ROOT)
            }
        }
        return null
    }

    private fun detectUnsourcedStatistic(text: String): String? {
        val statMatch = STATISTIC_REGEX.find(text) ?: return null
        if (SOURCE_CUE_REGEX.containsMatchIn(text)) return null
        return statMatch.value.trim()
    }

    private fun maskEmail(email: String): String {
        val parts = email.split("@", limit = 2)
        if (parts.size != 2) return "***"
        val local = parts[0]
        val visible = local.take(2)
        return "$visible***@${parts[1]}"
    }

    private fun maskDigits(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.length <= 4) return "****"
        return "***${digits.takeLast(4)}"
    }
}
