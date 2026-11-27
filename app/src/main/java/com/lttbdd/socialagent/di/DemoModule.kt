package com.lttbdd.socialagent.di

import android.content.Context
import com.lttbdd.socialagent.data.demo.DataStoreDemoModeStore
import com.lttbdd.socialagent.data.demo.DemoModeStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Chế độ Demo (F8 [N], #73). */
@Module
@InstallIn(SingletonComponent::class)
object DemoModule {
    @Provides
    @Singleton
    fun demoModeStore(@ApplicationContext context: Context): DemoModeStore = DataStoreDemoModeStore(context)
}
