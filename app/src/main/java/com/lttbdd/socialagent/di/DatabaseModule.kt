package com.lttbdd.socialagent.di

import android.content.Context
import androidx.room.Room
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.dao.CommentDao
import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.dao.MetricsDao
import com.lttbdd.socialagent.core.database.dao.PostDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Cung cấp DB cục bộ và các DAO. Repository của từng tính năng inject DAO cần dùng từ đây. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    fun draftDao(db: AppDatabase): DraftDao = db.draftDao()

    @Provides
    fun postDao(db: AppDatabase): PostDao = db.postDao()

    @Provides
    fun commentDao(db: AppDatabase): CommentDao = db.commentDao()

    @Provides
    fun metricsDao(db: AppDatabase): MetricsDao = db.metricsDao()
}
