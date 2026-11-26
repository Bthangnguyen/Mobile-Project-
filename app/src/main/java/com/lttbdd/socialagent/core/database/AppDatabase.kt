package com.lttbdd.socialagent.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec
import androidx.room.RoomDatabase
import com.lttbdd.socialagent.core.database.dao.ChannelCleanupDao
import com.lttbdd.socialagent.core.database.dao.CommentDao
import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.dao.MetricsDao
import com.lttbdd.socialagent.core.database.dao.PostDao
import com.lttbdd.socialagent.core.database.entity.ChannelCleanupReceipt
import com.lttbdd.socialagent.core.database.entity.CommentEntity
import com.lttbdd.socialagent.core.database.entity.DeferredChannelPostCleanup
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.database.entity.MetricsSnapshotEntity
import com.lttbdd.socialagent.core.database.entity.PostEntity

/**
 * DB cục bộ của app: nguồn dữ liệu chính để UI đọc, kể cả khi offline (F9).
 * Mạng chỉ cập nhật vào đây. Đổi schema thì tăng [version] và thêm migration — xem `app/schemas/`.
 * Enum (ChannelType, PostStatus…) được lưu theo tên: đổi tên một hằng số enum đã lưu cũng là đổi schema,
 * cần migration để chuyển dữ liệu cũ.
 */
@Database(
    entities = [
        DraftEntity::class,
        PostEntity::class,
        CommentEntity::class,
        MetricsSnapshotEntity::class,
        ChannelCleanupReceipt::class,
        DeferredChannelPostCleanup::class,
    ],
    version = 4,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = RemoveDeferredCleanupTimestamp::class),
    ],
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelCleanupDao(): ChannelCleanupDao
    abstract fun draftDao(): DraftDao
    abstract fun postDao(): PostDao
    abstract fun commentDao(): CommentDao
    abstract fun metricsDao(): MetricsDao

    companion object {
        const val NAME = "social_agent.db"
    }
}

// Các thao tác dọn cũ không có revision tin cậy, nên chỉ dọn bài và giữ bản nháp.
@DeleteColumn(tableName = "deferred_channel_post_cleanup", columnName = "draftUpdatedAtMillis")
class RemoveDeferredCleanupTimestamp : AutoMigrationSpec
