package com.lttbdd.socialagent.feature.channel

import org.junit.Assert.assertEquals
import org.junit.Test

/** Test cho việc tách chuỗi quyền của ma trận khả năng kênh. */
class ChannelCapabilityEvaluatorScopeTest {

    @Test
    fun `tach scope dau phay cua Facebook`() {
        assertEquals(
            setOf("public_profile", "pages_manage_posts"),
            ChannelCapabilityEvaluator.parseScopes("public_profile,pages_manage_posts"),
        )
    }

    @Test
    fun `tach scope khoang trang cua LinkedIn`() {
        assertEquals(
            setOf("openid", "profile", "w_member_social"),
            ChannelCapabilityEvaluator.parseScopes("openid profile  w_member_social"),
        )
    }

    @Test
    fun `scope null hoac rong tra ve tap rong`() {
        assertEquals(emptySet<String>(), ChannelCapabilityEvaluator.parseScopes(null))
        assertEquals(emptySet<String>(), ChannelCapabilityEvaluator.parseScopes("  , "))
    }
}
