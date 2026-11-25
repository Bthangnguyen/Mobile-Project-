package com.lttbdd.socialagent.data.publishing

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.dao.PostDao
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PostMetrics
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.core.model.PublishResult
import com.lttbdd.socialagent.core.social.SocialChannelClient
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Test [PublishQueueWorker] đổi kết quả của hàng đợi sang `Result` của WorkManager. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PublishQueueWorkerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val client = OneShotClient()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val repository by lazy {
        PublishQueueRepository(db, db.postDao(), db.draftDao(), setOf(client), NoOpScheduler) { 1_000L }
    }

    private fun worker(repo: PublishQueueRepository = repository): PublishQueueWorker =
        TestListenableWorkerBuilder<PublishQueueWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = PublishQueueWorker(appContext, workerParameters, repo)
                },
            )
            .build()

    private suspend fun queueApprovedDraft(): String {
        db.draftDao().upsert(
            DraftEntity(
                id = "d1",
                topicId = "t1",
                channel = ChannelType.FACEBOOK,
                text = "Nội dung",
                imageUri = null,
                imageAltText = null,
                approved = true,
                updatedAtMillis = 1L,
            ),
        )
        return (repository.enqueue("d1") as EnqueueResult.Queued).postId
    }

    @Test
    fun `empty queue succeeds`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }

    @Test
    fun `published post succeeds`() = runTest {
        val id = queueApprovedDraft()
        client.result = PublishResult.Success("fb-1", "https://facebook.com/fb-1")

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(PostStatus.PUBLISHED, db.postDao().getById(id)?.status)
    }

    @Test
    fun `retryable error asks WorkManager to retry`() = runTest {
        val id = queueApprovedDraft()
        client.result = PublishResult.RetryableError("rate_limited")

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals(PostStatus.QUEUED, db.postDao().getById(id)?.status)
    }

    @Test
    fun `permanent error does not retry`() = runTest {
        val id = queueApprovedDraft()
        client.result = PublishResult.PermanentError("permission_denied")

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(PostStatus.FAILED, db.postDao().getById(id)?.status)
    }

    @Test
    fun `unexpected repository failure asks to retry`() = runTest {
        val broken = object : PostDao by db.postDao() {
            override suspend fun markStalePublishingAsUnknown(
                startedBeforeMillis: Long,
                nowMillis: Long,
                publishing: PostStatus,
            ): Int = error("disk I/O error")
        }
        val repo = PublishQueueRepository(db, broken, db.draftDao(), setOf(client), NoOpScheduler) { 1_000L }

        assertEquals(ListenableWorker.Result.retry(), worker(repo).doWork())
    }

    private object NoOpScheduler : PublishScheduler {
        override fun schedule() = Unit
        override fun scheduleStaleCheck(delayMillis: Long) = Unit
    }

    private class OneShotClient : SocialChannelClient {
        override val type = ChannelType.FACEBOOK
        var result: PublishResult = PublishResult.Success("auto", "https://example.com/auto")

        override suspend fun publish(draft: Draft, idempotencyKey: String): PublishResult = result
        override suspend fun fetchComments(remotePostId: String): List<Comment> = emptyList()
        override suspend fun reply(commentId: String, text: String) = Unit
        override suspend fun fetchMetrics(remotePostId: String): PostMetrics = error("not used")
        override suspend fun fetchFollowerCount(): Int = 0
    }
}
