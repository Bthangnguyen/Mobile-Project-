package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import com.lttbdd.socialagent.core.database.entity.ChannelCleanupReceipt
import com.lttbdd.socialagent.core.model.ChannelType

@Dao
interface ChannelCleanupDao {
    @Query(
        """
        INSERT OR IGNORE INTO deferred_channel_post_cleanup (postId, draftRevision)
        SELECT posts.id, drafts.revision FROM posts
        LEFT JOIN drafts ON drafts.id = posts.draftId
        WHERE posts.channel = :channel AND (posts.status = 'PUBLISHING' OR posts.outcomeUnknown = 1)
        """,
    )
    suspend fun deferProtectedPosts(channel: ChannelType)

    @Query(
        """
        DELETE FROM drafts
        WHERE EXISTS (
            SELECT 1 FROM posts JOIN deferred_channel_post_cleanup ON postId = posts.id
            WHERE posts.draftId = drafts.id AND status != 'PUBLISHING' AND outcomeUnknown = 0
              AND draftRevision = drafts.revision
        ) AND NOT EXISTS (
            SELECT 1 FROM posts LEFT JOIN deferred_channel_post_cleanup ON postId = posts.id
            WHERE posts.draftId = drafts.id AND (postId IS NULL OR status = 'PUBLISHING' OR outcomeUnknown = 1)
        )
        """,
    )
    suspend fun deleteResolvedDrafts()

    @Query(
        """
        DELETE FROM posts
        WHERE id IN (SELECT postId FROM deferred_channel_post_cleanup)
          AND status != 'PUBLISHING' AND outcomeUnknown = 0
        """,
    )
    suspend fun deleteResolvedPosts()

    @Transaction
    suspend fun finishDeferredCleanup() {
        // Xoá bản nháp trước để còn biết bài nào đang tham chiếu tới nó.
        deleteResolvedDrafts()
        deleteResolvedPosts()
    }

    @Query("DELETE FROM deferred_channel_post_cleanup WHERE postId = :postId")
    suspend fun retainPostForExplicitRetry(postId: String)


    @Query("SELECT operationId FROM channel_cleanup_receipts WHERE channel = :channel")
    suspend fun getCompletedOperation(channel: ChannelType): String?

    @Upsert
    suspend fun recordCompletion(receipt: ChannelCleanupReceipt)
}
