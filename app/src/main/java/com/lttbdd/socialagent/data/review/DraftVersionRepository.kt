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
     * Khôi phục nội dung của [draftId] về phiên bản [versionId] trong một giao dịch Room để
     * tuần tự hóa với luồng `enqueue` của hàng đợi F5, tránh race condition giữa kiểm tra bài đang đăng
     * và cập nhật bản nháp. Luôn đi qua [DraftReviewRepository.restoreDraftContent] để thu hồi
     * `approved = false` qua [DraftApprovalGuard].
     */
    suspend fun restoreVersion(draftId: String, versionId: String): DraftRestoreResult
}

@Singleton
class DefaultDraftVersionRepository @Inject constructor(
    private val database: AppDatabase,
    private val draftDao: DraftDao,
    private val postDao: PostDao,
    private val draftReviewRepository: DraftReviewRepository,
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
                    // Trả DraftNotFound nếu bản nháp không tồn tại, ngược lại VersionNotFound.
                    return if (draftDao.getById(draftId) == null) {
                        DraftRestoreResult.DraftNotFound
                    } else {
                        DraftRestoreResult.VersionNotFound
                    }
                }

            // Bọc kiểm tra bài đăng hoạt động và cập nhật bản nháp trong cùng giao dịch SQLite
            // để tuần tự hóa với PublishQueueRepository.enqueue.
            database.withTransaction {
                draftDao.getById(draftId) ?: return@withTransaction DraftRestoreResult.DraftNotFound
                if (hasActivePublishingPosts(draftId)) {
                    return@withTransaction DraftRestoreResult.BlockedByActivePublish
                }
                when (val updateResult = draftReviewRepository.restoreDraftContent(draftId, targetVersion.text)) {
                    is DraftContentUpdateResult.Updated -> {
                        val latestVersion = draftVersionStore.getVersions(draftId).firstOrNull() ?: targetVersion
                        DraftRestoreResult.Restored(updateResult.draft, latestVersion)
                    }
                    is DraftContentUpdateResult.UpdatedWithoutHistory -> {
                        DraftRestoreResult.RestoredWithoutHistory(updateResult.draft, updateResult.cause)
                    }
                    DraftContentUpdateResult.NotFound -> DraftRestoreResult.DraftNotFound
                    DraftContentUpdateResult.EmptyContent -> DraftRestoreResult.VersionNotFound
                    is DraftContentUpdateResult.StorageError -> DraftRestoreResult.StorageError(updateResult.cause)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            DraftRestoreResult.StorageError(e)
        }
    }
}
