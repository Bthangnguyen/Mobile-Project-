package com.lttbdd.socialagent.data.fake

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
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
}
