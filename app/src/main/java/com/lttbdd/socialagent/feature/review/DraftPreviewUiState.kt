package com.lttbdd.socialagent.feature.review

import androidx.annotation.StringRes
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.data.review.PrePublishWarning

/**
 * Trạng thái UI cho màn hình xem trước theo kênh, sửa tay và đếm ký tự (F4, Issue #34).
 * [selectedChannel] chỉ phục vụ xem trước giao diện và bộ đếm mô phỏng; việc kiểm tra giới hạn lưu
 * luôn dựa trên kênh thật của [draft] (`draft.channel`).
 */
data class DraftPreviewUiState(
    val isLoading: Boolean = true,
    val isDraftNotFound: Boolean = false,
    val isEditingLockedByPublishing: Boolean = false,
    val draft: Draft? = null,
    val selectedChannel: ChannelType = ChannelType.FACEBOOK,
    val editedText: String = "",
    val characterCount: CharacterCountStatus = DraftCharacterCounter.evaluate("", ChannelType.FACEBOOK),
    val hasUnsavedChanges: Boolean = false,
    val canSave: Boolean = false,
    @param:StringRes val textErrorRes: Int? = null,
    val prePublishWarnings: List<PrePublishWarning> = emptyList(),
)

/** Sự kiện dùng một lần phát từ [DraftPreviewViewModel] sang [DraftPreviewFragment]. */
sealed interface DraftPreviewEvent {
    data class ShowMessage(@param:StringRes val messageRes: Int) : DraftPreviewEvent
}
