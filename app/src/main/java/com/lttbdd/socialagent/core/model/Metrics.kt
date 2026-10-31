package com.lttbdd.socialagent.core.model

/** Số liệu của một bài tại một thời điểm (F7). Lưu nhiều snapshot để vẽ đường tăng trưởng. */
data class PostMetrics(
    val channel: ChannelType,
    val remotePostId: String,
    val likes: Int,
    val comments: Int,
    val shares: Int,
    /** null khi API của kênh không trả về lượt hiển thị. */
    val impressions: Int?,
    val capturedAtMillis: Long,
) {
    /** Tỉ lệ tương tác = (thích + comment + chia sẻ) / lượt hiển thị. */
    val engagementRate: Double?
        get() = impressions?.takeIf { it > 0 }?.let { (likes + comments + shares).toDouble() / it }
}
