package com.lttbdd.socialagent.feature.review.hook

import com.lttbdd.socialagent.feature.review.DraftCharacterCounter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

/** Lý do một phương án câu mở đầu bị loại. */
enum class OpeningOptionRejection {
    BLANK,
    MULTI_LINE,
    MARKDOWN,
    TOO_LONG,
    SAME_AS_CURRENT,
    DUPLICATE,
}

/** Kết quả đọc câu trả lời của LLM. */
sealed interface OpeningParseResult {
    data class Success(val options: List<String>) : OpeningParseResult

    /** Không tìm thấy JSON đúng dạng `{"options": [...]}`. */
    data object MalformedResponse : OpeningParseResult

    /** JSON đúng dạng nhưng không đủ số phương án hợp lệ. */
    data class NotEnoughValidOptions(val validCount: Int) : OpeningParseResult
}

/**
 * Đọc và kiểm tra **xác định** (không gọi LLM) các phương án câu mở đầu do LLM trả về (Issue #63).
 *
 * Mỗi phương án phải: không rỗng, chỉ một dòng, không có Markdown, tối đa [MAX_OPTION_CHARS] ký tự
 * (đếm code point sau NFC như bộ đếm F4), không trùng nhau và không trùng câu mở đầu hiện tại
 * (so sánh sau khi gộp khoảng trắng, không phân biệt hoa thường). Không đủ [OPTION_COUNT] phương án hợp lệ
 * thì coi là lỗi, không trả kết quả thiếu.
 */
object OpeningSuggestionParser {

    const val OPTION_COUNT = 3
    const val MAX_OPTION_CHARS = 150

    private val json = Json { ignoreUnknownKeys = true }

    /** Facebook và LinkedIn không hiển thị Markdown: `**đậm**`, `__đậm__`, tiêu đề `# `, link `[a](b)`, mã `` ` ``. */
    private val markdownPatterns = listOf(
        Regex("""\*\*[^*]+\*\*"""),
        Regex("""__[^_]+__"""),
        Regex("""^ {0,3}#{1,6}\s+\S"""),
        Regex("""\[[^\]]+]\([^)\s]+\)"""),
        Regex("""`"""),
    )

    private val lineBreaks = charArrayOf('\n', '\r', ' ', ' ')
    private val whitespaceRun = Regex("""\s+""")

    fun parse(raw: String, currentOpening: String?): OpeningParseResult {
        val options = decodeOptions(raw) ?: return OpeningParseResult.MalformedResponse
        val currentKey = currentOpening?.takeIf { it.isNotBlank() }?.let(::comparisonKey)
        val accepted = mutableListOf<String>()
        val seenKeys = mutableSetOf<String>()
        for (option in options) {
            val candidate = Normalizer.normalize(option.trim(), Normalizer.Form.NFC)
            if (rejectionOf(candidate, currentKey, seenKeys) != null) continue
            accepted += candidate
            seenKeys += comparisonKey(candidate)
            if (accepted.size == OPTION_COUNT) break
        }
        return if (accepted.size == OPTION_COUNT) {
            OpeningParseResult.Success(accepted)
        } else {
            OpeningParseResult.NotEnoughValidOptions(accepted.size)
        }
    }

    /** Lý do loại [option] (đã trim), hoặc `null` khi hợp lệ. */
    fun rejectionOf(option: String, currentKey: String?, seenKeys: Set<String>): OpeningOptionRejection? {
        val key = comparisonKey(option)
        return when {
            option.isBlank() -> OpeningOptionRejection.BLANK
            option.indexOfAny(lineBreaks) >= 0 -> OpeningOptionRejection.MULTI_LINE
            markdownPatterns.any { it.containsMatchIn(option) } -> OpeningOptionRejection.MARKDOWN
            DraftCharacterCounter.countCharacters(option) > MAX_OPTION_CHARS -> OpeningOptionRejection.TOO_LONG
            key == currentKey -> OpeningOptionRejection.SAME_AS_CURRENT
            key in seenKeys -> OpeningOptionRejection.DUPLICATE
            else -> null
        }
    }

    /** Khoá so sánh: chuẩn hoá NFC, gộp khoảng trắng, bỏ hai đầu, chữ thường. */
    fun comparisonKey(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(whitespaceRun, " ")
            .trim()
            .lowercase(Locale.ROOT)

    /** LLM có thể bọc JSON trong khối ``` hoặc thêm lời dẫn: chỉ lấy đoạn từ `{` đầu tiên tới `}` cuối cùng. */
    private fun decodeOptions(raw: String): List<String>? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return try {
            json.decodeFromString<OpeningSuggestionPayload>(raw.substring(start, end + 1)).options
        } catch (e: IllegalArgumentException) {
            // SerializationException là lớp con của IllegalArgumentException.
            null
        }
    }
}

@Serializable
internal data class OpeningSuggestionPayload(val options: List<String>)
