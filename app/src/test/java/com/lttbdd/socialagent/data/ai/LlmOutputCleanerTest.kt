package com.lttbdd.socialagent.data.ai

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmOutputCleanerTest {

    private val post = "Ba bài học từ dự án đầu tiên.\n\nHãy chia sẻ bài học của bạn! #AI"

    @Test
    fun `van ban binh thuong chi bi cat khoang trang hai dau`() {
        assertEquals(post, LlmOutputCleaner.clean("\n  $post \n"))
    }

    @Test
    fun `go khoi code bao toan bo ket qua, co hoac khong co ten ngon ngu`() {
        assertEquals(post, LlmOutputCleaner.clean("```\n$post\n```"))
        assertEquals(post, LlmOutputCleaner.clean("```text\n$post\n```"))
        assertEquals(post, LlmOutputCleaner.clean("```markdown\r\n$post\r\n```"))
    }

    @Test
    fun `khoi code nam giua bai hoac nhieu khoi rieng thi giu nguyen`() {
        val inner = "Mở đầu\n```\nval x = 1\n```\nKết bài"
        assertEquals(inner, LlmOutputCleaner.clean(inner))
        val twoBlocks = "```\nA\n```\nGiữa\n```\nB\n```"
        assertEquals(twoBlocks, LlmOutputCleaner.clean(twoBlocks))
    }

    @Test
    fun `khoi code rong tra ve chuoi rong`() {
        assertEquals("", LlmOutputCleaner.clean("```\n   \n```"))
    }

    @Test
    fun `go cau dan tieng Anh`() {
        listOf(
            "Here is the final post:",
            "Here's the revised LinkedIn post:",
            "Here’s the improved, final version:",
            "Sure! Here is your post for the Facebook Page:",
            "Certainly, here's a draft:",
            "Below is the outline:",
            "Here is the image description:",
        ).forEach { preamble ->
            assertEquals(preamble, post, LlmOutputCleaner.clean("$preamble\n\n$post"))
        }
    }

    @Test
    fun `go cau dan tieng Viet ke ca dang NFD`() {
        listOf(
            "Dưới đây là bài viết hoàn chỉnh:",
            "Đây là bản nháp đã chỉnh sửa cho LinkedIn:",
            "Vâng, đây là bài đăng cho trang Facebook:",
            "Sau đây là dàn ý:",
            "Dưới đây là mô tả ảnh:",
            Normalizer.normalize("Dưới đây là bài viết cuối cùng:", Normalizer.Form.NFD),
        ).forEach { preamble ->
            assertEquals(preamble, post, LlmOutputCleaner.clean("$preamble\n$post"))
        }
    }

    @Test
    fun `cau mo dau that cua bai khong bi xoa nham`() {
        listOf(
            "Here's what I learned shipping our first app:",
            "Here are 3 lessons from our first launch:",
            "Here's the thing:",
            "Here is my post about burnout:",
            "3 bài học từ dự án đầu tiên:",
            "Đây là bài viết hay nhất tôi từng đọc:",
            "Đây là 3 điều mình rút ra:",
        ).forEach { hook ->
            val text = "$hook\n- Một\n- Hai"
            assertEquals(hook, text, LlmOutputCleaner.clean(text))
        }
    }

    @Test
    fun `chi co cau dan ma khong co noi dung thi giu nguyen`() {
        assertEquals("Here is the final post:", LlmOutputCleaner.clean("Here is the final post:"))
    }

    @Test
    fun `go ca cau dan lan khoi code theo moi thu tu`() {
        assertEquals(post, LlmOutputCleaner.clean("Here is the final post:\n```\n$post\n```"))
        assertEquals(post, LlmOutputCleaner.clean("```\nDưới đây là bài viết hoàn chỉnh:\n$post\n```"))
    }
}
