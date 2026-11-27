package com.lttbdd.socialagent.feature.comments

import com.lttbdd.socialagent.core.database.dao.CommentDao
import com.lttbdd.socialagent.core.database.dao.CommentPostKey
import com.lttbdd.socialagent.core.database.dao.CommentPostPreview
import com.lttbdd.socialagent.core.database.entity.CommentEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.CommentCategory
import com.lttbdd.socialagent.core.model.CommentStatus
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.data.comments.CommentInboxRepository
import com.lttbdd.socialagent.data.comments.CommentPageCursor
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentInboxViewModelTest {
    private val facebookPost = CommentPostKey(ChannelType.FACEBOOK, "shared")
    private val linkedinPost = CommentPostKey(ChannelType.LINKEDIN, "shared")

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun changingChannelClearsOnlyTheIncompatiblePostFilter() = runTest {
        val dao = FakeCommentDao()
        val inbox = openInbox(dao)

        inbox.selectPost(facebookPost)
        inbox.selectStatus(CommentStatus.REPLIED)
        advanceUntilIdle()
        assertEquals(Query(ChannelType.FACEBOOK, "shared", CommentStatus.REPLIED, 31, null), dao.lastQuery)

        inbox.selectChannel(ChannelType.FACEBOOK)
        advanceUntilIdle()
        assertEquals(facebookPost, inbox.uiState.value.filter.post)

        inbox.selectChannel(ChannelType.LINKEDIN)
        advanceUntilIdle()

        val state = inbox.uiState.value
        assertNull(state.filter.post)
        assertEquals(CommentStatus.REPLIED, state.filter.status)
        assertEquals(listOf(linkedinPost), state.posts.map { it.key })
        assertTrue(state.comments.isEmpty())
        assertFalse(state.isLoading)
        assertEquals(Query(ChannelType.LINKEDIN, null, CommentStatus.REPLIED, 31, null), dao.lastQuery)

        inbox.clearFilters()
        advanceUntilIdle()
        assertEquals(Query(null, null, null, 31, null), dao.lastQuery)
        assertEquals(listOf(facebookPost, linkedinPost), inbox.uiState.value.posts.map { it.key })
    }

    @Test
    fun nextAndPreviousUseBoundedPagesAndNewFilterRestartsAtFirstPage() = runTest {
        val dao = FakeCommentDao()
        val inbox = openInbox(dao)
        assertEquals(30, inbox.uiState.value.comments.size)
        assertTrue(inbox.uiState.value.hasMore)

        inbox.nextPage()
        inbox.nextPage()
        advanceUntilIdle()
        assertEquals(listOf(null, CommentPageCursor(31, ChannelType.FACEBOOK, "29")), dao.queries.map { it.after })
        assertEquals(listOf(31, 31), dao.queries.map { it.limit })
        assertEquals(1, dao.postReads)
        assertEquals((30..59).map(Int::toString), inbox.uiState.value.comments.map { it.id })
        assertFalse(inbox.uiState.value.hasMore)

        inbox.previousPage()
        advanceUntilIdle()
        assertFalse(inbox.uiState.value.hasPrevious)
        inbox.nextPage()
        advanceUntilIdle()

        inbox.selectStatus(CommentStatus.NEW)
        advanceUntilIdle()
        assertEquals(Query(null, null, CommentStatus.NEW, 31, null), dao.lastQuery)
        assertEquals((0..29).map(Int::toString), inbox.uiState.value.comments.map { it.id })
    }

    @Test
    fun nextPageKeepsCommentsWithTheSameTimestampAsTheCursor() = runTest {
        val dao = FakeCommentDao(commentCount = 30)
        dao.comments.update { rows ->
            listOf(
                comment(100, 0),
                comment(0, 1).copy(channel = ChannelType.LINKEDIN),
                comment(99, 1),
                comment(300, 1),
            ) + rows.reversed()
        }
        val inbox = openInbox(dao)
        assertEquals((0..29).map(Int::toString), inbox.uiState.value.comments.map { it.id })

        inbox.nextPage()
        advanceUntilIdle()

        assertEquals(
            listOf(
                ChannelType.FACEBOOK to "300",
                ChannelType.FACEBOOK to "99",
                ChannelType.LINKEDIN to "0",
                ChannelType.FACEBOOK to "100",
            ),
            inbox.uiState.value.comments.map { it.channel to it.id },
        )
        assertFalse(inbox.uiState.value.hasMore)
    }

    @Test
    fun deletingAllCommentsOnCurrentPageReturnsToFirstPage() = runTest {
        val dao = FakeCommentDao()
        val inbox = openInbox(dao)
        inbox.nextPage()
        advanceUntilIdle()
        assertTrue(inbox.uiState.value.hasPrevious)

        dao.comments.update { it.take(30) }
        advanceUntilIdle()

        val state = inbox.uiState.value
        assertEquals((0..29).map(Int::toString), state.comments.map { it.id })
        assertFalse(state.hasPrevious)
        assertFalse(state.hasMore)
        assertFalse(state.isLoading)
        assertNull(dao.lastQuery?.after)
    }

    @Test
    fun insertingNewerCommentDoesNotShiftCurrentPage() = runTest {
        val dao = FakeCommentDao()
        val inbox = openInbox(dao)
        val firstPage = inbox.uiState.value.comments.map { it.id }
        inbox.nextPage()
        advanceUntilIdle()

        dao.comments.update { listOf(comment(-1, 61)) + it }
        advanceUntilIdle()

        val secondPage = inbox.uiState.value.comments.map { it.id }
        assertEquals((30..59).map(Int::toString), secondPage)
        assertTrue(firstPage.intersect(secondPage.toSet()).isEmpty())
        assertTrue(inbox.uiState.value.hasPrevious)
        assertFalse(inbox.uiState.value.hasMore)

        inbox.previousPage()
        advanceUntilIdle()
        assertEquals((-1..28).map(Int::toString), inbox.uiState.value.comments.map { it.id })
        inbox.nextPage()
        advanceUntilIdle()
        assertEquals((29..58).map(Int::toString), inbox.uiState.value.comments.map { it.id })
        assertTrue(inbox.uiState.value.hasMore)
    }

    @Test
    fun previousPageUsesItsCursorAfterBoundaryDeletionAndNextUsesUpdatedLastComment() = runTest {
        val dao = FakeCommentDao(commentCount = 90)
        val inbox = openInbox(dao)
        inbox.nextPage()
        advanceUntilIdle()
        inbox.nextPage()
        advanceUntilIdle()

        dao.comments.update { comments -> comments.filterNot { it.id == "29" || it.id == "59" } }
        advanceUntilIdle()
        assertEquals((60..89).map(Int::toString), inbox.uiState.value.comments.map { it.id })

        inbox.previousPage()
        advanceUntilIdle()
        assertEquals((30..60).filter { it != 59 }.map(Int::toString), inbox.uiState.value.comments.map { it.id })

        inbox.nextPage()
        advanceUntilIdle()
        assertEquals((61..89).map(Int::toString), inbox.uiState.value.comments.map { it.id })
    }

    @Test
    fun readFailureCanBeRetriedWithTheSameFilter() = runTest {
        val dao = FakeCommentDao().apply { fail = true }
        val inbox = openInbox(dao)
        assertTrue(inbox.uiState.value.hasError)

        dao.fail = false
        inbox.retry()
        advanceUntilIdle()
        assertFalse(inbox.uiState.value.hasError)
        assertEquals(30, inbox.uiState.value.comments.size)
    }

    @Test
    fun changingFiltersRecoversAfterCommentOrPostReadFails() = runTest {
        val dao = FakeCommentDao().apply { fail = true }
        val inbox = openInbox(dao)
        assertEquals(CommentInboxEmptyState.ERROR, inbox.uiState.value.emptyState)

        dao.fail = false
        inbox.selectChannel(ChannelType.FACEBOOK)
        advanceUntilIdle()
        assertEquals(facebookPost, inbox.uiState.value.posts.single().key)
        assertFalse(inbox.uiState.value.hasError)

        dao.failPosts = true
        inbox.selectChannel(ChannelType.LINKEDIN)
        advanceUntilIdle()
        assertEquals(CommentInboxEmptyState.ERROR, inbox.uiState.value.emptyState)

        dao.failPosts = false
        inbox.selectStatus(CommentStatus.REPLIED)
        advanceUntilIdle()
        assertEquals(linkedinPost, inbox.uiState.value.posts.single().key)
        assertEquals(CommentInboxEmptyState.NO_RESULTS, inbox.uiState.value.emptyState)
    }

    @Test
    fun emptyInboxDistinguishesUnfilteredAndFilteredResults() = runTest {
        val inbox = openInbox(FakeCommentDao(commentCount = 0))
        assertEquals(CommentInboxEmptyState.NO_COMMENTS, inbox.uiState.value.emptyState)

        inbox.selectStatus(CommentStatus.REPLIED)
        advanceUntilIdle()
        assertEquals(CommentInboxEmptyState.NO_RESULTS, inbox.uiState.value.emptyState)
    }

    private fun TestScope.openInbox(dao: FakeCommentDao): CommentInboxViewModel {
        val inbox = CommentInboxViewModel(CommentInboxRepository(dao))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { inbox.uiState.collect {} }
        advanceUntilIdle()
        return inbox
    }

    private data class Query(
        val channel: ChannelType?,
        val post: String?,
        val status: CommentStatus?,
        val limit: Int,
        val after: CommentPageCursor?,
    )

    private fun comment(index: Int, time: Long) = CommentEntity(
        id = index.toString(),
        channel = ChannelType.FACEBOOK,
        remotePostId = "shared",
        authorName = "Test",
        text = "Comment",
        createdAtMillis = time,
        category = null,
        status = CommentStatus.NEW,
    )

    private inner class FakeCommentDao(commentCount: Int = 60) : CommentDao() {
        private val pageOrder = compareByDescending<CommentEntity> { it.createdAtMillis }
            .thenBy { it.channel.name }
            .thenBy { it.id }

        val comments = MutableStateFlow(List(commentCount) { comment(it, (commentCount - it).toLong()) })
        val queries = mutableListOf<Query>()
        val lastQuery get() = queries.lastOrNull()
        var fail = false
        var failPosts = false
        var postReads = 0

        override fun observePageAfter(
            channel: ChannelType?,
            remotePostId: String?,
            status: CommentStatus?,
            limit: Int,
            afterCreatedAtMillis: Long?,
            afterChannel: ChannelType?,
            afterId: String?,
        ): Flow<List<CommentEntity>> = flow {
            val after = afterCreatedAtMillis?.let {
                CommentPageCursor(it, requireNotNull(afterChannel), requireNotNull(afterId))
            }
            queries += Query(channel, remotePostId, status, limit, after)
            if (fail) throw IOException("test")
            emitAll(comments.map { rows ->
                rows.filter {
                    (channel == null || it.channel == channel) &&
                        (remotePostId == null || it.remotePostId == remotePostId) &&
                        (status == null || it.status == status) &&
                        (after == null || it.createdAtMillis < after.createdAtMillis ||
                            (it.createdAtMillis == after.createdAtMillis &&
                                (it.channel.name > after.channel.name ||
                                    (it.channel == after.channel && it.id > after.id))))
                }.sortedWith(pageOrder).take(limit)
            })
        }

        override fun observePostPreviews(
            channel: ChannelType?,
            published: PostStatus,
        ): Flow<List<CommentPostPreview>> = flow {
            postReads++
            if (failPosts) throw IOException("test")
            val posts = listOf(facebookPost, linkedinPost).filter {
                channel == null || it.channel == channel
            }
            emit(posts.map { CommentPostPreview(it, null) })
        }

        override fun observe(channel: ChannelType?, remotePostId: String?, status: CommentStatus?): Flow<List<CommentEntity>> =
            error("Hộp thư phải đọc có giới hạn")

        override suspend fun deleteByChannel(channel: ChannelType) = error("Không ghi trong hộp thư")
        override suspend fun upsertAll(comments: List<CommentEntity>) = error("Không ghi trong hộp thư")
        override suspend fun updateStatus(channel: ChannelType, id: String, status: CommentStatus) = error("Không ghi trong hộp thư")
        override suspend fun updateCategory(channel: ChannelType, id: String, category: CommentCategory) = error("Không ghi trong hộp thư")
        override suspend fun insertIgnoringExisting(comments: List<CommentEntity>) = error("Không ghi trong hộp thư")
        override suspend fun updateContent(channel: ChannelType, id: String, authorName: String, text: String) =
            error("Không ghi trong hộp thư")
    }
}
