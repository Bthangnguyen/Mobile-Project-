package com.lttbdd.socialagent.di

import com.lttbdd.socialagent.core.ai.ContentAgent
import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.social.SocialChannelClient
import com.lttbdd.socialagent.data.fake.FakeContentAgent
import com.lttbdd.socialagent.data.fake.FakeLlmClient
import com.lttbdd.socialagent.data.fake.FakeSocialChannelClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Gắn các bản Fake vào hợp đồng trong core/.
 * Khi có implementation thật, chủ mảng thay đúng hàm @Provides của mình — code gọi không phải sửa.
 */
@Module
@InstallIn(SingletonComponent::class)
object FakeDataModule {

    @Provides
    @IntoSet
    @Singleton
    fun facebookClient(): SocialChannelClient = FakeSocialChannelClient(ChannelType.FACEBOOK)

    @Provides
    @IntoSet
    @Singleton
    fun linkedInClient(): SocialChannelClient = FakeSocialChannelClient(ChannelType.LINKEDIN)

    @Provides
    @Singleton
    fun llmClient(): LlmClient = FakeLlmClient()

    @Provides
    @Singleton
    fun contentAgent(): ContentAgent = FakeContentAgent()
}
