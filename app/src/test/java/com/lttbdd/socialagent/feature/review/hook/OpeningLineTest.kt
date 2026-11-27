package com.lttbdd.socialagent.feature.review.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpeningLineTest {

    @Test
    fun `find returns first non-blank line trimmed`() {
        assertEquals("Mở đầu", OpeningLine.find("Mở đầu\nThân bài"))
        assertEquals("Mở đầu", OpeningLine.find("\n  \n   Mở đầu  \nThân bài"))
        assertEquals("Một dòng", OpeningLine.find("Một dòng"))
    }

    @Test
    fun `find returns null for blank text`() {
        assertNull(OpeningLine.find(""))
        assertNull(OpeningLine.find("  \n\t\n "))
    }

    @Test
    fun `find ignores carriage return of CRLF line endings`() {
        assertEquals("Mở đầu", OpeningLine.find("Mở đầu\r\nThân bài"))
    }

    @Test
    fun `replace keeps every other character of a multi-line post`() {
        val body = "\n\nĐoạn hai có emoji 🚀 và #HashtagViệt\n\n- ý một\n- ý hai\n"
        val text = "Câu mở đầu cũ$body"

        val result = OpeningLine.replace(text, "Câu mở đầu mới?")

        assertEquals("Câu mở đầu mới?$body", result)
    }

    @Test
    fun `replace keeps leading blank lines and indentation around the opening`() {
        val text = "\n  Câu cũ  \nThân bài"

        assertEquals("\n  Câu mới  \nThân bài", OpeningLine.replace(text, "Câu mới"))
    }

    @Test
    fun `replace trims the new opening`() {
        assertEquals("Câu mới\nThân bài", OpeningLine.replace("Câu cũ\nThân bài", "  Câu mới \n"))
    }

    @Test
    fun `replace keeps CRLF line endings`() {
        assertEquals("Câu mới\r\nThân bài", OpeningLine.replace("Câu cũ\r\nThân bài", "Câu mới"))
    }

    @Test
    fun `single-line post gets the option inserted above the old content`() {
        assertEquals("Câu mới\n\nBài chỉ một dòng", OpeningLine.replace("Bài chỉ một dòng", "Câu mới"))
        assertEquals("Câu mới\n\nBài một dòng\n\n", OpeningLine.replace("Bài một dòng\n\n", "Câu mới"))
        assertEquals("\nCâu mới\n\n  Bài một dòng", OpeningLine.replace("\n  Bài một dòng", "Câu mới"))
    }

    @Test
    fun `blank post becomes the option`() {
        assertEquals("Câu mới", OpeningLine.replace("", "Câu mới"))
        assertEquals("Câu mới", OpeningLine.replace(" \n ", "Câu mới"))
    }

    @Test
    fun `replace then find returns the new opening`() {
        val text = "Cũ 😀\nThân bài"
        val replaced = OpeningLine.replace(text, "Mới 🎯")

        assertEquals("Mới 🎯", OpeningLine.find(replaced))
    }
}
