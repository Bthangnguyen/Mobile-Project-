package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.core.model.ChannelAccount

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
