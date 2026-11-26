package com.lttbdd.socialagent.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Kiểm tra quy tắc 4 trong AGENTS.md và AC của #21: mọi chuỗi hiển thị có đủ bản EN (`values/`)
 * và VI (`values-vi/`), cùng số lượng và kiểu tham số định dạng.
 *
 * Đọc thẳng file XML trong `src/main/res` (thư mục làm việc của Gradle khi chạy unit test là module `app`),
 * nên không cần Robolectric hay máy ảo.
 */
class StringResourcesParityTest {

    private val english = loadStrings("src/main/res/values/strings.xml")
    private val vietnamese = loadStrings("src/main/res/values-vi/strings.xml")

    @Test
    fun `every translatable English string has a Vietnamese translation`() {
        val missing = english.filterValues { it.translatable }.keys - vietnamese.keys
        assertTrue("Thiếu bản VI trong values-vi/strings.xml: ${missing.sorted()}", missing.isEmpty())
    }

    @Test
    fun `every Vietnamese string exists in the default English file`() {
        // Chuỗi chỉ có ở values-vi sẽ làm app crash (Resources.NotFoundException) khi máy dùng ngôn ngữ khác.
        val orphaned = vietnamese.keys - english.keys
        assertTrue("Chuỗi chỉ có trong values-vi, thiếu ở values: ${orphaned.sorted()}", orphaned.isEmpty())
    }

    @Test
    fun `translations keep the same format arguments`() {
        val mismatched = english.filterValues { it.translatable }.keys
            .intersect(vietnamese.keys)
            .filter { name -> formatArgs(english.getValue(name).text) != formatArgs(vietnamese.getValue(name).text) }
        assertTrue(
            "Tham số định dạng (%s, %1\$d…) khác nhau giữa EN và VI: ${mismatched.sorted()}",
            mismatched.isEmpty(),
        )
    }

    @Test
    fun `format argument parser counts positional and plain arguments`() {
        assertEquals(listOf("%1\$s", "%2\$d"), formatArgs("Đã đăng %2\$d bài lên %1\$s"))
        assertEquals(listOf("%d"), formatArgs("%d characters, 100%% done"))
        assertEquals(emptyList<String>(), formatArgs("Không có tham số"))
    }

    private data class StringResource(val text: String, val translatable: Boolean)

    private fun loadStrings(path: String): Map<String, StringResource> {
        val file = File(path)
        assertTrue("Không tìm thấy ${file.absolutePath}", file.isFile)
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .documentElement.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { element ->
            element.getAttribute("name") to StringResource(
                text = element.textContent,
                translatable = element.getAttribute("translatable") != "false",
            )
        }
    }

    /** Danh sách tham số định dạng đã sắp xếp; bỏ qua `%%` vì đó là ký tự phần trăm, không phải tham số. */
    private fun formatArgs(text: String): List<String> =
        FORMAT_ARG.findAll(text).map { it.value }.filter { it != "%%" }.sorted().toList()

    private companion object {
        val FORMAT_ARG = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")
    }
}
