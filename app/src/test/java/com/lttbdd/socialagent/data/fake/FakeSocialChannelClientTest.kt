package com.lttbdd.socialagent.data.fake

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PublishResult
import com.lttbdd.socialagent.core.model.ReconcileResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FakeSocialChannelClientTest {

    private val client = FakeSocialChannelClient(ChannelType.FACEBOOK, latencyMillis = 0)
    private val draft = Draft(id = "d1", topicId = "t1", channel = ChannelType.FACEBOOK, text = "Hello")

    @Test
    fun `publish twice with same key does not create duplicate post`() = runTest {
        val first = client.publish(draft, idempotencyKey = "key-1")
        val second = client.publish(draft, idempotencyKey = "key-1")

        assertEquals(first, second)
        assertEquals(1, client.publishedCount)
    }

    @Test
    fun `publish with different keys creates separate posts`() = runTest {
        val first = client.publish(draft, idempotencyKey = "key-1")
        val second = client.publish(draft, idempotencyKey = "key-2")

        assertNotEquals(first, second)
        assertEquals(2, client.publishedCount)
    }

    // ---------- #94: đối soát ----------

    @Test
    fun `published draft is found with the same remote id and link`() = runTest {
        val published = client.publish(draft, idempotencyKey = "key-1") as PublishResult.Success

        assertEquals(
            ReconcileResult.Found(published.remotePostId, published.url),
            client.findPublishedPost(draft, sinceMillis = 0),
        )
    }

    @Test
    fun `draft that was never published is not found`() = runTest {
        client.publish(draft, idempotencyKey = "key-1")

        assertEquals(
            ReconcileResult.NotFound,
            client.findPublishedPost(draft.copy(id = "d2"), sinceMillis = 0),
        )
    }

    @Test
    fun `reconcile never creates a post`() = runTest {
        client.findPublishedPost(draft, sinceMillis = 0)

        assertEquals(0, client.publishedCount)
    }
}
