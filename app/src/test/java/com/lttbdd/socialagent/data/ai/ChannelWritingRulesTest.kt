package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChannelType
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelWritingRulesTest {

    private val facebook = ChannelWritingRules.of(ChannelType.FACEBOOK)
    private val linkedIn = ChannelWritingRules.of(ChannelType.LINKEDIN)

    @Test
    fun `moi kenh co quy tac rieng va gioi han cung lay tu ChannelType`() {
        ChannelType.entries.forEach { channel ->
            val rules = ChannelWritingRules.of(channel)
            assertEquals(channel, rules.channel)
            assertEquals(channel.maxChars, rules.maxChars)
            assertTrue(rules.targetMaxChars < rules.maxChars)
            assertTrue(rules.styleGuide.isNotEmpty())
        }
        assertNotEquals(facebook.promptName, linkedIn.promptName)
        assertNotEquals(facebook.styleGuide, linkedIn.styleGuide)
        assertEquals(3, facebook.maxHashtags)
        assertEquals(5, linkedIn.maxHashtags)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `targetMaxChars vuot gioi han cung bi tu choi`() {
        linkedIn.copy(targetMaxChars = ChannelType.LINKEDIN.maxChars + 1)
    }

    @Test
    fun `bai hop le khong co vi pham`() {
        assertEquals(emptyList<ChannelRuleViolation>(), linkedIn.check("Xin chào!\n\nNội dung chính.\n#AI #Kotlin"))
    }

    @Test
    fun `vuot gioi han ky tu cua kenh`() {
        assertEquals(emptyList<ChannelRuleViolation>(), linkedIn.check("a".repeat(3_000)))
        assertEquals(listOf(ChannelRuleViolation.TooLong(3_001, 3_000)), linkedIn.check("a".repeat(3_001)))
    }

    @Test
    fun `dem ky tu theo code point sau chuan hoa NFC giong bo dem F4`() {
        val decomposed = Normalizer.normalize("ệ".repeat(3_000), Normalizer.Form.NFD)
        assertTrue(decomposed.length > 3_000)
        assertEquals(emptyList<ChannelRuleViolation>(), linkedIn.check(decomposed))

        assertEquals(emptyList<ChannelRuleViolation>(), linkedIn.check("😀".repeat(3_000)))
        assertEquals(listOf(ChannelRuleViolation.TooLong(3_001, 3_000)), linkedIn.check("😀".repeat(3_001)))
    }

    @Test
    fun `dem hashtag dung dau tu va co chu cai`() {
        assertEquals(
            listOf(ChannelRuleViolation.TooManyHashtags(6, 5)),
            linkedIn.check("Bài viết\n#AI #ML #Kotlin #Android #Dev #TríTuệ"),
        )
        // "#1", "C#", neo URL và "##" không phải hashtag nên chỉ có 3 hashtag thật.
        assertEquals(
            emptyList<ChannelRuleViolation>(),
            facebook.check("Lý do #1 để học C# là https://x.com/docs#intro ## #Kotlin #Android #AI"),
        )
        assertEquals(
            listOf(ChannelRuleViolation.TooManyHashtags(4, 3)),
            facebook.check("#Kotlin #Android #AI #Dev"),
        )
    }

    @Test
    fun `hashtag tieng Viet dang NFD van duoc dem`() {
        val decomposed = Normalizer.normalize("#TríTuệ #NhânTạo #HọcMáy #DữLiệu", Normalizer.Form.NFD)
        assertEquals(listOf(ChannelRuleViolation.TooManyHashtags(4, 3)), facebook.check(decomposed))
    }

    @Test
    fun `phat hien Markdown ma kenh khong hien thi`() {
        listOf(
            "Đây là **điểm chính** cần nhớ",
            "Đây là __điểm chính__ cần nhớ",
            "# Tiêu đề\nNội dung",
            "Mở đầu\n## Mục 1",
            "Xem [tài liệu](https://x.com/docs)",
            "```kotlin\nval x = 1\n```",
        ).forEach { text ->
            assertEquals(text, listOf(ChannelRuleViolation.MarkdownFormatting), facebook.check(text))
        }
    }

    @Test
    fun `van ban thuong khong bi coi la Markdown`() {
        listOf(
            "#AI ở đầu dòng là hashtag",
            "5 * 3 = 15 và 2 * 4 = 8",
            "ten_bien_snake_case",
            "Giá [tham khảo] hôm nay",
            "Nhấn mạnh *nhẹ* thôi",
        ).forEach { text ->
            assertEquals(text, emptyList<ChannelRuleViolation>(), facebook.check(text))
        }
    }

    @Test
    fun `tra ve moi vi pham cung luc`() {
        val text = "**Tiêu đề**\n" + "a".repeat(3_000) + "\n#A #B #C #D #E #F"

        val violations = linkedIn.check(text)

        assertEquals(3, violations.size)
        assertTrue(violations[0] is ChannelRuleViolation.TooLong)
        assertEquals(ChannelRuleViolation.TooManyHashtags(6, 5), violations[1])
        assertEquals(ChannelRuleViolation.MarkdownFormatting, violations[2])
    }
}
