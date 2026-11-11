package com.lttbdd.socialagent.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lttbdd.socialagent.core.model.ChannelType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.channelAccountDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "social_agent_channel_accounts",
)

/** Lưu metadata tài khoản dưới dạng JSON trong DataStore (không mã hóa vì không chứa bí mật). */
class DataStoreChannelAccountStore(
    private val context: Context,
) : ChannelAccountStore {

    @Serializable
    private data class Entry(val type: String, val accountId: String, val displayName: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("accounts_v1")
    private val serializer = ListSerializer(Entry.serializer())

    override suspend fun load(): List<StoredChannelAccount> {
        val raw = context.channelAccountDataStore.data.first()[key] ?: return emptyList()
        return try {
            json.decodeFromString(serializer, raw).mapNotNull { entry ->
                val type = ChannelType.entries.firstOrNull { it.name == entry.type } ?: return@mapNotNull null
                StoredChannelAccount(type, entry.accountId, entry.displayName)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun save(accounts: List<StoredChannelAccount>) {
        val encoded = json.encodeToString(serializer, accounts.map { Entry(it.type.name, it.accountId, it.displayName) })
        context.channelAccountDataStore.edit { it[key] = encoded }
    }
}
