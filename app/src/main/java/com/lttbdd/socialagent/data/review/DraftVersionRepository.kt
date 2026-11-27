package com.lttbdd.socialagent.data.review

import androidx.room.withTransaction
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.dao.PostDao
import com.lttbdd.socialagent.core.database.toModel
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PostStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Kết quả khôi phục một phiên bản lịch sử của bản nháp (F4, Issue #62). */
sealed interface DraftRestoreResult {
    data class Restored(val draft: Draft, val version: DraftVersion) : DraftRestoreResult
    /** Nội dung bản nháp đã được khôi phục trong Room và thu hồi duyệt, nhưng ghi mục lịch sử mới thất bại. */
    data class RestoredWithoutHistory(val draft: Draft, val cause: Throwable) : DraftRestoreResult
    data object DraftNotFound : DraftRestoreResult
    data object VersionNotFound : DraftRestoreResult
    data object BlockedByActivePublish : DraftRestoreResult
    data class StorageError(val cause: Throwable) : DraftRestoreResult
}

/**
 * Kho nghiệp vụ đọc lịch sử phiên bản và khôi phục bản nháp (F4, Issue #62).
 */
interface DraftVersionRepository {

    fun observeVersions(draftId: String): Flow<List<DraftVersion>>

    suspend fun getDraft(draftId: String): Draft?

    /** Kiểm tra bản nháp có bài đang chờ, đang đăng hoặc đã bàn giao ([PostStatus.QUEUED]/[PostStatus.PUBLISHING]/[PostStatus.HANDED_OFF]). */
    suspend fun hasActivePublishingPosts(draftId: String): Boolean

    /**
     * Khôi phục nội dung của [draftId] về phiên bản [versionId] theo cơ chế đồng bộ 2 bước:
     * 1. Trong một giao dịch Room (`database.withTransaction`): kiểm tra bài đang đăng hoạt động và
     *    cập nhật bản nháp qua [DraftApprovalGuard.onDraftContentEdited] (thu hồi `approved = false`),
     *    không thực hiện ghi tệp bên trong transaction để tránh lịch sử bị lệch khi Room rollback.
     * 2. Sau khi Room transaction đã commit thành công mới ghi mục `RESTORED` vào [DraftVersionStore].
     */
    suspend fun restoreVersion(draftId: String, versionId: String): DraftRestoreResult
}

@Singleton
class DefaultDraftVersionRepository @Inject constructor(
    private val database: AppDatabase,
    private val draftDao: DraftDao,
    private val postDao: PostDao,
    private val draftVersionStore: DraftVersionStore,
) : DraftVersionRepository {

    override fun observeVersions(draftId: String): Flow<List<DraftVersion>> =
        draftVersionStore.observeVersions(draftId)

    override suspend fun getDraft(draftId: String): Draft? =
        draftDao.getById(draftId)?.toModel()

    override suspend fun hasActivePublishingPosts(draftId: String): Boolean =
        postDao.observeByDraft(draftId).first().any { post ->
            post.status == PostStatus.QUEUED ||
                post.status == PostStatus.PUBLISHING ||
                post.status == PostStatus.HANDED_OFF
        }

    override suspend fun restoreVersion(draftId: String, versionId: String): DraftRestoreResult {
        return try {
            val targetVersion = draftVersionStore.getVersions(draftId)
                .firstOrNull { it.id == versionId }
                ?: run {
                    return if (draftDao.getById(draftId) == null) {
                        DraftRestoreResult.DraftNotFound
                    } else {
                        DraftRestoreResult.VersionNotFound
                    }
                }
            if (targetVersion.text.isBlank()) {
                return DraftRestoreResult.VersionNotFound
            }

            // Bước 1: Chỉ chạy thao tác Room trong transaction (tuần tự hóa với PublishQueueRepository.enqueue).
            // Tuyệt đối không ghi tệp JSON trong transaction để tránh tạo bản RESTORED giả nếu Room rollback.
            val now = System.currentTimeMillis()
            val dbPhaseResult = database.withTransaction {
                val freshEntity = draftDao.getById(draftId) ?: return@withTransaction DbRestorePhase.DraftNotFound
                if (hasActivePublishingPosts(draftId)) {
                    return@withTransaction DbRestorePhase.BlockedByActivePublish
                }
                val editedModel = DraftApprovalGuard.onDraftContentEdited(
                    freshEntity.toModel().copy(text = targetVersion.text),
                )
                val updatedEntity = freshEntity.copy(
                    text = editedModel.text,
                    approved = editedModel.approved,
                    updatedAtMillis = now,
                )
                draftDao.upsert(updatedEntity)
                DbRestorePhase.Committed(updatedEntity.toModel())
            }

            val committedDraft = when (dbPhaseResult) {
                DbRestorePhase.DraftNotFound -> return DraftRestoreResult.DraftNotFound
                DbRestorePhase.BlockedByActivePublish -> return DraftRestoreResult.BlockedByActivePublish
                is DbRestorePhase.Committed -> dbPhaseResult.draft
            }

            // Bước 2: Room transaction đã commit chắc chắn → mới ghi phiên bản RESTORED vào tệp lịch sử.
            try {
                val recorded = draftVersionStore.recordVersion(
                    draftId = draftId,
                    text = committedDraft.text,
                    source = DraftVersionSource.RESTORED,
                    createdAtMillis = now,
                )
                DraftRestoreResult.Restored(committedDraft, recorded)
            } catch (ce: CancellationException) {
                throw ce
            } catch (historyError: Exception) {
                DraftRestoreResult.RestoredWithoutHistory(committedDraft, historyError)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            DraftRestoreResult.StorageError(e)
        }
    }

    private sealed interface DbRestorePhase {
        data class Committed(val draft: Draft) : DbRestorePhase
        data object DraftNotFound : DbRestorePhase
        data object BlockedByActivePublish : DbRestorePhase
    }
}
