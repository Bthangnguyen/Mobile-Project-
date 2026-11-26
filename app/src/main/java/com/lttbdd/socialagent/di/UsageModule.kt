package com.lttbdd.socialagent.di

import com.lttbdd.socialagent.core.database.dao.LlmUsageDao
import com.lttbdd.socialagent.data.ai.LlmUsageRecorder
import com.lttbdd.socialagent.data.usage.RoomLlmUsageRecorder
import com.lttbdd.socialagent.data.usage.RoomUsageRepository
import com.lttbdd.socialagent.data.usage.SystemUsageTimeSource
import com.lttbdd.socialagent.data.usage.UsageRepository
import com.lttbdd.socialagent.data.usage.UsageTimeSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Thống kê token và chi phí LLM theo ngày/tháng (F8, #74). */
@Module
@InstallIn(SingletonComponent::class)
object UsageModule {
    @Provides
    fun timeSource(): UsageTimeSource = SystemUsageTimeSource

    /** Router (`LlmModule`) ghi usage qua binding này. */
    @Provides
    @Singleton
    fun usageRecorder(dao: LlmUsageDao, time: UsageTimeSource): LlmUsageRecorder = RoomLlmUsageRecorder(dao, time)

    @Provides
    @Singleton
    fun usageRepository(dao: LlmUsageDao, time: UsageTimeSource): UsageRepository = RoomUsageRepository(dao, time)
}
