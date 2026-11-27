package com.lttbdd.socialagent.feature.agent

import androidx.annotation.StringRes
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.data.ai.ChannelFailureReason
import com.lttbdd.socialagent.data.ai.LlmFailure

/**
 * Ánh xạ mã lỗi an toàn từ [com.lttbdd.socialagent.core.model.AgentEvent.ChannelFailed.reason]
 * sang chuỗi hiển thị đa ngôn ngữ (EN/VI). Không hiển thị thông điệp exception thô lên UI.
 */
object AgentErrorMapper {

    @StringRes
    fun toMessageRes(reasonCode: String?): Int = when (reasonCode) {
        ChannelFailureReason.TIMEOUT,
        ChannelFailureReason.fromLlm(LlmFailure.TIMEOUT),
        -> R.string.agent_error_timeout

        ChannelFailureReason.CHANNEL_RULES_VIOLATED -> R.string.agent_error_rules_violated
        ChannelFailureReason.UNSUPPORTED_CHANNEL -> R.string.agent_error_unsupported_channel
        ChannelFailureReason.CHANNEL_NOT_IN_BRIEF -> R.string.agent_error_channel_not_in_brief
        ChannelFailureReason.NO_RESULT,
        ChannelFailureReason.fromLlm(LlmFailure.EMPTY_RESPONSE),
        ChannelFailureReason.fromLlm(LlmFailure.INCOMPLETE_RESPONSE),
        ChannelFailureReason.fromLlm(LlmFailure.INVALID_RESPONSE),
        -> R.string.agent_error_empty_response

        ChannelFailureReason.fromLlm(LlmFailure.MISSING_API_KEY),
        ChannelFailureReason.fromLlm(LlmFailure.MISSING_CONFIGURATION),
        -> R.string.agent_error_missing_api_key

        ChannelFailureReason.fromLlm(LlmFailure.AUTHENTICATION),
        ChannelFailureReason.fromLlm(LlmFailure.PERMISSION),
        -> R.string.agent_error_auth_failed

        ChannelFailureReason.fromLlm(LlmFailure.RATE_LIMIT) -> R.string.agent_error_rate_limit
        ChannelFailureReason.fromLlm(LlmFailure.NETWORK) -> R.string.agent_error_network
        ChannelFailureReason.fromLlm(LlmFailure.CONTENT_BLOCKED) -> R.string.agent_error_content_blocked
        ChannelFailureReason.fromLlm(LlmFailure.SERVER),
        ChannelFailureReason.fromLlm(LlmFailure.HTTP),
        ChannelFailureReason.fromLlm(LlmFailure.PROVIDER),
        -> R.string.agent_error_provider

        else -> R.string.agent_error_unknown
    }
}
