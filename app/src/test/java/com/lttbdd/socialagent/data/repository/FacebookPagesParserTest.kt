package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.data.model.FacebookPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Test phân tích danh sách Page từ `/me/accounts`; dùng token giả, không gọi mạng. */
class FacebookPagesParserTest {

    @Test
    fun `lay du id ten va page token`() {
        val body = """{"data":[
            {"id":"1","name":"Page A","access_token":"tokA","category":"Blog"},
            {"id":"2","name":"Page B","access_token":"tokB"}
        ],"paging":{}}"""

        assertEquals(
            listOf(FacebookPage("1", "Page A", "tokA"), FacebookPage("2", "Page B", "tokB")),
            FacebookPagesParser.parse(body),
        )
    }

    @Test
    fun `bo qua phan tu thieu truong va loai trung id`() {
        val body = """{"data":[
            {"id":"1","name":"A","access_token":"t"},
            {"id":"1","name":"A lap","access_token":"t2"},
            {"id":"2","name":"","access_token":"t"},
            {"id":"3","name":"C"},
            {"name":"D","access_token":"t"}
        ]}"""

        assertEquals(listOf(FacebookPage("1", "A", "t")), FacebookPagesParser.parse(body))
    }

    @Test
    fun `chi mang data rong hop le moi la khong co Page`() {
        assertEquals(emptyList<FacebookPage>(), FacebookPagesParser.parse("""{"data":[]}"""))
        listOf(
            "",
            "not_json",
            """{"error":{"code":190},"data":[]}""",
            """{"data":null}""",
            """{"data":[{"id":"1","name":"A"}]}""",
            """{"data":[{"id":"1","name":"A","access_token":null}]}""",
        ).forEach { assertNull(FacebookPagesParser.parse(it)) }
    }

    @Test
    fun `toString khong lo page token`() {
        assertFalse(FacebookPage("1", "A", "bi-mat").toString().contains("bi-mat"))
    }

    @Test
    fun `doc Page task, thieu truong tasks thi la null (chua biet), mang rong thi la rong`() {
        val body = """{"data":[
            {"id":"1","name":"A","access_token":"t1","tasks":["ANALYZE","MODERATE"]},
            {"id":"2","name":"B","access_token":"t2"},
            {"id":"3","name":"C","access_token":"t3","tasks":[]}
        ]}"""

        val pages = requireNotNull(FacebookPagesParser.parse(body))

        assertEquals(listOf("ANALYZE", "MODERATE"), pages[0].tasks)
        assertEquals(null, pages[1].tasks)
        assertEquals(emptyList<String>(), pages[2].tasks)
    }
}
