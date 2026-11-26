package com.lttbdd.socialagent.feature.review

import com.lttbdd.socialagent.core.model.ChannelType
import java.text.Normalizer

/** Mức cảnh báo độ dài ký tự so với giới hạn của từng kênh. */
enum class CharacterLimitLevel {
    NORMAL,
    NEAR_LIMIT,
    EXCEEDED,
}

/**
 * Kết quả đếm ký tự theo giới hạn của kênh đích ([ChannelType.maxChars]).
 */
data class CharacterCountStatus(
    val currentLength: Int,
    val maxChars: Int,
    val remainingChars: Int,
    val level: CharacterLimitLevel,
) {
    val isOverLimit: Boolean get() = level == CharacterLimitLevel.EXCEEDED
    val excessChars: Int get() = if (remainingChars < 0) -remainingChars else 0
}

/**
 * Bộ đếm và kiểm tra giới hạn ký tự cho từng kênh mạng xã hội (F4, Issue #34).
 * Giới hạn được đọc trực tiếp từ [ChannelType.maxChars], không viết cứng.
 * Đếm theo Unicode code point sau khi chuẩn hóa NFC để tránh tính trùng emoji (surrogate pair)
 * và chữ có dấu tổ hợp (NFD) thành nhiều ký tự (ước lượng thực tế, có thể chênh lệch nhẹ tùy nền tảng).
 */
object DraftCharacterCounter {

    private const val NEAR_LIMIT_RATIO = 0.9

    fun countCharacters(text: String): Int {
        if (text.isEmpty()) return 0
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        return normalized.codePointCount(0, normalized.length)
    }

    /**
     * Cắt chuỗi theo số Unicode code point sau chuẩn hóa NFC để không cắt đôi emoji (surrogate pair)
     * hay dấu kết hợp khi mô phỏng điểm gập nội dung trên feed.
     */
    fun truncateByCodePoints(text: String, maxCodePoints: Int): String {
        if (maxCodePoints <= 0 || text.isEmpty()) return ""
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val total = normalized.codePointCount(0, normalized.length)
        if (total <= maxCodePoints) return normalized
        val endIndex = normalized.offsetByCodePoints(0, maxCodePoints)
        return normalized.substring(0, endIndex).trimEnd()
    }

    fun evaluate(text: String, channel: ChannelType): CharacterCountStatus {
        val current = countCharacters(text)
        val max = channel.maxChars
        val remaining = max - current
        val level = when {
            current > max -> CharacterLimitLevel.EXCEEDED
            current >= (max * NEAR_LIMIT_RATIO).toInt() -> CharacterLimitLevel.NEAR_LIMIT
            else -> CharacterLimitLevel.NORMAL
        }
        return CharacterCountStatus(
            currentLength = current,
            maxChars = max,
            remainingChars = remaining,
            level = level,
        )
    }

    fun canSave(text: String, channel: ChannelType): Boolean =
        text.isNotBlank() && !evaluate(text, channel).isOverLimit
}
