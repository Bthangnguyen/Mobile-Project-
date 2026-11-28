package com.lttbdd.socialagent.data.review

import java.util.Locale

/**
 * Bộ phân tích cảnh báo ngoại tuyến trước khi đăng bài (F4, Issue #65):
 * 1. Số liệu/thống kê chưa có nguồn trích dẫn trong cùng câu ([PrePublishWarningType.UNSOURCED_STATISTIC]).
 * 2. Thông tin cá nhân nhạy cảm ([PrePublishWarningType.PERSONAL_INFO]) — luôn che mờ đoạn khớp;
 *    số thẻ được kiểm tra thêm bằng thuật toán Luhn để tránh nhận nhầm dãy năm/mã số.
 * 3. Lời lẽ nhạy cảm, công kích hoặc cam kết tuyệt đối ([PrePublishWarningType.SENSITIVE_LANGUAGE]).
 *
 * Thuần Kotlin, không gọi mạng/LLM, dùng cờ `(?iu)` để nhận diện nhất quán chữ hoa tiếng Việt
 * trên cả JVM và Android, trả về tối đa 1 cảnh báo đại diện cho mỗi nhóm và sắp xếp mức
 * [WarningSeverity.HIGH] lên trước.
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

    /** Khớp dãy 13–19 chữ số có thể ngăn cách bởi khoảng trắng hoặc dấu gạch ngang; sẽ kiểm tra thêm Luhn. */
    private val CARD_NUMBER_REGEX = Regex(
        """(?<!\d)(?:\d[\s-]?){13,19}(?!\d)""",
    )

    private val ID_CONTEXT_REGEX = Regex(
        """(?iu)(?<!\p{L})(?:cccd|cmnd|căn cước|hộ chiếu|passport|ssn|stk|số tài khoản|bank account|iban)(?!\p{L})[^\n\r\d]{0,16}(\d{6,16})""",
    )

    /**
     * Khớp số liệu phần trăm hoặc quy mô lớn; bỏ qua đơn vị tiền tệ ngay sau (`triệu đồng`, `tỷ vnđ`, ...)
     * để hạn chế báo nhầm cho giá sản phẩm.
     */
    private val STATISTIC_REGEX = Regex(
        """(?iu)\b\d+(?:[.,]\d+)?\s*(?:%|(?:phần trăm|percent|triệu|tỷ|tỉ|nghìn|million|billion|thousand)(?!\p{L})(?!\s*(?:đồng|vnđ|vnd|usd|\$|đ(?!\p{L}))))""",
    )

    /**
     * Chỉ nhận các cụm trích dẫn nguồn rõ ràng hoặc URL trong cùng câu chứa số liệu
     * (không nhận từ "theo" đứng một mình để tránh tắt cảnh báo bởi "theo dõi", "tiếp theo"...).
     */
    private val SOURCE_CUE_REGEX = Regex(
        """(?iu)(?:https?://\S+|(?<!\p{L})(?:nguồn|theo\s+(?:báo cáo|số liệu|khảo sát|thống kê|nghiên cứu)|báo cáo\s+của|khảo sát\s+của|nghiên cứu\s+của|thống kê\s+của|source|according to|report by|study by|survey by|via)(?!\p{L}))""",
    )

    private val SENTENCE_SPLIT_REGEX = Regex("""(?<=[.!?])\s+|\r?\n+""")

    private val SENSITIVE_PATTERNS: List<Regex> = listOf(
        // Công kích, xúc phạm, thù ghét (VI & EN)
        Regex("""(?iu)(?<!\p{L})(?:đồ ngu|ngu ngốc|óc chó|khốn nạn|mất dạy|kỳ thị|phân biệt chủng tộc|đánh chết|giết chết)(?!\p{L})"""),
        Regex("""(?iu)(?<!\p{L})(?:idiot|moron|stupid|racist|sexist|hate speech|kill yourself|scammer)(?!\p{L})"""),
        // Tuyên bố tuyệt đối / gây hiểu lầm dễ bị nền tảng gắn cờ (không dùng \b sau ký tự %)
        Regex("""(?iu)(?<!\p{L})(?:chữa khỏi 100%|cam kết 100%|làm giàu nhanh|không bao giờ thua|đảm bảo thắng 100%)(?!\p{L})(?!\d)"""),
        Regex("""(?iu)(?<!\p{L})(?:guaranteed 100%|100% cure|get rich quick|zero risk guaranteed)(?!\p{L})(?!\d)"""),
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
        for (match in CARD_NUMBER_REGEX.findAll(text)) {
            val digits = match.value.filter { it.isDigit() }
            if (digits.length in 13..19 && passesLuhn(digits)) {
                return maskDigits(digits)
            }
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
        for (sentence in text.split(SENTENCE_SPLIT_REGEX)) {
            if (sentence.isBlank()) continue
            val statMatch = STATISTIC_REGEX.find(sentence) ?: continue
            if (!SOURCE_CUE_REGEX.containsMatchIn(sentence)) {
                return statMatch.value.trim()
            }
        }
        return null
    }

    private fun passesLuhn(digits: String): Boolean {
        var sum = 0
        var doubleNext = false
        for (i in digits.length - 1 downTo 0) {
            var digit = digits[i] - '0'
            if (doubleNext) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            doubleNext = !doubleNext
        }
        return sum % 10 == 0
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
