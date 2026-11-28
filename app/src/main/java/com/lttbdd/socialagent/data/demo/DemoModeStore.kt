package com.lttbdd.socialagent.data.demo

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
 * Trạng thái chế độ Demo (F8 [N], #73): dùng thử app bằng nội dung mẫu, không cần API key LLM.
 * Mặc định tắt.
 */
interface DemoModeStore {
    /** Phát trạng thái hiện tại rồi mỗi lần người dùng đổi. */
    fun observe(): Flow<Boolean>

    /** Đọc trạng thái ngay lúc gọi; client LLM đọc lại ở mỗi lệnh gọi. */
    suspend fun isEnabled(): Boolean = observe().first()

    suspend fun setEnabled(enabled: Boolean)
}

/** Tệp hỏng được thay bằng cấu hình rỗng: chế độ Demo về mặc định (tắt) thay vì app bị lỗi. */
internal fun demoModeCorruptionHandler() = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

private val Context.demoModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "social_agent_demo_mode",
    corruptionHandler = demoModeCorruptionHandler(),
)

/** Lưu trong DataStore Preferences; chỉ có một giá trị boolean nên không cần mã hóa. */
class DataStoreDemoModeStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : DemoModeStore {
    constructor(context: Context) : this(context.demoModeDataStore)

    override fun observe(): Flow<Boolean> = dataStore.data
        // Đọc tệp lỗi thì coi như tắt: không âm thầm đổi sang nội dung mẫu.
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it[ENABLED_KEY] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED_KEY] = enabled }
    }

    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("demo_mode_enabled")
    }
}
