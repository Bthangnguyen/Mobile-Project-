package com.lttbdd.socialagent.di

import com.lttbdd.socialagent.feature.review.DraftReviewRepository
import com.lttbdd.socialagent.feature.review.RoomDraftReviewRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Cung cấp repository kiểm duyệt bản nháp cho tính năng F4 (#33). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReviewModule {

    @Binds
    @Singleton
    abstract fun bindDraftReviewRepository(
        impl: RoomDraftReviewRepository,
    ): DraftReviewRepository
}
