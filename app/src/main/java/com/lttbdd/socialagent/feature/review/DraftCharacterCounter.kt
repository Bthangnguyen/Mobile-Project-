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
 * Đếm theo Unicode code point sau khi chuẩn hóa NFC để emoji (surrogate pair) và chữ có dấu tổ hợp (NFD)
 * được tính là 1 ký tự khớp với cách mạng xã hội tính độ dài.
 */
object DraftCharacterCounter {

    private const val NEAR_LIMIT_RATIO = 0.9

    fun countCharacters(text: String): Int {
        if (text.isEmpty()) return 0
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        return normalized.codePointCount(0, normalized.length)
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
