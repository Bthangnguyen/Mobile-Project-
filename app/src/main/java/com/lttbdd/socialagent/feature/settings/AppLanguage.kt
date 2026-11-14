package com.lttbdd.socialagent.feature.settings

/** Ngôn ngữ giao diện người dùng chọn trong Cài đặt (F9). [tag] là BCP 47; rỗng nghĩa là theo hệ thống. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    VIETNAMESE("vi"),
    ENGLISH("en"),
    ;

    companion object {
        /** Chọn ngôn ngữ từ danh sách tag đang áp dụng, ví dụ "vi" hoặc "en-US"; không khớp thì theo hệ thống. */
        fun fromTags(tags: String): AppLanguage {
            val primary = tags.substringBefore(',').substringBefore('-').lowercase()
            return entries.firstOrNull { it != SYSTEM && it.tag == primary } ?: SYSTEM
        }
    }
}
