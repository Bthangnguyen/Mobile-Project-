package com.lttbdd.socialagent.di

import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.data.ai.ApiKeySource
import com.lttbdd.socialagent.data.ai.GeminiClient
import com.lttbdd.socialagent.data.ai.InMemoryLlmConfiguration
import com.lttbdd.socialagent.data.ai.LlmConfigurationSource
import com.lttbdd.socialagent.data.ai.OkHttpSseTransport
import com.lttbdd.socialagent.data.ai.OpenAiClient
import com.lttbdd.socialagent.data.ai.RoutingLlmClient
import com.lttbdd.socialagent.data.apikeys.ApiKeyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.sse.EventSources

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {
    @Provides
    @Singleton
    fun selections(): InMemoryLlmConfiguration = InMemoryLlmConfiguration()

    @Provides
    fun configurations(selections: InMemoryLlmConfiguration): LlmConfigurationSource = selections

    /** Đọc secure storage ngay trước mỗi request; không giữ key cũ sau khi thay/xóa. */
    @Provides
    fun apiKeys(store: ApiKeyStore): ApiKeySource = ApiKeySource { provider -> store.read(provider) }

    @Provides
    @Singleton
    fun llmClient(configurations: LlmConfigurationSource, keys: ApiKeySource): LlmClient {
        // Client riêng cho LLM, không ảnh hưởng HTTP/DI của mảng khác. Không có interceptor ghi log.
        val http = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val transport = OkHttpSseTransport(EventSources.createFactory(http))
        return RoutingLlmClient(configurations, keys, setOf(OpenAiClient(transport), GeminiClient(transport)))
    }
}
