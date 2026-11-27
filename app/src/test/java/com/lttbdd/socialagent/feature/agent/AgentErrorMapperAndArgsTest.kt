package com.lttbdd.socialagent.feature.agent

import android.os.Bundle
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ContentLanguage
import com.lttbdd.socialagent.core.model.TopicBrief
import com.lttbdd.socialagent.data.ai.ChannelFailureReason
import com.lttbdd.socialagent.data.ai.LlmFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentErrorMapperAndArgsTest {

    @Test
    fun `maps known channel and llm failure codes to dedicated string resources`() {
        assertEquals(R.string.agent_error_timeout, AgentErrorMapper.toMessageRes(ChannelFailureReason.TIMEOUT))
        assertEquals(
            R.string.agent_error_timeout,
            AgentErrorMapper.toMessageRes(ChannelFailureReason.fromLlm(LlmFailure.TIMEOUT)),
        )
        assertEquals(
            R.string.agent_error_rules_violated,
            AgentErrorMapper.toMessageRes(ChannelFailureReason.CHANNEL_RULES_VIOLATED),
        )
        assertEquals(
            R.string.agent_error_missing_api_key,
            AgentErrorMapper.toMessageRes(ChannelFailureReason.fromLlm(LlmFailure.MISSING_API_KEY)),
        )
        assertEquals(
            R.string.agent_error_auth_failed,
            AgentErrorMapper.toMessageRes(ChannelFailureReason.fromLlm(LlmFailure.AUTHENTICATION)),
        )
        assertEquals(
            R.string.agent_error_rate_limit,
            AgentErrorMapper.toMessageRes(ChannelFailureReason.fromLlm(LlmFailure.RATE_LIMIT)),
        )
        assertEquals(
            R.string.agent_error_network,
            AgentErrorMapper.toMessageRes(ChannelFailureReason.fromLlm(LlmFailure.NETWORK)),
        )
        assertEquals(
            R.string.agent_error_content_blocked,
            AgentErrorMapper.toMessageRes(ChannelFailureReason.fromLlm(LlmFailure.CONTENT_BLOCKED)),
        )
        assertEquals(R.string.agent_error_unknown, AgentErrorMapper.toMessageRes(null))
        assertEquals(R.string.agent_error_unknown, AgentErrorMapper.toMessageRes("SOME_UNMAPPED_CODE"))
    }

    @Test
    fun `AgentBriefArgs round-trips valid TopicBrief through Bundle`() {
        val original = TopicBrief(
            id = "brief-99",
            topic = "AI Agent trên Android",
            goal = "Chia sẻ kiến trúc",
            audience = "Mobile developers",
            tone = "informative",
            language = ContentLanguage.EN,
            channels = setOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN),
        )

        val bundle = AgentBriefArgs.toBundle(original)
        val restored = AgentBriefArgs.fromBundle(bundle)

        assertEquals(original, restored)
    }

    @Test
    fun `AgentBriefArgs returns null when bundle is null or missing required fields`() {
        assertNull(AgentBriefArgs.fromBundle(null))
        assertNull(AgentBriefArgs.fromBundle(Bundle()))
    }
}
