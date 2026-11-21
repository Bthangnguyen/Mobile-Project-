package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Đọc từng mục để một bản ghi hỏng không chặn màn Kênh. */
internal object ChannelAccountMetadata {
    @Serializable
    private data class Entry(
        val type: String,
        val accountId: String,
        val displayName: String,
        val tokenWriteId: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val supportedTypes = ChannelType.entries.associateBy { it.name }

    fun decode(raw: String?): List<StoredChannelAccount> =
        readArray(raw).mapNotNull { element ->
            val entry = decodeEntry(element) ?: return@mapNotNull null
            val type = supportedTypes[entry.type] ?: return@mapNotNull null
            StoredChannelAccount(type, entry.accountId, entry.displayName, entry.tokenWriteId)
        }

    fun upsert(raw: String?, account: StoredChannelAccount): String {
        val entry = Entry(account.type.name, account.accountId, account.displayName, account.tokenWriteId)
        return update(raw, account.type, account.accountId, json.encodeToJsonElement(Entry.serializer(), entry))
    }

    fun remove(raw: String?, type: ChannelType, accountId: String): String =
        update(raw, type, accountId, null)

    /** Giữ nguyên các mục khác, kể cả dữ liệu của phiên bản mới hơn. */
    private fun update(raw: String?, type: ChannelType, accountId: String, replacement: JsonElement?): String {
        var found = false
        val entries = buildList {
            for (element in readArray(raw)) {
                val entry = decodeEntry(element)
                if (entry?.type == type.name && entry.accountId == accountId) {
                    if (!found && replacement != null) add(replacement)
                    found = true
                } else {
                    add(element)
                }
            }
            if (!found && replacement != null) add(replacement)
        }
        return JsonArray(entries).toString()
    }

    private fun readArray(raw: String?): List<JsonElement> {
        if (raw == null) return emptyList()
        return try {
            json.parseToJsonElement(raw) as? JsonArray ?: emptyList()
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    private fun decodeEntry(element: JsonElement): Entry? = try {
        json.decodeFromJsonElement(Entry.serializer(), element).takeIf { it.accountId.isNotBlank() }
    } catch (_: SerializationException) {
        null
    }
}
