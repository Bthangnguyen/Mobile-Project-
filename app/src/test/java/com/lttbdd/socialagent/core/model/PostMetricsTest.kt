package com.lttbdd.socialagent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostMetricsTest {

    private fun metrics(impressions: Int?) = PostMetrics(
        channel = ChannelType.LINKEDIN,
        remotePostId = "p1",
        likes = 8,
        comments = 1,
        shares = 1,
        impressions = impressions,
        capturedAtMillis = 0,
    )

    @Test
    fun `engagement rate is interactions divided by impressions`() {
        assertEquals(0.1, metrics(impressions = 100).engagementRate!!, 1e-9)
    }

    @Test
    fun `engagement rate is null when impressions unknown or zero`() {
        assertNull(metrics(impressions = null).engagementRate)
        assertNull(metrics(impressions = 0).engagementRate)
    }
}
