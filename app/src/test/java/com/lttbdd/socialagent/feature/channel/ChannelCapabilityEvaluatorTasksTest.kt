package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ma trận Facebook theo Page task của người dùng (docs/research-social-api.md, mục Page Tasks). */
class ChannelCapabilityEvaluatorTasksTest {

    private val account = ChannelAccount(
        type = ChannelType.FACEBOOK,
        accountId = "fb_101",
        displayName = "Page",
        status = ConnectionStatus.CONNECTED,
        capabilities = emptySet(),
    )
    private val allScopes =
        "public_profile,pages_show_list,pages_read_engagement,pages_read_user_content,pages_manage_posts,pages_manage_engagement,read_insights"

    private fun types(tasks: List<String>?, scopes: String = allScopes) =
        ChannelCapabilityEvaluator.evaluateCapabilities(account, scopes, tasks).map { it.statusType }

    @Test
    fun `chua biet task thi chi xet quyen nhu truoc`() {
        val result = types(null)

        assertEquals(CapabilityStatusType.SUPPORTED, result[0])
        assertEquals(CapabilityStatusType.SUPPORTED, result[4])
    }

    @Test
    fun `chi co task ANALYZE thi dang bai, doc comment, tra loi van khong ho tro nhung so lieu duoc`() {
        val result = types(listOf("ANALYZE"))

        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, result[0])
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, result[1])
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, result[2])
        assertEquals(CapabilityStatusType.SUPPORTED, result[4])
    }

    @Test
    fun `du task CREATE_CONTENT va MODERATE thi dang bai, doc va tra loi duoc, so lieu thi can ANALYZE`() {
        val result = types(listOf("CREATE_CONTENT", "MODERATE"))

        assertEquals(CapabilityStatusType.SUPPORTED, result[0])
        assertEquals(CapabilityStatusType.SUPPORTED, result[1])
        assertEquals(CapabilityStatusType.SUPPORTED, result[2])
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, result[4])
    }

    @Test
    fun `tra loi can ca MODERATE lan CREATE_CONTENT`() {
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, types(listOf("MODERATE"))[2])
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, types(listOf("CREATE_CONTENT"))[2])
    }

    @Test
    fun `danh sach task rong nghia la khong co quyen nao tren Page`() {
        val result = types(emptyList())

        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, result[0])
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, result[1])
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, result[2])
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, result[4])
    }

    @Test
    fun `so lieu can ca read_insights lan pages_read_engagement`() {
        val all = listOf("CREATE_CONTENT", "MODERATE", "ANALYZE")

        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, types(all, "read_insights")[4])
        assertEquals(CapabilityStatusType.SUPPORTED, types(all, "read_insights,pages_read_engagement")[4])
    }

    @Test
    fun `thieu quyen app thi bao thieu quyen du co task`() {
        val result = types(listOf("CREATE_CONTENT", "MODERATE", "ANALYZE"), scopes = "public_profile")

        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, result[0])
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, result[4])
    }
}
