package com.lttbdd.socialagent.data.review

import kotlinx.serialization.Serializable

/** Nguồn gốc tạo ra một phiên bản bản nháp (F4, Issue #62). */
@Serializable
enum class DraftVersionSource {
    /** Bản chụp nội dung sẵn có trước lần chỉnh sửa đầu tiên. */
    INITIAL,

    /** Bản nháp do AI Agent tạo ra. */
    AI_GENERATED,

    /** Nội dung do người dùng chỉnh sửa thủ công. */
    MANUAL_EDIT,

    /** Phiên bản được khôi phục từ một phiên bản cũ hơn. */
    RESTORED,
}

/**
 * Một phiên bản lịch sử bất biến của bản nháp (F4, Issue #62).
 */
@Serializable
data class DraftVersion(
    val id: String,
    val draftId: String,
    val versionNumber: Int,
    val text: String,
    val source: DraftVersionSource,
    val createdAtMillis: Long,
)
