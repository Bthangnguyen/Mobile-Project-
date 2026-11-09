package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.data.model.FacebookPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `json sai hoac khong co data tra ve rong`() {
        assertTrue(FacebookPagesParser.parse("").isEmpty())
        assertTrue(FacebookPagesParser.parse("not_json").isEmpty())
        assertTrue(FacebookPagesParser.parse("""{"data":[]}""").isEmpty())
        assertTrue(FacebookPagesParser.parse("""{"error":{"code":190}}""").isEmpty())
    }

    @Test
    fun `toString khong lo page token`() {
        assertFalse(FacebookPage("1", "A", "bi-mat").toString().contains("bi-mat"))
    }
}
