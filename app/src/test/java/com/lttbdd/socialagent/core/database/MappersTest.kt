package com.lttbdd.socialagent.core.database

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.CommentCategory
import com.lttbdd.socialagent.core.model.CommentStatus
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PostMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {

    @Test
    fun `draft survives a round trip through the entity`() {
        val draft = Draft(
            id = "d1",
            topicId = "t1",
            channel = ChannelType.LINKEDIN,
            text = "Xin chào",
            imageUri = "content://img",
            imageAltText = "Ảnh minh hoạ",
            approved = true,
        )

        val entity = draft.toEntity(updatedAtMillis = 42)

        assertEquals(42, entity.updatedAtMillis)
        assertEquals(draft, entity.toModel())
    }

    @Test
    fun `comment survives a round trip through the entity`() {
        val comment = Comment(
            id = "c1",
            channel = ChannelType.FACEBOOK,
            remotePostId = "p1",
            authorName = "Minh",
            text = "Nguồn số liệu ở đâu?",
            createdAtMillis = 1_000,
            category = CommentCategory.QUESTION,
            status = CommentStatus.NEW,
        )

        assertEquals(comment, comment.toEntity().toModel())
    }

    @Test
    fun `metrics survive a round trip and keep unknown impressions as null`() {
        val metrics = PostMetrics(
            channel = ChannelType.LINKEDIN,
            remotePostId = "p1",
            likes = 5,
            comments = 1,
            shares = 0,
            impressions = null,
            capturedAtMillis = 7,
        )

        assertEquals(metrics, metrics.toEntity().toModel())
    }
}
