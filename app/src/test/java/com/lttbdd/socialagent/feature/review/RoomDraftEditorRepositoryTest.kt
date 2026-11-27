package com.lttbdd.socialagent.feature.review

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.database.entity.PostEntity
import com.lttbdd.socialagent.core.database.toEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.data.review.RoomDraftReviewRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
class RoomDraftEditorRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoomDraftEditorRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomDraftEditorRepository(
            draftDao = db.draftDao(),
            postDao = db.postDao(),
            draftReviewRepository = RoomDraftReviewRepository(db, db.draftDao()),
        )
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
    }

    @Test
    fun `saveEditedDraft preserves channel and avoids unique index conflict when topic has both channels`() = runTest {
        val fbDraft = Draft(
            id = "d-fb",
            topicId = "topic-shared",
            channel = ChannelType.FACEBOOK,
            text = "Bài Facebook gốc",
            approved = true,
        )
        val liDraft = Draft(
            id = "d-li",
            topicId = "topic-shared",
            channel = ChannelType.LINKEDIN,
            text = "Bài LinkedIn gốc",
            approved = true,
        )
        db.draftDao().upsert(fbDraft.toEntity(updatedAtMillis = 1_000L))
        db.draftDao().upsert(liDraft.toEntity(updatedAtMillis = 1_000L))

        val longFacebookText = "a".repeat(3_500)
        val result = repository.saveEditedDraft("d-fb", longFacebookText)

        assertTrue(result is DraftSaveResult.Saved)
        val saved = (result as DraftSaveResult.Saved).draft
        assertEquals(ChannelType.FACEBOOK, saved.channel)
        assertEquals(longFacebookText, saved.text)
        assertFalse(saved.approved)

        // Bản LinkedIn cùng topic không bị ảnh hưởng
        val reloadedLi = repository.getDraft("d-li")
        assertEquals(ChannelType.LINKEDIN, reloadedLi?.channel)
        assertEquals("Bài LinkedIn gốc", reloadedLi?.text)
        assertTrue(reloadedLi?.approved == true)
    }

    @Test
    fun `saveEditedDraft preserves latest imageUri and imageAltText updated concurrently in DB`() = runTest {
        val original = Draft(
            id = "d-img",
            topicId = "topic-img",
            channel = ChannelType.FACEBOOK,
            text = "Nội dung cũ",
            imageUri = null,
            imageAltText = null,
            approved = true,
        )
        db.draftDao().upsert(original.toEntity(updatedAtMillis = 1_000L))

        // Giả lập luồng khác cập nhật ảnh trong lúc người dùng đang sửa chữ
        db.draftDao().upsert(
            original.copy(
                imageUri = "content://images/new.png",
                imageAltText = "Ảnh minh họa mới",
            ).toEntity(updatedAtMillis = 2_000L),
        )

        val result = repository.saveEditedDraft("d-img", "Nội dung mới")
        assertTrue(result is DraftSaveResult.Saved)
        val saved = (result as DraftSaveResult.Saved).draft
        assertEquals("Nội dung mới", saved.text)
        assertEquals("content://images/new.png", saved.imageUri)
        assertEquals("Ảnh minh họa mới", saved.imageAltText)
        assertFalse(saved.approved)
    }

    @Test
    fun `saveEditedDraft blocks editing when draft has QUEUED or PUBLISHING post`() = runTest {
        val draft = Draft(
            id = "d-pub",
            topicId = "topic-pub",
            channel = ChannelType.FACEBOOK,
            text = "Nội dung đang chờ đăng",
            approved = true,
        )
        db.draftDao().upsert(draft.toEntity(updatedAtMillis = 1_000L))
        db.postDao().insertIfAbsent(
            PostEntity(
                id = "post-1",
                draftId = "d-pub",
                channel = ChannelType.FACEBOOK,
                status = PostStatus.QUEUED,
                idempotencyKey = "idem-1",
                remotePostId = null,
                url = null,
                errorMessage = null,
                attemptCount = 0,
                createdAtMillis = 1_000L,
                updatedAtMillis = 1_000L,
            ),
        )

        assertTrue(repository.hasActivePublishingPosts("d-pub"))
        val result = repository.saveEditedDraft("d-pub", "Sửa khi đang chờ đăng")
        assertEquals(DraftSaveResult.BlockedByActivePublish, result)

        val reloaded = repository.getDraft("d-pub")
        assertEquals("Nội dung đang chờ đăng", reloaded?.text)
        assertTrue(reloaded?.approved == true)
    }

    @Test
    fun `saveEditedDraft rejects blank text and over-limit text without overwriting DB`() = runTest {
        val original = Draft(
            id = "d-edit-2",
            topicId = "topic-2",
            channel = ChannelType.LINKEDIN,
            text = "Bản gốc hợp lệ",
            approved = false,
        )
        db.draftDao().upsert(original.toEntity(updatedAtMillis = 1_000L))

        assertEquals(DraftSaveResult.BlockedBlankContent, repository.saveEditedDraft("d-edit-2", "   "))
        assertEquals(
            DraftSaveResult.BlockedOverLimit,
            repository.saveEditedDraft("d-edit-2", "x".repeat(ChannelType.LINKEDIN.maxChars + 1)),
        )

        val reloaded = repository.getDraft("d-edit-2")
        assertEquals("Bản gốc hợp lệ", reloaded?.text)
    }

    @Test
    fun `saveEditedDraft catches DB exceptions and returns StorageError instead of crashing`() = runTest {
        val existingEntity = Draft(
            id = "d-fail",
            topicId = "topic-fail",
            channel = ChannelType.FACEBOOK,
            text = "Nội dung cũ",
            approved = false,
        ).toEntity(updatedAtMillis = 1_000L)

        val failingDao = object : DraftDao() {
            override fun observeAll(): Flow<List<DraftEntity>> = emptyFlow()
            override fun observeByTopic(topicId: String): Flow<List<DraftEntity>> = emptyFlow()
            override suspend fun getById(id: String): DraftEntity? =
                if (id == "d-fail") existingEntity else null

            override suspend fun upsert(draft: DraftEntity) {
                throw SQLiteException("Simulated disk I/O error")
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

        val repoWithFailingDao = RoomDraftEditorRepository(
            draftDao = failingDao,
            postDao = db.postDao(),
            draftReviewRepository = RoomDraftReviewRepository(db, failingDao),
        )
        val result = repoWithFailingDao.saveEditedDraft("d-fail", "Nội dung mới")

        assertTrue(result is DraftSaveResult.StorageError)
        assertTrue((result as DraftSaveResult.StorageError).cause is SQLiteException)
    }
}
