package com.lttbdd.socialagent.data.fake

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PublishResult
import com.lttbdd.socialagent.core.model.ReconcileResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kênh giả phải đối soát đúng để test hàng đợi và dựng UI (#94). */
class FakeSocialChannelClientTest {

    private val client = FakeSocialChannelClient(ChannelType.FACEBOOK, latencyMillis = 0)

    private fun draft(id: String) = Draft(id = id, topicId = "t", channel = ChannelType.FACEBOOK, text = "Nội dung $id")

    @Test
    fun `published draft is found with the same remote id and link`() = runTest {
        val published = client.publish(draft("d1"), idempotencyKey = "k1") as PublishResult.Success

        assertEquals(
            ReconcileResult.Found(published.remotePostId, published.url),
            client.findPublishedPost(draft("d1"), sinceMillis = 0),
        )
    }

    @Test
    fun `draft that was never published is not found`() = runTest {
        client.publish(draft("d1"), idempotencyKey = "k1")

        assertEquals(ReconcileResult.NotFound, client.findPublishedPost(draft("d2"), sinceMillis = 0))
    }

    @Test
    fun `reconcile never creates a post`() = runTest {
        client.findPublishedPost(draft("d1"), sinceMillis = 0)

        assertEquals(0, client.publishedCount)
    }
}
