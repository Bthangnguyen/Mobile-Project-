package com.lttbdd.socialagent.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import com.lttbdd.socialagent.core.database.dao.CommentDao
import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.dao.LlmUsageDao
import com.lttbdd.socialagent.core.database.dao.MetricsDao
import com.lttbdd.socialagent.core.database.dao.PostDao
import com.lttbdd.socialagent.core.database.entity.CommentEntity
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.database.entity.LlmUsageEntity
import com.lttbdd.socialagent.core.database.entity.MetricsSnapshotEntity
import com.lttbdd.socialagent.core.database.entity.PostEntity

/**
 * DB cục bộ của app: nguồn dữ liệu chính để UI đọc, kể cả khi offline (F9).
 * Mạng chỉ cập nhật vào đây. Đổi schema thì tăng [version] và thêm migration — xem `app/schemas/`.
 * Enum (ChannelType, PostStatus…) được lưu theo tên: đổi tên một hằng số enum đã lưu cũng là đổi schema,
 * cần migration để chuyển dữ liệu cũ.
 *
 * Version 2 (#74) chỉ thêm bảng `llm_usage`, nên dùng AutoMigration và giữ nguyên dữ liệu cũ.
 */
@Database(
    entities = [
        DraftEntity::class,
        PostEntity::class,
        CommentEntity::class,
        MetricsSnapshotEntity::class,
        LlmUsageEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao
    abstract fun postDao(): PostDao
    abstract fun commentDao(): CommentDao
    abstract fun metricsDao(): MetricsDao
    abstract fun llmUsageDao(): LlmUsageDao

    companion object {
        const val NAME = "social_agent.db"
    }
}
