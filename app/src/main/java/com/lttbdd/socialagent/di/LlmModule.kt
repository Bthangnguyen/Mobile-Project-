package com.lttbdd.socialagent.di

import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.data.ai.ApiKeySource
import com.lttbdd.socialagent.data.ai.GeminiClient
import com.lttbdd.socialagent.data.ai.InMemoryLlmConfiguration
import com.lttbdd.socialagent.data.ai.LlmConfigurationSource
import com.lttbdd.socialagent.data.ai.OkHttpSseTransport
import com.lttbdd.socialagent.data.ai.OpenAiClient
import com.lttbdd.socialagent.data.ai.RoutingLlmClient
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

    /** #53 thay provider này bằng nguồn key từ secure storage; hiện tại báo thiếu key an toàn. */
    @Provides
    fun apiKeys(): ApiKeySource = ApiKeySource { null }

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
