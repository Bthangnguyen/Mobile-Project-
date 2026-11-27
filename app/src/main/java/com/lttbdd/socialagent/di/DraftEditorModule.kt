package com.lttbdd.socialagent.di

import com.lttbdd.socialagent.feature.review.DraftEditorRepository
import com.lttbdd.socialagent.feature.review.RoomDraftEditorRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Cung cấp repository chỉnh sửa & xem trước bản nháp cho tính năng F4 (#34). */
@Module
@InstallIn(SingletonComponent::class)
abstract class DraftEditorModule {

    @Binds
    @Singleton
    abstract fun bindDraftEditorRepository(
        impl: RoomDraftEditorRepository,
    ): DraftEditorRepository
}
