package com.lttbdd.socialagent.di

import android.content.Context
import com.lttbdd.socialagent.data.ai.AgentLogRecorder
import com.lttbdd.socialagent.data.ai.AgentLogStore
import com.lttbdd.socialagent.data.ai.FileAgentLogStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Cung cấp kho lưu trữ và bộ ghi nhật ký agent cho F2 (#31). */
@Module
@InstallIn(SingletonComponent::class)
object AgentLogModule {

    @Provides
    @Singleton
    fun provideAgentLogStore(
        @ApplicationContext context: Context,
    ): AgentLogStore = FileAgentLogStore(
        baseDir = File(context.filesDir, FileAgentLogStore.DIRECTORY_NAME),
    )

    @Provides
    @Singleton
    fun provideAgentLogRecorder(
        store: AgentLogStore,
    ): AgentLogRecorder = AgentLogRecorder(store)
}
