package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.data.model.FacebookPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Logic thuần phân tích JSON của Graph API `/me/accounts` thành danh sách [FacebookPage].
 * Bỏ qua phần tử thiếu id, tên hoặc access_token; JSON sai định dạng trả về danh sách rỗng.
 */
object FacebookPagesParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<FacebookPage> {
        return try {
            val data = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray
                ?: return emptyList()
            data.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = (item["id"] as? JsonPrimitive)?.content
                val name = (item["name"] as? JsonPrimitive)?.content
                val token = (item["access_token"] as? JsonPrimitive)?.content
                if (id.isNullOrBlank() || name.isNullOrBlank() || token.isNullOrBlank()) {
                    null
                } else {
                    FacebookPage(id = id, name = name, accessToken = token)
                }
            }.distinctBy { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
