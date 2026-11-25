package com.lttbdd.socialagent.data.review

import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.toEntity
import com.lttbdd.socialagent.core.database.toModel
import com.lttbdd.socialagent.core.model.Draft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Kết quả phê duyệt bản nháp gắn với phiên bản nội dung người dùng đã xem. */
sealed interface DraftApprovalResult {
    data class Approved(val draft: Draft) : DraftApprovalResult
    /** Nội dung bản nháp trong DB đã thay đổi so với nội dung người dùng đã xem trên màn hình. */
    data object ContentChanged : DraftApprovalResult
    data object EmptyContent : DraftApprovalResult
    data object NotFound : DraftApprovalResult
    data class StorageError(val cause: Throwable) : DraftApprovalResult
}

/** Kết quả cập nhật nội dung bản nháp (luôn tự động thu hồi trạng thái duyệt). */
sealed interface DraftContentUpdateResult {
    data class Updated(val draft: Draft) : DraftContentUpdateResult
    data object EmptyContent : DraftContentUpdateResult
    data object NotFound : DraftContentUpdateResult
    data class StorageError(val cause: Throwable) : DraftContentUpdateResult
}

/** Kết quả thu hồi trạng thái duyệt bản nháp. */
sealed interface DraftRevokeResult {
    data class Revoked(val draft: Draft) : DraftRevokeResult
    data object NotFound : DraftRevokeResult
    data class StorageError(val cause: Throwable) : DraftRevokeResult
}

/**
 * Kết quả cổng kiểm duyệt trước khi bàn giao bản nháp sang hàng đợi đăng bài (F5).
 */
sealed interface PublishGateResult {
    data class ReadyToPublish(val draft: Draft) : PublishGateResult
    data class BlockedUnapproved(val draft: Draft) : PublishGateResult
    data class BlockedEmptyContent(val draft: Draft) : PublishGateResult
    data object NotFound : PublishGateResult
    data class StorageError(val cause: Throwable) : PublishGateResult
}

/**
 * Kho lưu trữ và cổng phê duyệt bản nháp (F4, Issue #33).
 *
 * Quy ước bắt buộc: mọi nguồn ghi bản nháp (agent F2 qua [saveAgentDraft], sửa tay F4 qua
 * [updateDraftContent]) phải đi qua repository này thay vì gọi `DraftDao.upsert` trực tiếp với
 * `approved = true`.
 */
interface DraftReviewRepository {

    /** Quan sát toàn bộ bản nháp cục bộ (offline-first). */
    fun observeDrafts(): Flow<List<Draft>>

    /**
     * Lưu bản nháp do AI Agent tạo ra. Luôn đảm bảo `approved = false` qua [DraftApprovalGuard]
     * để agent không bao giờ tự đăng bài chưa qua duyệt.
     */
    suspend fun saveAgentDraft(draft: Draft): Result<Draft>

    /**
     * Cập nhật nội dung bản nháp và đồng thời thu hồi trạng thái duyệt (`approved = false`)
     * qua [DraftApprovalGuard.onDraftContentEdited] trên bản ghi mới nhất từ DB.
     */
    suspend fun updateDraftContent(draftId: String, newText: String): DraftContentUpdateResult

    /**
     * Người dùng phê duyệt tường minh bản nháp.
     * Chỉ chấp nhận khi nội dung hiện tại trong DB vẫn khớp đúng [expectedText] mà người dùng đã xem;
     * nếu nội dung đã đổi thì trả [DraftApprovalResult.ContentChanged] để yêu cầu xem lại.
     */
    suspend fun approveDraft(draftId: String, expectedText: String): DraftApprovalResult

    /** Người dùng thu hồi trạng thái duyệt của bản nháp. */
    suspend fun revokeApproval(draftId: String): DraftRevokeResult

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

    override suspend fun saveAgentDraft(draft: Draft): Result<Draft> = try {
        val unapprovedDraft = DraftApprovalGuard.sanitizeAgentDraft(draft)
        draftDao.upsert(unapprovedDraft.toEntity(updatedAtMillis = System.currentTimeMillis()))
        Result.success(unapprovedDraft)
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun updateDraftContent(
        draftId: String,
        newText: String,
    ): DraftContentUpdateResult = try {
        if (newText.isBlank()) {
            DraftContentUpdateResult.EmptyContent
        } else {
            val freshEntity = draftDao.getById(draftId)
            if (freshEntity == null) {
                DraftContentUpdateResult.NotFound
            } else {
                val editedModel = DraftApprovalGuard.onDraftContentEdited(
                    freshEntity.toModel().copy(text = newText),
                )
                val updatedEntity = freshEntity.copy(
                    text = editedModel.text,
                    approved = editedModel.approved,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                draftDao.upsert(updatedEntity)
                DraftContentUpdateResult.Updated(updatedEntity.toModel())
            }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        DraftContentUpdateResult.StorageError(e)
    }

    override suspend fun approveDraft(
        draftId: String,
        expectedText: String,
    ): DraftApprovalResult = try {
        val freshEntity = draftDao.getById(draftId)
        when {
            freshEntity == null -> DraftApprovalResult.NotFound
            freshEntity.text != expectedText -> DraftApprovalResult.ContentChanged
            freshEntity.text.isBlank() -> DraftApprovalResult.EmptyContent
            else -> {
                val approvedEntity = freshEntity.copy(
                    approved = true,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                draftDao.upsert(approvedEntity)
                DraftApprovalResult.Approved(approvedEntity.toModel())
            }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        DraftApprovalResult.StorageError(e)
    }

    override suspend fun revokeApproval(draftId: String): DraftRevokeResult = try {
        val freshEntity = draftDao.getById(draftId)
        if (freshEntity == null) {
            DraftRevokeResult.NotFound
        } else {
            val revokedEntity = freshEntity.copy(
                approved = false,
                updatedAtMillis = System.currentTimeMillis(),
            )
            draftDao.upsert(revokedEntity)
            DraftRevokeResult.Revoked(revokedEntity.toModel())
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        DraftRevokeResult.StorageError(e)
    }

    override suspend fun requestPublish(draftId: String): PublishGateResult = try {
        val draft = draftDao.getById(draftId)?.toModel()
        if (draft == null) {
            PublishGateResult.NotFound
        } else {
            when (DraftApprovalGuard.checkPublishEligibility(draft)) {
                PublishEligibility.Allowed -> PublishGateResult.ReadyToPublish(draft)
                PublishEligibility.BlockedNotApproved -> PublishGateResult.BlockedUnapproved(draft)
                PublishEligibility.BlockedEmptyContent -> PublishGateResult.BlockedEmptyContent(draft)
            }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        PublishGateResult.StorageError(e)
    }
}
