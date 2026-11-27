package com.lttbdd.socialagent.data.ai

import java.text.Normalizer

/**
 * Gỡ phần "vỏ" mà LLM hay thêm quanh kết quả của một bước viết (Issue #27), dù prompt đã dặn chỉ trả nội dung:
 * - Khối ``` bọc TOÀN BỘ kết quả (khối code nằm giữa bài thì giữ nguyên).
 * - Một dòng dẫn đầu chỉ để giới thiệu kết quả, ví dụ "Here is the final post:" hay
 *   "Dưới đây là bài viết hoàn chỉnh:".
 *
 * Mẫu câu dẫn được giữ hẹp có chủ đích: dòng phải là câu giới thiệu hoàn chỉnh, gồm cụm mở đầu, danh từ chỉ
 * kết quả (post, draft, bài viết…) và kết thúc bằng dấu hai chấm. Nhờ vậy câu mở đầu thật của bài như
 * "Here's what I learned:" hay "3 bài học từ dự án đầu tiên:" không bị xoá nhầm. Không gỡ được thì giữ nguyên
 * văn bản để bước Tự rà và [ChannelWritingRules.check] xử lý như trước.
 */
internal object LlmOutputCleaner {

    fun clean(raw: String): String {
        val unwrapped = unwrapFence(raw.trim())
        return unwrapFence(dropPreamble(unwrapped))
    }

    private fun unwrapFence(text: String): String {
        val match = wholeFence.matchEntire(text) ?: return text
        val inner = match.groupValues[1]
        // Có thêm dòng ``` bên trong nghĩa là nhiều khối riêng, không phải một khối bọc ngoài.
        if (fenceLine.containsMatchIn(inner)) return text
        // Khối rỗng thì trả chuỗi rỗng để bước gọi báo EMPTY_RESPONSE như khi LLM không trả gì.
        return inner.trim()
    }

    private fun dropPreamble(text: String): String {
        val firstLine = text.substringBefore('\n')
        val rest = text.substringAfter('\n', missingDelimiterValue = "").trim()
        if (rest.isEmpty()) return text
        val normalized = Normalizer.normalize(firstLine.trim(), Normalizer.Form.NFC)
        return if (preambles.any { it.matches(normalized) }) rest else text
    }

    private val wholeFence = Regex("""```[\w-]*[ \t]*\r?\n(.*?)\r?\n[ \t]*```""", RegexOption.DOT_MATCHES_ALL)

    private val fenceLine = Regex("""(?m)^[ \t]*```""")

    private val preambles = listOf(
        // "Here is the final post:", "Sure! Here's the revised LinkedIn post:", "Here's a draft for the Page:".
        Regex(
            """(?:(?:sure|certainly|of course|okay|ok|great)[,!.]*\s+)?""" +
                """(?:here(?:['’]s|\s+is|\s+are)|below\s+is)\s+(?:the|your|a|an|my)\s+""" +
                """(?:[\p{L}-]+,?\s+){0,3}?""" +
                """(?:post|version|draft|outline|description|caption|copy)""" +
                """(?:\s+(?:for|on)\s+[^:\n]{1,40})?\s*:""",
            RegexOption.IGNORE_CASE,
        ),
        // "Dưới đây là bài viết hoàn chỉnh:", "Đây là bản nháp đã chỉnh sửa cho LinkedIn:".
        Regex(
            """(?:(?:vâng|dạ|chắc chắn rồi|được rồi|ok)[,!.]*\s+)?""" +
                """(?:dưới đây là|sau đây là|đây là)\s+""" +
                """(?:bài viết|bài đăng|bản nháp|phiên bản|dàn ý|mô tả(?:\s+ảnh)?)""" +
                """(?:\s+(?:hoàn chỉnh|cuối cùng|mới|đã được chỉnh sửa|đã chỉnh sửa|đã sửa|đã rà soát|""" +
                """sau khi (?:chỉnh sửa|sửa|rà soát)|cho (?:trang\s+)?(?:facebook|linkedin)(?:\s+page)?|""" +
                """của bạn|bằng tiếng (?:việt|anh)))*\s*:""",
            RegexOption.IGNORE_CASE,
        ),
    )
}
