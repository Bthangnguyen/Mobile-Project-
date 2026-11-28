package com.lttbdd.socialagent.data.review

/** Phân loại cảnh báo nội dung trước khi đăng bài (F4, Issue #65). */
enum class PrePublishWarningType {
    /** Số liệu, tỉ lệ phần trăm hoặc thống kê chưa có nguồn trích dẫn. */
    UNSOURCED_STATISTIC,

    /** Nội dung có dấu hiệu chứa thông tin cá nhân (email, số điện thoại, CCCD/tài khoản, số thẻ). */
    PERSONAL_INFO,

    /** Lời lẽ nhạy cảm, công kích hoặc cam kết tuyệt đối dễ vi phạm chính sách nền tảng. */
    SENSITIVE_LANGUAGE,
}

/** Mức độ ưu tiên của cảnh báo trước khi đăng. */
enum class WarningSeverity {
    MEDIUM,
    HIGH,
}

/**
 * Một cảnh báo cụ thể phát hiện trong nội dung bản nháp.
 * Với [PrePublishWarningType.PERSONAL_INFO], [matchedSnippet] luôn được che mờ để tránh rò rỉ dữ liệu nhạy cảm.
 */
data class PrePublishWarning(
    val type: PrePublishWarningType,
    val severity: WarningSeverity,
    val matchedSnippet: String,
)
