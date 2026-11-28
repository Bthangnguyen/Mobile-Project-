package com.lttbdd.socialagent.data.review

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.database.toEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PostStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

        val saved = repository.saveAgentDraft(draft).getOrThrow()
        val observed = repository.observeDrafts().first()

        assertFalse(saved.approved)
        assertEquals(1, observed.size)
        assertFalse(observed.first().approved)
    }

    @Test
    fun `requestPublish blocks unapproved draft and allows only after explicit approval matching viewed content`() = runTest {
        val draft = Draft(
            id = "draft-2",
            topicId = "topic-2",
            channel = ChannelType.FACEBOOK,
            text = "Bài giới thiệu tính năng mới",
            approved = false,
        )
        repository.saveAgentDraft(draft)

        assertTrue(repository.requestPublish("draft-2") is PublishGateResult.BlockedUnapproved)

        val approveResult = repository.approveDraft("draft-2", expectedText = "Bài giới thiệu tính năng mới")
        assertTrue(approveResult is DraftApprovalResult.Approved)
        assertTrue((approveResult as DraftApprovalResult.Approved).draft.approved)

        val ready = repository.requestPublish("draft-2")
        assertTrue(ready is PublishGateResult.ReadyToPublish)
        assertEquals("draft-2", (ready as PublishGateResult.ReadyToPublish).draft.id)
    }

    @Test
    fun `approveDraft rejects approval if draft content changed after user viewed it`() = runTest {
        val draft = Draft(
            id = "draft-stale",
            topicId = "topic-stale",
            channel = ChannelType.FACEBOOK,
            text = "Nội dung cũ người dùng đã xem",
            approved = false,
        )
        repository.saveAgentDraft(draft)

        // Giả lập agent sinh lại hoặc nơi khác sửa nội dung trước khi người dùng bấm Xác nhận
        repository.updateDraftContent("draft-stale", "Nội dung mới chưa được xem")

        val approveResult = repository.approveDraft(
            draftId = "draft-stale",
            expectedText = "Nội dung cũ người dùng đã xem",
        )
        assertEquals(DraftApprovalResult.ContentChanged, approveResult)

        // Bản nháp trong DB vẫn phải là approved = false và giữ nguyên nội dung mới
        val reloaded = db.draftDao().getById("draft-stale")
        assertFalse(reloaded?.approved ?: true)
        assertEquals("Nội dung mới chưa được xem", reloaded?.text)
    }

    @Test
    fun `updateDraftContent atomically updates text and revokes approval`() = runTest {
        val draft = Draft(
            id = "draft-edit",
            topicId = "topic-edit",
            channel = ChannelType.LINKEDIN,
            text = "Bản đã duyệt",
            approved = false,
        )
        repository.saveAgentDraft(draft)
        repository.approveDraft("draft-edit", expectedText = "Bản đã duyệt")
        assertTrue(repository.requestPublish("draft-edit") is PublishGateResult.ReadyToPublish)

        val updateResult = repository.updateDraftContent("draft-edit", "Bản sau khi chỉnh sửa")
        assertTrue(updateResult is DraftContentUpdateResult.Updated)
        val updated = (updateResult as DraftContentUpdateResult.Updated).draft
        assertEquals("Bản sau khi chỉnh sửa", updated.text)
        assertFalse(updated.approved)
        assertTrue(repository.requestPublish("draft-edit") is PublishGateResult.BlockedUnapproved)
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
        repository.approveDraft("draft-3", expectedText = "Bài cần kiểm tra lại")
        assertTrue(repository.requestPublish("draft-3") is PublishGateResult.ReadyToPublish)

        val revokeResult = repository.revokeApproval("draft-3")
        assertTrue(revokeResult is DraftRevokeResult.Revoked)
        assertFalse((revokeResult as DraftRevokeResult.Revoked).draft.approved)
        assertTrue(repository.requestPublish("draft-3") is PublishGateResult.BlockedUnapproved)
    }

    @Test
    fun `approveDraft and revokeApproval catch DB exceptions and return StorageError instead of crashing`() = runTest {
        val existingEntity = Draft(
            id = "d-err",
            topicId = "topic-err",
            channel = ChannelType.FACEBOOK,
            text = "Nội dung hợp lệ",
            approved = false,
        ).toEntity(updatedAtMillis = 1_000L)

        val failingDao = object : DraftDao() {
            override fun observeAll(): Flow<List<DraftEntity>> = emptyFlow()
            override fun observeByTopic(topicId: String): Flow<List<DraftEntity>> = emptyFlow()
            override suspend fun getById(id: String): DraftEntity? =
                if (id == "d-err") existingEntity else null

            override suspend fun upsert(draft: DraftEntity) {
                throw SQLiteException("Simulated storage full")
            }

            override suspend fun countPostsNotIn(draftId: String, finishedStatus: PostStatus): Int = 0
            override suspend fun deleteAbandonablePosts(
                draftId: String,
                finishedStatus: PostStatus,
                inFlightStatus: PostStatus,
            ): Int = 0

            override suspend fun deleteIfAllPostsIn(id: String, finishedStatus: PostStatus): Int = 0
            override suspend fun insert(draft: DraftEntity) = Unit
            override suspend fun update(draft: DraftEntity): Int = 0
            override suspend fun deleteByChannel(channel: ChannelType) {}
        }

        val repoWithFailingDao = RoomDraftReviewRepository(failingDao)

        val approveErr = repoWithFailingDao.approveDraft("d-err", expectedText = "Nội dung hợp lệ")
        assertTrue(approveErr is DraftApprovalResult.StorageError)

        val revokeErr = repoWithFailingDao.revokeApproval("d-err")
        assertTrue(revokeErr is DraftRevokeResult.StorageError)
    }
}
