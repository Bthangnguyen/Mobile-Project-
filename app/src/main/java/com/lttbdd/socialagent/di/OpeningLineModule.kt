package com.lttbdd.socialagent.di

import com.lttbdd.socialagent.feature.review.hook.LlmOpeningLineSuggester
import com.lttbdd.socialagent.feature.review.hook.OpeningLineSuggester
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Cung cấp bộ sinh phương án câu mở đầu cho F4 (#63); gọi LLM qua `LlmClient` đã gắn trong [LlmModule]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OpeningLineModule {

    @Binds
    abstract fun bindOpeningLineSuggester(
        impl: LlmOpeningLineSuggester,
    ): OpeningLineSuggester
}
