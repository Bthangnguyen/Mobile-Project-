package com.lttbdd.socialagent.di

import com.lttbdd.socialagent.core.ai.ContentAgent
import com.lttbdd.socialagent.data.fake.FakeContentAgent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Gắn các bản Fake vào hợp đồng trong core/.
 * Khi có implementation thật, chủ mảng thay đúng hàm @Provides của mình — code gọi không phải sửa.
 */
@Module
@InstallIn(SingletonComponent::class)
object FakeDataModule {

    @Provides
    @Singleton
    fun contentAgent(): ContentAgent = FakeContentAgent()
}
