package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import java.util.concurrent.ConcurrentHashMap

/** Cấu hình không chứa key; model do bên cấu hình lựa chọn, không tự fallback. */
data class LlmModelConfiguration(
    val provider: LlmProvider,
    val model: String,
    val supportsTemperature: Boolean = true,
)

fun interface LlmConfigurationSource {
    suspend fun configurationFor(task: LlmTask): LlmModelConfiguration?
}

/** #53 cung cấp implementation đọc secure storage mỗi lần gọi; không cache key tại router. */
fun interface ApiKeySource {
    suspend fun keyFor(provider: LlmProvider): String?
}

/** Chỉ giữ lựa chọn trong phiên; màn hình/persistence cấu hình nằm ngoài #52. */
class InMemoryLlmConfiguration : LlmConfigurationSource {
    private val selections = ConcurrentHashMap<LlmTask, LlmModelConfiguration>()

    fun set(task: LlmTask, configuration: LlmModelConfiguration) {
        selections[task] = configuration
    }

    fun clear(task: LlmTask) {
        selections.remove(task)
    }

    override suspend fun configurationFor(task: LlmTask): LlmModelConfiguration? = selections[task]
}
