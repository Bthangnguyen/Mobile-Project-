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
        // 4. Mention: chưa xác minh dù đã có quyền đăng/tương tác nên bàn giao thủ công
        assertEquals(CapabilityStatusType.HANDED_OFF, items[3].statusType)
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
        val scopes = ""

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, scopes)

        assertEquals(5, items.size)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[0].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[1].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[2].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[3].statusType)
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
        assertEquals(CapabilityStatusType.HANDED_OFF, items[3].statusType)
        assertEquals(CapabilityStatusType.SUPPORTED, items[4].statusType)
    }

    @Test
    fun `facebook mention khong duoc coi la supported chi vi co public_profile`() {
        val account = ChannelAccount(
            type = ChannelType.FACEBOOK,
            accountId = "fb_101",
            displayName = "Facebook Test Page",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, "public_profile")

        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[3].statusType)
    }

    @Test
    fun `linkedin member scope cho phep dang bai nhung mention van chua xac dinh`() {
        val account = ChannelAccount(
            type = ChannelType.LINKEDIN,
            accountId = "li_member",
            displayName = "LinkedIn Member Test",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, "openid profile w_member_social")

        assertEquals(CapabilityStatusType.SUPPORTED, items[0].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[1].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[2].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[3].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[4].statusType)
    }

    @Test
    fun `linkedin khong co quyen dang bai thi van la requires_permission, chi cac muc khong xin duoc moi ban giao`() {
        val account = ChannelAccount(
            type = ChannelType.LINKEDIN,
            accountId = "li_member",
            displayName = "LinkedIn Member Test",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, "openid profile")

        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[0].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[1].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[2].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[3].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[4].statusType)
    }

    @Test
    fun `facebook thieu quyen van la requires_permission vi nguoi dung cap lai duoc`() {
        val account = ChannelAccount(
            type = ChannelType.FACEBOOK,
            accountId = "fb_101",
            displayName = "Facebook Test Page",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, "pages_manage_posts")

        assertEquals(CapabilityStatusType.SUPPORTED, items[0].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[1].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[2].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[3].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[4].statusType)
    }
}
