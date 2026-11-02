package com.lttbdd.socialagent.core.model

/**
 * Các mạng xã hội app hỗ trợ.
 * [maxChars] là giới hạn ký tự của một bài đăng, dùng cho bộ đếm ở F4.
 */
enum class ChannelType(val maxChars: Int) {
    FACEBOOK(maxChars = 63_206),
    LINKEDIN(maxChars = 3_000),
}

/** Chức năng mà một kênh hỗ trợ — hiển thị trên màn "Kênh của tôi" (F1). */
enum class ChannelCapability { PUBLISH, READ_COMMENTS, REPLY, MENTION, METRICS }

enum class ConnectionStatus { CONNECTED, TOKEN_EXPIRED, DISCONNECTED }

/** Một kênh người dùng đã kết nối (ví dụ một Facebook Page). */
data class ChannelAccount(
    val type: ChannelType,
    val accountId: String,
    val displayName: String,
    val status: ConnectionStatus,
    val capabilities: Set<ChannelCapability>,
)
