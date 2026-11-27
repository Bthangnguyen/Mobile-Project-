package com.lttbdd.socialagent.feature.review

import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.dao.PostDao
import com.lttbdd.socialagent.core.database.toModel
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.data.review.DraftContentUpdateResult
import com.lttbdd.socialagent.data.review.DraftReviewRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Kết quả lưu bản nháp đã chỉnh sửa thủ công. */
sealed interface DraftSaveResult {
    data class Saved(val draft: Draft) : DraftSaveResult
    data object NotFound : DraftSaveResult
    data object BlockedBlankContent : DraftSaveResult
    data object BlockedOverLimit : DraftSaveResult
    data object BlockedByActivePublish : DraftSaveResult
    data class StorageError(val cause: Throwable) : DraftSaveResult
}

/**
 * Kho dữ liệu đọc và lưu bản nháp đã chỉnh sửa thủ công (F4, Issue #34).
 */
interface DraftEditorRepository {

    suspend fun getDraft(draftId: String): Draft?

    /** Kiểm tra bản nháp có bài đăng đang chờ ([PostStatus.QUEUED]) hoặc đang đăng ([PostStatus.PUBLISHING]). */
    suspend fun hasActivePublishingPosts(draftId: String): Boolean

    /**
     * Lưu nội dung người dùng sửa tay vào DB cục bộ theo đúng [draftId].
     * Thực hiện kiểm tra riêng của #34 (chặn khi có bài đang đăng, rỗng, vượt giới hạn theo `draft.channel`)
     * rồi ủy quyền ghi và thu hồi duyệt qua [DraftReviewRepository.updateDraftContent] (#33).
     */
    suspend fun saveEditedDraft(draftId: String, newText: String): DraftSaveResult
}

@Singleton
class RoomDraftEditorRepository @Inject constructor(
    private val draftDao: DraftDao,
    private val postDao: PostDao,
    private val draftReviewRepository: DraftReviewRepository,
) : DraftEditorRepository {

    override suspend fun getDraft(draftId: String): Draft? =
        draftDao.getById(draftId)?.toModel()

    override suspend fun hasActivePublishingPosts(draftId: String): Boolean =
        postDao.observeByDraft(draftId).first().any { post ->
            post.status == PostStatus.QUEUED || post.status == PostStatus.PUBLISHING
        }

    override suspend fun saveEditedDraft(draftId: String, newText: String): DraftSaveResult {
        return try {
            val freshEntity = draftDao.getById(draftId) ?: return DraftSaveResult.NotFound
            if (hasActivePublishingPosts(draftId)) {
                return DraftSaveResult.BlockedByActivePublish
            }
            if (newText.isBlank()) {
                return DraftSaveResult.BlockedBlankContent
            }
            if (DraftCharacterCounter.evaluate(newText, freshEntity.channel).isOverLimit) {
                return DraftSaveResult.BlockedOverLimit
            }
            when (val updateResult = draftReviewRepository.updateDraftContent(draftId, newText)) {
                is DraftContentUpdateResult.Updated -> DraftSaveResult.Saved(updateResult.draft)
                is DraftContentUpdateResult.UpdatedWithoutHistory -> DraftSaveResult.Saved(updateResult.draft)
                DraftContentUpdateResult.EmptyContent -> DraftSaveResult.BlockedBlankContent
                DraftContentUpdateResult.NotFound -> DraftSaveResult.NotFound
                is DraftContentUpdateResult.StorageError -> DraftSaveResult.StorageError(updateResult.cause)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            DraftSaveResult.StorageError(e)
        }
    }
}
