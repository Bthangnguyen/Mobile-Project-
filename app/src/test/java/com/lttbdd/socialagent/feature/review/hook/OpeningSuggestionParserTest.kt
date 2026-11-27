package com.lttbdd.socialagent.feature.review.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.Normalizer

class OpeningSuggestionParserTest {

    private fun json(vararg options: String): String =
        options.joinToString(prefix = """{"options":[""", postfix = "]}") { "\"$it\"" }

    private fun reject(option: String, current: String? = null, seen: Set<String> = emptySet()) =
        OpeningSuggestionParser.rejectionOf(
            option = option,
            currentKey = current?.let(OpeningSuggestionParser::comparisonKey),
            seenKeys = seen.map(OpeningSuggestionParser::comparisonKey).toSet(),
        )

    @Test
    fun `valid JSON returns exactly three trimmed options`() {
        val result = OpeningSuggestionParser.parse(json(" Một? ", "Hai!", "Ba."), currentOpening = "Cũ")

        assertEquals(OpeningParseResult.Success(listOf("Một?", "Hai!", "Ba.")), result)
    }

    @Test
    fun `JSON wrapped in a code fence or prose is still read`() {
        val raw = "Đây là kết quả:\n```json\n${json("Một", "Hai", "Ba")}\n```"

        assertEquals(
            OpeningParseResult.Success(listOf("Một", "Hai", "Ba")),
            OpeningSuggestionParser.parse(raw, currentOpening = null),
        )
    }

    @Test
    fun `extra valid options are cut to the first three`() {
        val result = OpeningSuggestionParser.parse(json("Một", "Hai", "Ba", "Bốn"), currentOpening = null)

        assertEquals(OpeningParseResult.Success(listOf("Một", "Hai", "Ba")), result)
    }

    @Test
    fun `invalid options are skipped and later valid ones fill the list`() {
        val result = OpeningSuggestionParser.parse(
            json("Một", "", "**Đậm**", "một", "Hai", "Ba"),
            currentOpening = null,
        )

        assertEquals(OpeningParseResult.Success(listOf("Một", "Hai", "Ba")), result)
    }

    @Test
    fun `fewer than three valid options is an error, not a partial result`() {
        val result = OpeningSuggestionParser.parse(json("Một", "Hai", "Câu cũ"), currentOpening = "câu  cũ")

        assertEquals(OpeningParseResult.NotEnoughValidOptions(validCount = 2), result)
    }

    @Test
    fun `malformed responses are reported as malformed`() {
        listOf(
            "",
            "Không có JSON",
            """{"options": ["Một", "Hai"""",
            """{"options": "Một"}""",
            """{"options": [1, 2, 3]}""",
            """{"other": ["Một", "Hai", "Ba"]}""",
        ).forEach { raw ->
            assertEquals(raw, OpeningParseResult.MalformedResponse, OpeningSuggestionParser.parse(raw, null))
        }
    }

    @Test
    fun `blank and multi-line options are rejected`() {
        assertEquals(OpeningOptionRejection.BLANK, reject(""))
        assertEquals(OpeningOptionRejection.MULTI_LINE, reject("Dòng một\nDòng hai"))
        assertEquals(OpeningOptionRejection.MULTI_LINE, reject("Dòng một\r\nDòng hai"))
        assertEquals(OpeningOptionRejection.MULTI_LINE, reject("Dòng một Dòng hai"))
    }

    @Test
    fun `markdown is rejected but hashtags and plain punctuation are not`() {
        assertEquals(OpeningOptionRejection.MARKDOWN, reject("Đây là **điểm chính**"))
        assertEquals(OpeningOptionRejection.MARKDOWN, reject("Đây là __điểm chính__"))
        assertEquals(OpeningOptionRejection.MARKDOWN, reject("# Tiêu đề"))
        assertEquals(OpeningOptionRejection.MARKDOWN, reject("Xem [bài gốc](https://example.com)"))
        assertEquals(OpeningOptionRejection.MARKDOWN, reject("Dùng `code` ở đây"))
        assertNull(reject("#AI đang thay đổi cách viết bài?"))
        assertNull(reject("3 * 4 = 12, còn bạn?"))
    }

    @Test
    fun `length limit counts NFC code points`() {
        val max = OpeningSuggestionParser.MAX_OPTION_CHARS
        assertNull(reject("a".repeat(max)))
        assertEquals(OpeningOptionRejection.TOO_LONG, reject("a".repeat(max + 1)))
        // Emoji là 2 char UTF-16 nhưng 1 code point.
        assertNull(reject("😀".repeat(max)))
        // Chữ có dấu dạng NFD được đếm như NFC.
        val nfd = Normalizer.normalize("ệ", Normalizer.Form.NFD).repeat(max)
        assertNull(reject(nfd))
    }

    @Test
    fun `options equal to the current opening or to each other are rejected ignoring case and spacing`() {
        assertEquals(OpeningOptionRejection.SAME_AS_CURRENT, reject("Câu  Cũ", current = "câu cũ"))
        assertEquals(OpeningOptionRejection.DUPLICATE, reject("MỘT  câu", seen = setOf("một câu")))
        val nfc = Normalizer.normalize("Hoà bình", Normalizer.Form.NFC)
        val nfd = Normalizer.normalize("Hoà bình", Normalizer.Form.NFD)
        assertEquals(OpeningOptionRejection.DUPLICATE, reject(nfd, seen = setOf(nfc)))
    }
}
