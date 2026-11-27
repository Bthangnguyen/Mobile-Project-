package com.lttbdd.socialagent.data.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Người dùng có cho phép tự chuyển sang nhà cung cấp phụ khi nhà cung cấp chính lỗi không (#72).
 * Mặc định tắt, vì chuyển sang nhà cung cấp khác sẽ dùng key và tính phí ở đó.
 */
interface FallbackPreferenceStore {
    fun observe(): Flow<Boolean>

    suspend fun isEnabled(): Boolean = observe().first()

    suspend fun setEnabled(enabled: Boolean)
}

/** Tệp hỏng được thay bằng cấu hình rỗng: quay về mặc định (tắt) thay vì app bị lỗi. */
internal fun fallbackCorruptionHandler() = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

private val Context.fallbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "social_agent_llm_fallback",
    corruptionHandler = fallbackCorruptionHandler(),
)

/** Lưu trong DataStore Preferences; chỉ có một boolean nên không cần mã hóa. */
class DataStoreFallbackPreferenceStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : FallbackPreferenceStore {
    constructor(context: Context) : this(context.fallbackDataStore)

    override fun observe(): Flow<Boolean> = dataStore.data
        // Đọc tệp lỗi thì coi như tắt: không âm thầm gọi nhà cung cấp khác.
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it[ENABLED_KEY] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED_KEY] = enabled }
    }

    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("fallback_enabled")
    }
}
