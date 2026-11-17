package com.lttbdd.socialagent.data.model

/**
 * Một Facebook Page mà người dùng quản lý (từ Graph API `/me/accounts`).
 * [accessToken] là Page access token dùng để đăng bài, đọc/trả lời comment và lấy số liệu.
 * Không bao giờ in token ra log: [toString] chỉ gồm id và tên.
 */
data class FacebookPage(
    val id: String,
    val name: String,
    val accessToken: String,
    /** Page task của người dùng trên Page này (CREATE_CONTENT, MODERATE, ANALYZE...); null nếu Graph API không trả `tasks`. */
    val tasks: List<String>? = null,
) {
    override fun toString(): String = "FacebookPage(id=$id, name=$name)"
}
