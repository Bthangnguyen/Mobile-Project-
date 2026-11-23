package com.lttbdd.socialagent.feature.review

import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.toEntity
import com.lttbdd.socialagent.core.database.toModel
import com.lttbdd.socialagent.core.model.Draft
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kho dữ liệu đọc và lưu bản nháp đã chỉnh sửa thủ công (F4, Issue #34).
 */
interface DraftEditorRepository {

    suspend fun getDraft(draftId: String): Draft?

    suspend fun getLatestDraft(): Draft?

    /**
     * Lưu nội dung người dùng sửa tay vào DB cục bộ.
     * Chặn nội dung rỗng hoặc vượt quá [com.lttbdd.socialagent.core.model.ChannelType.maxChars],
     * và luôn đặt lại `approved = false` khi nội dung thay đổi (quy tắc 6).
     */
    suspend fun saveEditedDraft(draft: Draft, newText: String): Result<Draft>
}

@Singleton
class RoomDraftEditorRepository @Inject constructor(
    private val draftDao: DraftDao,
) : DraftEditorRepository {

    override suspend fun getDraft(draftId: String): Draft? =
        draftDao.getById(draftId)?.toModel()

    override suspend fun getLatestDraft(): Draft? =
        draftDao.observeAll().first().firstOrNull()?.toModel()

    override suspend fun saveEditedDraft(draft: Draft, newText: String): Result<Draft> {
        if (newText.isBlank()) {
            return Result.failure(IllegalArgumentException("Draft text cannot be blank"))
        }
        if (DraftCharacterCounter.evaluate(newText, draft.channel).isOverLimit) {
            return Result.failure(IllegalArgumentException("Draft text exceeds channel limit"))
        }
        val updated = draft.copy(
            text = newText,
            approved = false,
        )
        draftDao.upsert(updated.toEntity(updatedAtMillis = System.currentTimeMillis()))
        return Result.success(updated)
    }
}
