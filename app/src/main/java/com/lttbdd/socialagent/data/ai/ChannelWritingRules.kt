package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChannelType
import java.text.Normalizer

/**
 * Quy tắc viết riêng của một kênh, dùng cho sub-agent (Issue #27).
 *
 * - [maxChars] là giới hạn cứng, lấy từ [ChannelType.maxChars] để khớp bộ đếm ở F4.
 * - [targetMaxChars] là độ dài nên nhắm tới, thấp hơn giới hạn cứng vì LLM đếm ký tự không chính xác.
 * - [styleGuide] là chỉ thị cho LLM (tiếng Anh, nằm trong prompt), KHÔNG phải chuỗi hiển thị cho người dùng.
 */
data class ChannelWritingRules(
    val channel: ChannelType,
    /** Tên kênh dùng trong prompt. */
    val promptName: String,
    val targetMaxChars: Int,
    val maxHashtags: Int,
    val styleGuide: List<String>,
) {
    val maxChars: Int get() = channel.maxChars

    init {
        require(targetMaxChars in 1..maxChars) { "targetMaxChars phải nằm trong 1..maxChars" }
        require(maxHashtags >= 0) { "maxHashtags không được âm" }
    }

    /**
     * Kiểm tra xác định (không gọi LLM) các quy tắc cứng của kênh; danh sách rỗng nghĩa là đạt.
     * Dùng ở bước Tự rà để không phụ thuộc hoàn toàn vào việc LLM tự nhận xét đúng.
     */
    fun check(text: String): List<ChannelRuleViolation> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        return buildList {
            val length = normalized.codePointCount(0, normalized.length)
            if (length > maxChars) add(ChannelRuleViolation.TooLong(length, maxChars))
            val hashtags = hashtagPattern.findAll(normalized).count()
            if (hashtags > maxHashtags) add(ChannelRuleViolation.TooManyHashtags(hashtags, maxHashtags))
            if (markdownPatterns.any { it.containsMatchIn(normalized) }) add(ChannelRuleViolation.MarkdownFormatting)
        }
    }

    companion object {
        /**
         * Hashtag phải đứng đầu từ và có ít nhất một chữ cái: không tính `#1`, `C#` hay `url#anchor`.
         * Đếm trên chuỗi NFC nên chữ tiếng Việt có dấu được nhận là chữ cái (`\p{L}`).
         */
        private val hashtagPattern = Regex("""(?<![\p{L}\p{N}_&/#])#(?=[\p{N}_]*\p{L})[\p{L}\p{M}\p{N}_]+""")

        /** Facebook và LinkedIn không hiển thị Markdown: `**đậm**`, tiêu đề `# `, link `[a](b)`, khối ``` sẽ lộ ký hiệu. */
        private val markdownPatterns = listOf(
            Regex("""\*\*[^*\n]+\*\*"""),
            Regex("""__[^_\n]+__"""),
            Regex("""(?m)^ {0,3}#{1,6}\s+\S"""),
            Regex("""\[[^\]\n]+]\([^)\s]+\)"""),
            Regex("""(?m)^ {0,3}```"""),
        )

        fun of(channel: ChannelType): ChannelWritingRules = when (channel) {
            ChannelType.FACEBOOK -> ChannelWritingRules(
                channel = channel,
                promptName = "Facebook Page",
                targetMaxChars = 1_500,
                maxHashtags = 3,
                styleGuide = listOf(
                    "Conversational and warm, as if the Page is talking to its community.",
                    "Open with a hook in the first sentence.",
                    "Use short paragraphs separated by blank lines; a few relevant emojis are fine.",
                    "End with a question or a clear call to action that invites comments.",
                ),
            )

            ChannelType.LINKEDIN -> ChannelWritingRules(
                channel = channel,
                promptName = "LinkedIn",
                targetMaxChars = 2_000,
                maxHashtags = 5,
                styleGuide = listOf(
                    "Professional, credible and insight-driven: share a lesson, an example or a practical takeaway.",
                    "Put the hook in the first two lines, because the feed collapses the rest of the post.",
                    "Use short paragraphs or line-separated points; use emojis sparingly or not at all.",
                    "End with a question or call to action; put hashtags, if any, on the last line.",
                ),
            )
        }
    }
}

/** Vi phạm quy tắc cứng của kênh, phát hiện bằng [ChannelWritingRules.check]. */
sealed interface ChannelRuleViolation {
    data class TooLong(val length: Int, val max: Int) : ChannelRuleViolation

    data class TooManyHashtags(val count: Int, val max: Int) : ChannelRuleViolation

    data object MarkdownFormatting : ChannelRuleViolation
}
