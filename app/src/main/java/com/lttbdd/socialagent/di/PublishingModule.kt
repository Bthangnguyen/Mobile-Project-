package com.lttbdd.socialagent.di

import com.lttbdd.socialagent.core.publishing.PublishQueue
import com.lttbdd.socialagent.core.publishing.PublishTracker
import com.lttbdd.socialagent.data.publishing.PublishClock
import com.lttbdd.socialagent.data.publishing.PublishQueueRepository
import com.lttbdd.socialagent.data.publishing.PublishScheduler
import com.lttbdd.socialagent.data.publishing.RoomPublishTracker
import com.lttbdd.socialagent.data.publishing.WorkManagerPublishScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hàng đợi đăng bài F5 (A): WorkManager làm bộ hẹn giờ, đồng hồ hệ thống làm nguồn thời gian. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PublishingModule {

    @Binds
    abstract fun publishScheduler(impl: WorkManagerPublishScheduler): PublishScheduler

    /** Màn hình (feature/review) gọi hàng đợi qua interface `core/`, không import thẳng `data/publishing`. */
    @Binds
    abstract fun publishQueue(impl: PublishQueueRepository): PublishQueue

    /** Màn Trạng thái đăng (feature/publishing, #36) đọc trạng thái từng kênh qua interface `core/`. */
    @Binds
    abstract fun publishTracker(impl: RoomPublishTracker): PublishTracker

    companion object {
        @Provides
        fun publishClock(): PublishClock = PublishClock(System::currentTimeMillis)
    }
}
