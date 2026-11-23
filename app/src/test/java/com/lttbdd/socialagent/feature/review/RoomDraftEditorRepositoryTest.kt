package com.lttbdd.socialagent.feature.review

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.toEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
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
        repository = RoomDraftEditorRepository(db.draftDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `saveEditedDraft persists updated text and resets approved to false`() = runTest {
        val original = Draft(
            id = "d-edit-1",
            topicId = "topic-1",
            channel = ChannelType.LINKEDIN,
            text = "Nội dung cũ",
            approved = true,
        )
        db.draftDao().upsert(original.toEntity(updatedAtMillis = 1_000L))

        val result = repository.saveEditedDraft(original, "Nội dung đã sửa tay")
        assertTrue(result.isSuccess)
        val saved = result.getOrThrow()
        assertEquals("Nội dung đã sửa tay", saved.text)
        assertFalse(saved.approved)

        val reloaded = repository.getDraft("d-edit-1")
        assertEquals("Nội dung đã sửa tay", reloaded?.text)
        assertFalse(reloaded?.approved ?: true)
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

        val blankResult = repository.saveEditedDraft(original, "   ")
        assertTrue(blankResult.isFailure)

        val overLimitResult = repository.saveEditedDraft(
            original,
            "x".repeat(ChannelType.LINKEDIN.maxChars + 1),
        )
        assertTrue(overLimitResult.isFailure)

        val reloaded = repository.getDraft("d-edit-2")
        assertEquals("Bản gốc hợp lệ", reloaded?.text)
    }
}
