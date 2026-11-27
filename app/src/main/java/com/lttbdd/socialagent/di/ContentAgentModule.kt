package com.lttbdd.socialagent.di

import android.content.Context
import androidx.room.withTransaction
import com.lttbdd.socialagent.core.ai.ContentAgent
import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.toEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.data.ai.ApiKeySource
import com.lttbdd.socialagent.data.ai.ContentAgentImpl
import com.lttbdd.socialagent.data.ai.DraftChannelSubAgent
import com.lttbdd.socialagent.data.ai.DraftImageFiles
import com.lttbdd.socialagent.data.ai.DraftImages
import com.lttbdd.socialagent.data.ai.LlmConfigurationSource
import com.lttbdd.socialagent.data.ai.ProviderImageApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object ContentAgentModule {
    @Provides
    @Singleton
    fun contentAgent(
        @ApplicationContext context: Context,
        database: AppDatabase,
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
        val saveDraft: suspend (Draft) -> Unit = { draft ->
            database.withTransaction {
                val drafts = database.draftDao()
                val existing = drafts.getById(draft.id)
                check(existing?.approved != true && drafts.countPendingPosts(draft.id) == 0)
                drafts.upsert(draft.toEntity(System.currentTimeMillis()))
            }
        }
        return ContentAgentImpl(
            subAgents = ChannelType.entries.associateWith { DraftChannelSubAgent(it, llm, images, saveDraft) },
            createImage = { draft -> images.create(draft, "") },
        )
    }
}
