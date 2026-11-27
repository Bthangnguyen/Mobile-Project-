package com.lttbdd.socialagent.data.comments

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.dao.CommentPostKey
import com.lttbdd.socialagent.core.database.entity.CommentEntity
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.database.entity.PostEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.CommentStatus
import com.lttbdd.socialagent.core.model.PostStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CommentInboxRepositoryTest {
    private lateinit var db: AppDatabase
    private val facebook = ChannelType.FACEBOOK
    private val linkedin = ChannelType.LINKEDIN

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun combinedFiltersAndPageLimitRespectChannelSpecificPostKeys() = runTest {
        val dao = db.commentDao()
        dao.upsertAll(
            listOf(
                comment("match", facebook, "shared", 20, CommentStatus.REPLIED),
                comment("wrong-status", facebook, "shared", 80),
                comment("wrong-channel", linkedin, "shared", 90, CommentStatus.REPLIED),
                comment("wrong-post", facebook, "another", 50, CommentStatus.REPLIED),
                comment("older", facebook, "shared", 10, CommentStatus.REPLIED),
                comment("same-time", linkedin, "another", 90),
            ),
        )

        val repository = CommentInboxRepository(dao)
        val filtered = CommentInboxFilter(facebook, CommentPostKey(facebook, "shared"), CommentStatus.REPLIED)
        assertEquals(listOf("match"), repository.observeComments(filtered, 1).first().map { it.id })
        assertEquals(listOf("older"), repository.observeComments(filtered, 1, CommentPageCursor(20, facebook, "match")).first().map { it.id })
        assertTrue(
            repository.observeComments(filtered, 1, CommentPageCursor(10, facebook, "older")).first().isEmpty(),
        )
        assertTrue(dao.observePageAfter(null, "shared", null, 30, null, null, null).first().isEmpty())
        assertTrue(repository.observeComments(filtered.copy(status = CommentStatus.IGNORED), 30).first().isEmpty())
        assertEquals(
            listOf("same-time", "wrong-channel", "wrong-status"),
            repository.observeComments(CommentInboxFilter(), 3).first().map { it.id },
        )
        assertEquals(
            listOf("wrong-channel"),
            repository.observeComments(CommentInboxFilter(), 1, CommentPageCursor(90, linkedin, "same-time")).first().map { it.id },
        )
        assertEquals(
            listOf(CommentPostKey(facebook, "shared"), CommentPostKey(facebook, "another")),
            repository.observePostPreviews(facebook).first().map { it.key },
        )
        assertEquals(listOf("match", "older"), repository.observeComments(filtered.copy(channel = null), 2).first().map { it.id })
        assertTrue(repository.observeComments(filtered.copy(channel = linkedin), 2).first().isEmpty())
    }

    @Test
    fun pageCursorOrdersEqualTimestampsByChannelAndIdEvenWhenAnchorLeavesFilter() = runTest {
        val dao = db.commentDao()
        dao.upsertAll(
            listOf(
                comment("a", facebook, "shared", 20),
                comment("b", facebook, "shared", 20),
                comment("a", linkedin, "shared", 20),
                comment("older", facebook, "shared", 10),
            ),
        )
        val repository = CommentInboxRepository(dao)
        val filter = CommentInboxFilter(status = CommentStatus.NEW)
        val anchor = repository.observeComments(filter, 1).first().single()
        assertEquals(facebook to "a", anchor.channel to anchor.id)

        dao.updateStatus(facebook, "a", CommentStatus.REPLIED)
        dao.upsertAll(listOf(comment("newer", linkedin, "shared", 30)))
        val nextPage = repository.observeComments(filter, 3, CommentPageCursor(anchor)).first()

        assertEquals(
            listOf(facebook to "b", linkedin to "a", facebook to "older"),
            nextPage.map { it.channel to it.id },
        )
    }

    @Test
    fun postPreviewsMatchChannelAndRemoteIdAndKeepPostsWithoutText() = runTest {
        val dao = db.commentDao()
        val repository = CommentInboxRepository(dao)
        val longText = "🔔".repeat(130)
        savePublishedPost(facebook, "shared", "  Nội dung Facebook  ")
        savePublishedPost(linkedin, "shared", longText)
        savePublishedPost(facebook, "blank", " \t\n ")
        dao.upsertAll(listOf(
            comment("1", facebook, "shared", 4),
            comment("2", linkedin, "shared", 3),
            comment("3", facebook, "external", 2),
            comment("4", facebook, "blank", 1),
        ))

        val posts = repository.observePostPreviews(null).first().associate { it.key to it.excerpt }
        assertEquals(mapOf(
            CommentPostKey(facebook, "shared") to "Nội dung Facebook",
            CommentPostKey(linkedin, "shared") to "🔔".repeat(121),
            CommentPostKey(facebook, "external") to null,
            CommentPostKey(facebook, "blank") to null,
        ), posts)
    }

    @Test
    fun postPreviewsNormalizeLongUnicodeWhitespaceBeforeLimitingExcerpt() = runTest {
        val dao = db.commentDao()
        val whitespace = "\u00A0\u2003\t".repeat(60)
        val longText = "🔔".repeat(125)
        savePublishedPost(facebook, "prefix", "\u00A0".repeat(150) + "Nội dung Facebook")
        savePublishedPost(facebook, "middle", "Đầu" + whitespace.repeat(1_000) + "cuối")
        savePublishedPost(facebook, "repeated", "word  ".repeat(35_000))
        savePublishedPost(facebook, "long", "A" + whitespace + longText.repeat(1600))
        savePublishedPost(facebook, "boundary", "🔔".repeat(120) + whitespace + "suffix")
        savePublishedPost(facebook, "trailing", "🔔".repeat(120) + whitespace)
        savePublishedPost(facebook, "blank", whitespace)
        dao.upsertAll(listOf(
            comment("prefix", facebook, "prefix", 4),
            comment("middle", facebook, "middle", 3),
            comment("long", facebook, "long", 2),
            comment("boundary", facebook, "boundary", 1),
            comment("trailing", facebook, "trailing", 0),
            comment("blank", facebook, "blank", -1),
            comment("repeated", facebook, "repeated", -2),
        ))

        val previews = dao.observePostPreviews(facebook).first()

        assertEquals(
            listOf(
                "Nội dung Facebook", "Đầu cuối", "A " + "🔔".repeat(119),
                "🔔".repeat(120) + " ", "🔔".repeat(120), null, "word ".repeat(24) + "w",
            ),
            previews.map { it.excerpt },
        )
    }

    private suspend fun savePublishedPost(channel: ChannelType, remoteId: String, text: String) {
        val id = "${channel.name}-$remoteId"
        db.draftDao().upsert(
            DraftEntity(id, id, channel, text, null, null, approved = true, updatedAtMillis = 1),
        )
        db.postDao().insertIfAbsent(
            PostEntity(
                id = id,
                draftId = id,
                channel = channel,
                status = PostStatus.PUBLISHED,
                idempotencyKey = id,
                remotePostId = remoteId,
                url = null,
                errorMessage = null,
                attemptCount = 1,
                createdAtMillis = 1,
                updatedAtMillis = 1,
            ),
        )
    }

    private fun comment(id: String, channel: ChannelType, post: String, time: Long, status: CommentStatus = CommentStatus.NEW) =
        CommentEntity(id, channel, post, "Test", id, time, null, status)
}
