package com.lttbdd.socialagent.di

import android.content.Context
import com.lttbdd.socialagent.core.ai.ContentAgent
import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.data.ai.ApiKeySource
import com.lttbdd.socialagent.data.ai.ChannelImageRequest
import com.lttbdd.socialagent.data.ai.ContentAgentImpl
import com.lttbdd.socialagent.data.ai.DraftImageFiles
import com.lttbdd.socialagent.data.ai.DraftImages
import com.lttbdd.socialagent.data.ai.LlmConfigurationSource
import com.lttbdd.socialagent.data.ai.ProviderImageApi
import com.lttbdd.socialagent.data.ai.SavingContentAgent
import com.lttbdd.socialagent.data.ai.llmChannelSubAgents
import com.lttbdd.socialagent.data.review.DraftHistoryWriteException
import com.lttbdd.socialagent.data.review.DraftReviewRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object ContentAgentModule {
    @Provides
    @Singleton
    fun contentAgent(
        @ApplicationContext context: Context,
        drafts: DraftReviewRepository,
        llm: LlmClient,
        configurations: LlmConfigurationSource,
        keys: ApiKeySource,
    ): ContentAgent {
        val http = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
        val files = DraftImageFiles(context)
        val images = DraftImages(configurations, keys, ProviderImageApi(http), files, files)
        val agent = ContentAgentImpl(
            subAgents = llmChannelSubAgents(llm, images),
            createImage = { draft ->
                images.create(ChannelImageRequest(draft.topicId, draft.channel, draft.text, draft.text))
            },
        )
        return SavingContentAgent(agent) { draft ->
            val saved = drafts.saveAgentDraft(draft)
            val failure = saved.exceptionOrNull()
            // Lỗi ghi lịch sử xảy ra sau commit Room, khi bản nháp vẫn đang dùng URI này.
            if (failure != null && failure !is DraftHistoryWriteException && failure !is CancellationException) {
                files.discard(draft.imageUri)
            }
            saved.getOrThrow()
        }
    }
}
