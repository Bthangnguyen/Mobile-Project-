package com.lttbdd.socialagent.feature.review

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomDraftReviewRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoomDraftReviewRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomDraftReviewRepository(db.draftDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `saveAgentDraft persists approved as false even when caller passes approved true`() = runTest {
        val draft = Draft(
            id = "draft-agent-1",
            topicId = "topic-1",
            channel = ChannelType.LINKEDIN,
            text = "Bài do AI sinh ra",
            approved = true,
        )

        val saved = repository.saveAgentDraft(draft)
        val observed = repository.observeDrafts().first()

        assertFalse(saved.approved)
        assertEquals(1, observed.size)
        assertFalse(observed.first().approved)
    }

    @Test
    fun `requestPublish blocks unapproved draft and allows only after explicit approval`() = runTest {
        val draft = Draft(
            id = "draft-2",
            topicId = "topic-2",
            channel = ChannelType.FACEBOOK,
            text = "Bài giới thiệu tính năng mới",
            approved = false,
        )
        repository.saveAgentDraft(draft)

        val blocked = repository.requestPublish("draft-2")
        assertTrue(blocked is PublishGateResult.BlockedUnapproved)

        val approveResult = repository.approveDraft("draft-2")
        assertTrue(approveResult.isSuccess)
        assertTrue(approveResult.getOrThrow().approved)

        val ready = repository.requestPublish("draft-2")
        assertTrue(ready is PublishGateResult.ReadyToPublish)
        assertEquals("draft-2", (ready as PublishGateResult.ReadyToPublish).draft.id)
    }

    @Test
    fun `revokeApproval locks publishing again`() = runTest {
        val draft = Draft(
            id = "draft-3",
            topicId = "topic-3",
            channel = ChannelType.FACEBOOK,
            text = "Bài cần kiểm tra lại",
            approved = false,
        )
        repository.saveAgentDraft(draft)
        repository.approveDraft("draft-3")
        assertTrue(repository.requestPublish("draft-3") is PublishGateResult.ReadyToPublish)

        val revokeResult = repository.revokeApproval("draft-3")
        assertTrue(revokeResult.isSuccess)
        assertFalse(revokeResult.getOrThrow().approved)
        assertTrue(repository.requestPublish("draft-3") is PublishGateResult.BlockedUnapproved)
    }

    @Test
    fun `approveDraft fails for blank content and missing draft`() = runTest {
        repository.saveAgentDraft(
            Draft(
                id = "draft-empty",
                topicId = "topic-4",
                channel = ChannelType.LINKEDIN,
                text = "   ",
                approved = false,
            ),
        )

        assertTrue(repository.approveDraft("draft-empty").isFailure)
        assertTrue(repository.approveDraft("non-existent").isFailure)
        assertEquals(PublishGateResult.NotFound, repository.requestPublish("non-existent"))
    }
}
