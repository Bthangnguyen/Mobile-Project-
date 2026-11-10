package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType

enum class CapabilityStatusType {
    SUPPORTED,
    REQUIRES_PERMISSION,
    NOT_SUPPORTED,
    UNVERIFIED,

    /** Không làm được qua API với app này: app thông báo và người dùng tự thao tác (HANDED_OFF). */
    HANDED_OFF,
}

data class CapabilityItemUiState(
    val capabilityNameRes: Int,
    val statusTextRes: Int,
    val statusType: CapabilityStatusType,
    val captionRes: Int,
)

/**
 * Lớp đánh giá ma trận 5 khả năng (Publish, Read Comments, Reply, Mention, Metrics) cho từng kênh.
 * Nguồn tham chiếu kỹ thuật chính thức:
 * - Meta Pages API Posts: https://developers.facebook.com/documentation/pages-api/posts
 * - LinkedIn Posts API: https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/posts-api
 * - LinkedIn Comments API: https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/comments-api
 */
object ChannelCapabilityEvaluator {

    /**
     * Tách chuỗi quyền, chấp nhận cả dấu phẩy (Facebook) lẫn khoảng trắng (LinkedIn/OAuth 2.0).
     */
    internal fun parseScopes(scopeString: String?): Set<String> =
        scopeString.orEmpty().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.toSet()

    fun evaluateCapabilities(
        account: ChannelAccount,
        scopeString: String?,
    ): List<CapabilityItemUiState> {
        val scopes = parseScopes(scopeString)

        return when (account.type) {
            ChannelType.FACEBOOK -> evaluateFacebook(scopes)
            ChannelType.LINKEDIN -> evaluateLinkedIn(scopes)
        }
    }

    private fun evaluateFacebook(scopes: Set<String>): List<CapabilityItemUiState> {
        // 1. Đăng bài (Publish) - Quyền yêu cầu: pages_manage_posts
        val publishState = if (scopes.contains("pages_manage_posts")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_publish,
                statusTextRes = R.string.cap_status_supported,
                statusType = CapabilityStatusType.SUPPORTED,
                captionRes = R.string.cap_fb_publish_ok,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_publish,
                statusTextRes = R.string.cap_status_requires_perm,
                statusType = CapabilityStatusType.REQUIRES_PERMISSION,
                captionRes = R.string.cap_fb_publish_no_perm,
            )
        }

        // 2. Đọc comment (Read Comments) - Quyền yêu cầu: pages_read_engagement hoặc pages_read_user_content
        val readCommentsState = if (scopes.contains("pages_read_engagement") || scopes.contains("pages_read_user_content")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_read_comments,
                statusTextRes = R.string.cap_status_supported,
                statusType = CapabilityStatusType.SUPPORTED,
                captionRes = R.string.cap_fb_read_comments_ok,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_read_comments,
                statusTextRes = R.string.cap_status_requires_perm,
                statusType = CapabilityStatusType.REQUIRES_PERMISSION,
                captionRes = R.string.cap_fb_read_comments_no_perm,
            )
        }

        // 3. Trả lời comment (Reply) - Quyền yêu cầu: pages_manage_engagement
        val replyState = if (scopes.contains("pages_manage_engagement")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_reply,
                statusTextRes = R.string.cap_status_supported,
                statusType = CapabilityStatusType.SUPPORTED,
                captionRes = R.string.cap_fb_reply_ok,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_reply,
                statusTextRes = R.string.cap_status_requires_perm,
                statusType = CapabilityStatusType.REQUIRES_PERMISSION,
                captionRes = R.string.cap_fb_reply_no_perm,
            )
        }

        // 4. Mention (Nhắc tên): tài liệu Meta chỉ mô tả mention trong bình luận khi Page bật Page Mentioning,
        // nên chưa xác minh được thì bàn giao thủ công (HANDED_OFF); không tính public_profile.
        val mentionState = if (scopes.contains("pages_manage_posts") || scopes.contains("pages_manage_engagement")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_mention,
                statusTextRes = R.string.cap_status_handed_off,
                statusType = CapabilityStatusType.HANDED_OFF,
                captionRes = R.string.cap_fb_mention_handed_off,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_mention,
                statusTextRes = R.string.cap_status_requires_perm,
                statusType = CapabilityStatusType.REQUIRES_PERMISSION,
                captionRes = R.string.cap_fb_mention_page_no_perm,
            )
        }

        // 5. Số liệu (Metrics) - Quyền yêu cầu: read_insights
        val metricsState = if (scopes.contains("read_insights")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_metrics,
                statusTextRes = R.string.cap_status_supported,
                statusType = CapabilityStatusType.SUPPORTED,
                captionRes = R.string.cap_fb_metrics_ok,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_metrics,
                statusTextRes = R.string.cap_status_requires_perm,
                statusType = CapabilityStatusType.REQUIRES_PERMISSION,
                captionRes = R.string.cap_fb_metrics_no_perm,
            )
        }

        return listOf(publishState, readCommentsState, replyState, mentionState, metricsState)
    }

    private fun evaluateLinkedIn(scopes: Set<String>): List<CapabilityItemUiState> {
        // 1. Đăng bài (Publish) - Quyền yêu cầu: w_organization_social hoặc w_member_social
        val publishState = if (scopes.contains("w_organization_social") || scopes.contains("w_member_social")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_publish,
                statusTextRes = R.string.cap_status_supported,
                statusType = CapabilityStatusType.SUPPORTED,
                captionRes = R.string.cap_li_publish_ok,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_publish,
                statusTextRes = R.string.cap_status_requires_perm,
                statusType = CapabilityStatusType.REQUIRES_PERMISSION,
                captionRes = R.string.cap_li_publish_no_perm,
            )
        }

        // Các quyền đọc/trả lời comment và số liệu của LinkedIn chỉ có khi app được duyệt Community Management API
        // (hoặc cấp riêng cho "select developers"); app không tự xin thêm được nên thiếu quyền thì bàn giao thủ công.
        // 2. Đọc comment (Read Comments) - Quyền yêu cầu: r_organization_social_feed hoặc r_member_social_feed
        val readCommentsState = if (scopes.contains("r_organization_social_feed") || scopes.contains("r_member_social_feed")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_read_comments,
                statusTextRes = R.string.cap_status_supported,
                statusType = CapabilityStatusType.SUPPORTED,
                captionRes = R.string.cap_li_read_comments_ok,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_read_comments,
                statusTextRes = R.string.cap_status_handed_off,
                statusType = CapabilityStatusType.HANDED_OFF,
                captionRes = R.string.cap_li_read_comments_handed_off,
            )
        }

        // 3. Trả lời comment (Reply) - Quyền yêu cầu: w_organization_social_feed hoặc w_member_social_feed
        val replyState = if (scopes.contains("w_organization_social_feed") || scopes.contains("w_member_social_feed")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_reply,
                statusTextRes = R.string.cap_status_supported,
                statusType = CapabilityStatusType.SUPPORTED,
                captionRes = R.string.cap_li_reply_ok,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_reply,
                statusTextRes = R.string.cap_status_handed_off,
                statusType = CapabilityStatusType.HANDED_OFF,
                captionRes = R.string.cap_li_reply_handed_off,
            )
        }

        // 4. Mention (Nhắc tên): cần quyền đăng và phải biết URN của người/tổ chức được nhắc (không có API tìm URN),
        // nên có quyền đăng vẫn bàn giao thủ công (HANDED_OFF).
        val mentionState = if (scopes.contains("w_organization_social") || scopes.contains("w_member_social")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_mention,
                statusTextRes = R.string.cap_status_handed_off,
                statusType = CapabilityStatusType.HANDED_OFF,
                captionRes = R.string.cap_li_mention_handed_off,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_mention,
                statusTextRes = R.string.cap_status_requires_perm,
                statusType = CapabilityStatusType.REQUIRES_PERMISSION,
                captionRes = R.string.cap_li_mention_no_perm,
            )
        }

        // 5. Số liệu (Metrics) - Quyền yêu cầu: rw_organization_admin
        val metricsState = if (scopes.contains("rw_organization_admin")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_metrics,
                statusTextRes = R.string.cap_status_supported,
                statusType = CapabilityStatusType.SUPPORTED,
                captionRes = R.string.cap_li_metrics_ok,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_metrics,
                statusTextRes = R.string.cap_status_handed_off,
                statusType = CapabilityStatusType.HANDED_OFF,
                captionRes = R.string.cap_li_metrics_handed_off,
            )
        }

        return listOf(publishState, readCommentsState, replyState, mentionState, metricsState)
    }
}
