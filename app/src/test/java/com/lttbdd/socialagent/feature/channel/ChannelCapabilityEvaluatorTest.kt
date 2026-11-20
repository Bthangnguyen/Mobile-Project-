package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelCapabilityEvaluatorTest {

    @Test
    fun `token het han yeu cau ket noi lai va giu ban giao thu cong`() {
        val scopesByType = mapOf(
            ChannelType.FACEBOOK to "pages_manage_posts,pages_read_engagement",
            ChannelType.LINKEDIN to "openid profile w_member_social",
        )

        scopesByType.forEach { (type, scopes) ->
            val account = ChannelAccount(
                type = type,
                accountId = "test",
                displayName = "Test",
                status = ConnectionStatus.CONNECTED,
                capabilities = emptySet(),
            )
            val tasks = listOf("CREATE_CONTENT", "MODERATE")
            val connected = ChannelCapabilityEvaluator.evaluateCapabilities(account, scopes, tasks)
            val expired = ChannelCapabilityEvaluator.evaluateCapabilities(
                account.copy(status = ConnectionStatus.TOKEN_EXPIRED),
                scopes,
                tasks,
            )

            assertEquals(CapabilityStatusType.SUPPORTED, connected.first().statusType)
            assertEquals(CapabilityStatusType.REQUIRES_RECONNECT, expired.first().statusType)
            assertEquals(R.string.cap_status_requires_reconnect, expired.first().statusTextRes)
            assertEquals(R.string.cap_token_expired, expired.first().captionRes)
            assertEquals(connected[3], expired[3])
            assertEquals(CapabilityStatusType.HANDED_OFF, expired[3].statusType)
            assertEquals(emptyList<CapabilityItemUiState>(), expired.filter { it.statusType == CapabilityStatusType.SUPPORTED })
        }
    }

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
    fun `facebook chi co public_profile hien huong dan can Page`() {
        val account = ChannelAccount(
            type = ChannelType.FACEBOOK,
            accountId = "fb_101",
            displayName = "Facebook Test Page",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, "public_profile")

        assertEquals(List(5) { CapabilityStatusType.REQUIRES_PERMISSION }, items.map { it.statusType })
        assertEquals(List(5) { R.string.cap_fb_needs_page }, items.map { it.captionRes })

        val pageItems = ChannelCapabilityEvaluator.evaluateCapabilities(account, "public_profile", listOf("CREATE_CONTENT"))
        assertEquals(R.string.cap_fb_publish_no_perm, pageItems.first().captionRes)
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
