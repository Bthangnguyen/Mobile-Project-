package com.lttbdd.socialagent.feature.review

import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.toEntity
import com.lttbdd.socialagent.core.database.toModel
import com.lttbdd.socialagent.core.model.Draft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kết quả cổng kiểm duyệt trước khi bàn giao bản nháp sang hàng đợi đăng bài (F5).
 */
sealed interface PublishGateResult {
    data class ReadyToPublish(val draft: Draft) : PublishGateResult
    data class BlockedUnapproved(val draft: Draft) : PublishGateResult
    data class BlockedEmptyContent(val draft: Draft) : PublishGateResult
    data object NotFound : PublishGateResult
}

/**
 * Kho lưu trữ và cổng phê duyệt bản nháp (F4, Issue #33).
 */
interface DraftReviewRepository {

    /** Quan sát toàn bộ bản nháp cục bộ (offline-first). */
    fun observeDrafts(): Flow<List<Draft>>

    /**
     * Lưu bản nháp do AI Agent tạo ra. Luôn đảm bảo `approved = false` qua [DraftApprovalGuard]
     * để agent không bao giờ tự đăng bài chưa qua duyệt.
     */
    suspend fun saveAgentDraft(draft: Draft): Draft

    /** Người dùng phê duyệt tường minh bản nháp trước khi cho phép đăng. */
    suspend fun approveDraft(draftId: String): Result<Draft>

    /** Người dùng thu hồi trạng thái duyệt của bản nháp. */
    suspend fun revokeApproval(draftId: String): Result<Draft>

    /**
     * Cổng chặn cứng trước khi đăng bài (F5): luôn đọc lại trạng thái thật từ DB,
     * chỉ trả [PublishGateResult.ReadyToPublish] khi bản nháp đã được duyệt và có nội dung hợp lệ.
     */
    suspend fun requestPublish(draftId: String): PublishGateResult
}

@Singleton
class RoomDraftReviewRepository @Inject constructor(
    private val draftDao: DraftDao,
) : DraftReviewRepository {

    override fun observeDrafts(): Flow<List<Draft>> =
        draftDao.observeAll().map { entities -> entities.map { it.toModel() } }

    override suspend fun saveAgentDraft(draft: Draft): Draft {
        val unapprovedDraft = DraftApprovalGuard.sanitizeAgentDraft(draft)
        draftDao.upsert(unapprovedDraft.toEntity(updatedAtMillis = System.currentTimeMillis()))
        return unapprovedDraft
    }

    override suspend fun approveDraft(draftId: String): Result<Draft> {
        val current = draftDao.getById(draftId)?.toModel()
            ?: return Result.failure(NoSuchElementException("Draft $draftId not found"))
        if (current.text.isBlank()) {
            return Result.failure(IllegalStateException("Cannot approve draft with empty content"))
        }
        val approved = current.copy(approved = true)
        draftDao.upsert(approved.toEntity(updatedAtMillis = System.currentTimeMillis()))
        return Result.success(approved)
    }

    override suspend fun revokeApproval(draftId: String): Result<Draft> {
        val current = draftDao.getById(draftId)?.toModel()
            ?: return Result.failure(NoSuchElementException("Draft $draftId not found"))
        val revoked = current.copy(approved = false)
        draftDao.upsert(revoked.toEntity(updatedAtMillis = System.currentTimeMillis()))
        return Result.success(revoked)
    }

    override suspend fun requestPublish(draftId: String): PublishGateResult {
        val draft = draftDao.getById(draftId)?.toModel() ?: return PublishGateResult.NotFound
        return when (DraftApprovalGuard.checkPublishEligibility(draft)) {
            PublishEligibility.Allowed -> PublishGateResult.ReadyToPublish(draft)
            PublishEligibility.BlockedNotApproved -> PublishGateResult.BlockedUnapproved(draft)
            PublishEligibility.BlockedEmptyContent -> PublishGateResult.BlockedEmptyContent(draft)
        }
    }
}
