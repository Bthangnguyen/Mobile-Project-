package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelCapabilityEvaluatorTest {

    @Test
    fun `facebook capabilities with full scopes return supported for publish, read, reply, metrics`() {
        val account = ChannelAccount(
            type = ChannelType.FACEBOOK,
            accountId = "fb_101",
            displayName = "Facebook Test Page",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )
        val scopes = "pages_manage_posts,pages_read_engagement,pages_manage_engagement,read_insights"

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, scopes)

        assertEquals(5, items.size)
        // 1. Publish
        assertEquals(CapabilityStatusType.SUPPORTED, items[0].statusType)
        // 2. Read Comments
        assertEquals(CapabilityStatusType.SUPPORTED, items[1].statusType)
        // 3. Reply
        assertEquals(CapabilityStatusType.SUPPORTED, items[2].statusType)
        // 4. Mention (Unverified for profiles in Pages API)
        assertEquals(CapabilityStatusType.UNVERIFIED, items[3].statusType)
        // 5. Metrics
        assertEquals(CapabilityStatusType.SUPPORTED, items[4].statusType)
    }

    @Test
    fun `facebook capabilities with missing scopes return requires_permission`() {
        val account = ChannelAccount(
            type = ChannelType.FACEBOOK,
            accountId = "fb_101",
            displayName = "Facebook Test Page",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )
        val scopes = "public_profile"

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, scopes)

        assertEquals(5, items.size)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[0].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[1].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[2].statusType)
        assertEquals(CapabilityStatusType.UNVERIFIED, items[3].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[4].statusType)
    }

    @Test
    fun `linkedin capabilities with full scopes return supported for all 5 items`() {
        val account = ChannelAccount(
            type = ChannelType.LINKEDIN,
            accountId = "li_202",
            displayName = "LinkedIn Org Test",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )
        val scopes = "w_organization_social,r_organization_social_feed,w_organization_social_feed,rw_organization_admin"

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, scopes)

        assertEquals(5, items.size)
        assertEquals(CapabilityStatusType.SUPPORTED, items[0].statusType)
        assertEquals(CapabilityStatusType.SUPPORTED, items[1].statusType)
        assertEquals(CapabilityStatusType.SUPPORTED, items[2].statusType)
        assertEquals(CapabilityStatusType.SUPPORTED, items[3].statusType)
        assertEquals(CapabilityStatusType.SUPPORTED, items[4].statusType)
    }
}
