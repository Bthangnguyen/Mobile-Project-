package com.lttbdd.socialagent.di

import android.content.Context
import com.lttbdd.socialagent.data.review.DefaultDraftVersionRepository
import com.lttbdd.socialagent.data.review.DraftReviewRepository
import com.lttbdd.socialagent.data.review.DraftVersionRepository
import com.lttbdd.socialagent.data.review.DraftVersionStore
import com.lttbdd.socialagent.data.review.FileDraftVersionStore
import com.lttbdd.socialagent.data.review.RoomDraftReviewRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Cung cấp repository kiểm duyệt và lịch sử phiên bản bản nháp cho tính năng F4 (#33, #62). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReviewModule {

    @Binds
    @Singleton
    abstract fun bindDraftReviewRepository(
        impl: RoomDraftReviewRepository,
    ): DraftReviewRepository

    @Binds
    @Singleton
    abstract fun bindDraftVersionRepository(
        impl: DefaultDraftVersionRepository,
    ): DraftVersionRepository

    companion object {
        @Provides
        @Singleton
        fun provideDraftVersionStore(
            @ApplicationContext context: Context,
        ): DraftVersionStore = FileDraftVersionStore(
            baseDir = File(context.filesDir, FileDraftVersionStore.DIRECTORY_NAME),
        )
    }
}
