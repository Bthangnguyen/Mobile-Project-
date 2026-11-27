package com.lttbdd.socialagent.data.review

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DefaultDraftVersionRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var versionStore: FileDraftVersionStore
    private lateinit var reviewRepository: RoomDraftReviewRepository
    private lateinit var versionRepository: DefaultDraftVersionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        versionStore = FileDraftVersionStore(
            baseDir = tempFolder.newFolder("draft_versions"),
            ioDispatcher = Dispatchers.Unconfined,
        )
        reviewRepository = RoomDraftReviewRepository(db.draftDao(), versionStore)
        versionRepository = DefaultDraftVersionRepository(
            database = db,
            draftDao = db.draftDao(),
            postDao = db.postDao(),
            draftVersionStore = versionStore,
        )
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
    }

    @Test
    fun `saveAgentDraft and updateDraftContent record history and restoreVersion revokes approval`() = runTest {
        val aiDraft = Draft(
            id = "d-hist-1",
            topicId = "topic-1",
            channel = ChannelType.FACEBOOK,
            text = "Bản AI ban đầu",
            imageUri = "content://img/1.png",
            imageAltText = "Ảnh minh họa",
            approved = false,
        )
        reviewRepository.saveAgentDraft(aiDraft)
        reviewRepository.updateDraftContent("d-hist-1", "Bản đã sửa tay")
        reviewRepository.approveDraft("d-hist-1", "Bản đã sửa tay")

        val versionsBeforeRestore = versionStore.getVersions("d-hist-1")
        assertEquals(2, versionsBeforeRestore.size)
        assertEquals(DraftVersionSource.MANUAL_EDIT, versionsBeforeRestore[0].source)
        assertEquals(DraftVersionSource.AI_GENERATED, versionsBeforeRestore[1].source)

        val v1Id = versionsBeforeRestore.last().id
        val restoreResult = versionRepository.restoreVersion("d-hist-1", v1Id)

        assertTrue(restoreResult is DraftRestoreResult.Restored)
        val restored = (restoreResult as DraftRestoreResult.Restored).draft
        assertEquals("Bản AI ban đầu", restored.text)
        assertFalse(restored.approved)
        assertEquals(ChannelType.FACEBOOK, restored.channel)
        assertEquals("content://img/1.png", restored.imageUri)
        assertEquals("Ảnh minh họa", restored.imageAltText)

        val versionsAfterRestore = versionStore.getVersions("d-hist-1")
        assertEquals(3, versionsAfterRestore.size)
        assertEquals(DraftVersionSource.RESTORED, versionsAfterRestore.first().source)
        assertEquals("Bản AI ban đầu", versionsAfterRestore.first().text)
    }

    @Test
    fun `updateDraftContent captures INITIAL snapshot for pre-existing draft without prior history`() = runTest {
        val legacyDraft = Draft(
            id = "d-legacy",
            topicId = "topic-legacy",
            channel = ChannelType.LINKEDIN,
            text = "Nội dung cũ trước khi có #62",
            approved = true,
        )
        db.draftDao().upsert(legacyDraft.toEntity(updatedAtMillis = 1_000L))

        reviewRepository.updateDraftContent("d-legacy", "Nội dung mới sau khi sửa")

        val versions = versionStore.getVersions("d-legacy")
        assertEquals(2, versions.size)
        assertEquals(DraftVersionSource.MANUAL_EDIT, versions[0].source)
        assertEquals("Nội dung mới sau khi sửa", versions[0].text)
        assertEquals(DraftVersionSource.INITIAL, versions[1].source)
        assertEquals("Nội dung cũ trước khi có #62", versions[1].text)
    }

    @Test
    fun `restoreVersion blocks when draft has QUEUED, PUBLISHING, or HANDED_OFF post`() = runTest {
        val draft = Draft(
            id = "d-active",
            topicId = "topic-active",
            channel = ChannelType.FACEBOOK,
            text = "Bản v1",
            approved = false,
        )
        reviewRepository.saveAgentDraft(draft)
        reviewRepository.updateDraftContent("d-active", "Bản v2")
        val v1Id = versionStore.getVersions("d-active").last().id

        db.postDao().insertIfAbsent(
            PostEntity(
                id = "post-active",
                draftId = "d-active",
                channel = ChannelType.FACEBOOK,
                status = PostStatus.HANDED_OFF,
                idempotencyKey = "idem-active",
                remotePostId = null,
                url = null,
                errorMessage = null,
                attemptCount = 1,
                createdAtMillis = 1_000L,
                updatedAtMillis = 1_000L,
            ),
        )

        assertTrue(versionRepository.hasActivePublishingPosts("d-active"))
        assertEquals(DraftRestoreResult.BlockedByActivePublish, versionRepository.restoreVersion("d-active", v1Id))
        assertEquals("Bản v2", versionRepository.getDraft("d-active")?.text)
    }

    @Test
    fun `restoreVersion returns DraftNotFound, VersionNotFound, and StorageError safely`() = runTest {
        assertEquals(
            DraftRestoreResult.DraftNotFound,
            versionRepository.restoreVersion("missing-draft", "v-1"),
        )

        val draft = Draft(
            id = "d-exists",
            topicId = "topic-e",
            channel = ChannelType.FACEBOOK,
            text = "Bản gốc",
        )
        reviewRepository.saveAgentDraft(draft)
        assertEquals(
            DraftRestoreResult.VersionNotFound,
            versionRepository.restoreVersion("d-exists", "missing-version"),
        )

        val existingEntity = draft.toEntity(updatedAtMillis = 1_000L)
        val failingDao = object : DraftDao() {
            override fun observeAll(): Flow<List<DraftEntity>> = emptyFlow()
            override fun observeByTopic(topicId: String): Flow<List<DraftEntity>> = emptyFlow()
            override suspend fun getById(id: String): DraftEntity? =
                if (id == "d-exists") existingEntity else null

            override suspend fun upsert(draft: DraftEntity) {
                throw SQLiteException("Simulated DB write failure")
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
            override suspend fun deleteByChannel(channel: ChannelType) = Unit
        }

        val failingVersionRepo = DefaultDraftVersionRepository(
            database = db,
            draftDao = failingDao,
            postDao = db.postDao(),
            draftVersionStore = versionStore,
        )
        val versionsBeforeFailure = versionStore.getVersions("d-exists")
        val existingVersionId = versionsBeforeFailure.first().id
        val errorResult = failingVersionRepo.restoreVersion("d-exists", existingVersionId)
        assertTrue(errorResult is DraftRestoreResult.StorageError)
        // Room thất bại → không được tạo mục RESTORED giả trong tệp lịch sử
        assertEquals(versionsBeforeFailure, versionStore.getVersions("d-exists"))
    }

    @Test
    fun `restoreVersion returns RestoredWithoutHistory when Room update succeeds but history write fails`() = runTest {
        val draft = Draft(
            id = "d-hist-fail",
            topicId = "topic-hf",
            channel = ChannelType.FACEBOOK,
            text = "Bản v1",
            approved = true,
        )
        reviewRepository.saveAgentDraft(draft)
        reviewRepository.updateDraftContent("d-hist-fail", "Bản v2")
        val v1Id = versionStore.getVersions("d-hist-fail").last().id

        val failingWriteStore = object : DraftVersionStore by versionStore {
            override suspend fun recordVersion(
                draftId: String,
                text: String,
                source: DraftVersionSource,
                createdAtMillis: Long,
            ): DraftVersion {
                throw java.io.IOException("Simulated disk full")
            }
        }
        val repoWithFailingStore = DefaultDraftVersionRepository(
            database = db,
            draftDao = db.draftDao(),
            postDao = db.postDao(),
            draftVersionStore = failingWriteStore,
        )

        val result = repoWithFailingStore.restoreVersion("d-hist-fail", v1Id)
        assertTrue(result is DraftRestoreResult.RestoredWithoutHistory)
        val restored = (result as DraftRestoreResult.RestoredWithoutHistory).draft
        assertEquals("Bản v1", restored.text)
        assertFalse(restored.approved)
    }

    @Test
    fun `saveAgentDraft and manual updateDraftContent surface history write failure instead of hiding it`() = runTest {
        val failingWriteStore = object : DraftVersionStore by NoOpDraftVersionStore {
            override suspend fun recordVersion(
                draftId: String,
                text: String,
                source: DraftVersionSource,
                createdAtMillis: Long,
            ): DraftVersion {
                throw java.io.IOException("Simulated disk error")
            }
        }
        val reviewRepoWithFailingStore = RoomDraftReviewRepository(db.draftDao(), failingWriteStore)

        // Luồng AI tạo bản nháp: Room vẫn lưu approved = false nhưng báo DraftHistoryWriteException
        val aiResult = reviewRepoWithFailingStore.saveAgentDraft(
            Draft(
                id = "d-ai-fail",
                topicId = "t-ai",
                channel = ChannelType.FACEBOOK,
                text = "Nội dung AI",
                approved = true,
            ),
        )
        assertTrue(aiResult.isFailure)
        val historyException = aiResult.exceptionOrNull() as DraftHistoryWriteException
        assertEquals("d-ai-fail", historyException.savedDraft.id)
        assertFalse(historyException.savedDraft.approved)
        assertEquals("Nội dung AI", db.draftDao().getById("d-ai-fail")?.text)

        // Luồng sửa thủ công: trả UpdatedWithoutHistory và RoomDraftEditorRepository ánh xạ SavedWithoutHistory
        val editorRepo = com.lttbdd.socialagent.feature.review.RoomDraftEditorRepository(
            draftDao = db.draftDao(),
            postDao = db.postDao(),
            draftReviewRepository = reviewRepoWithFailingStore,
        )
        val saveResult = editorRepo.saveEditedDraft("d-ai-fail", "Nội dung sửa tay")
        assertTrue(saveResult is com.lttbdd.socialagent.feature.review.DraftSaveResult.SavedWithoutHistory)
        val savedDraft = (saveResult as com.lttbdd.socialagent.feature.review.DraftSaveResult.SavedWithoutHistory).draft
        assertEquals("Nội dung sửa tay", savedDraft.text)
        assertFalse(savedDraft.approved)
    }
}
