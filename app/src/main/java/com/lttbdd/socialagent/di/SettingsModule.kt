package com.lttbdd.socialagent.di

import com.lttbdd.socialagent.data.settings.AppCompatLanguageManager
import com.lttbdd.socialagent.feature.settings.LanguageManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    abstract fun languageManager(impl: AppCompatLanguageManager): LanguageManager
}
