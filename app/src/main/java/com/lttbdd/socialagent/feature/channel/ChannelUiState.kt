package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.data.model.FacebookPage

/**
 * Trạng thái UI màn hình Kênh của tôi (F1).
 */
sealed interface ChannelUiState {
    data object Loading : ChannelUiState
    data class Success(
        val channels: List<ChannelAccount>,
        val userMessage: String? = null,
    ) : ChannelUiState
    data class Error(val message: String) : ChannelUiState
}

/** Danh sách Page để người dùng chọn khi tài khoản Facebook quản lý nhiều Page. */
data class FacebookPagePicker(val pages: List<FacebookPage>)

/** Thông báo một lần cho UI (không lưu lại trạng thái). */
enum class ChannelNotice { NO_FACEBOOK_PAGES }
