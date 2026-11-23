package com.lttbdd.socialagent.feature.review

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewDraftsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeDraftReviewRepository
    private lateinit var viewModel: ReviewDraftsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeDraftReviewRepository()
        viewModel = ReviewDraftsViewModel(fakeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `unapproved draft has canPublish false and publish request is blocked`() = runTest {
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        fakeRepository.saveAgentDraft(
            Draft(
                id = "d1",
                topicId = "t1",
                channel = ChannelType.FACEBOOK,
                text = "Bài nháp AI mới tạo",
                approved = true, // Cố tình truyền true để kiểm chứng guard ép về false
            ),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.drafts.size)
        assertFalse(state.drafts.first().draft.approved)
        assertFalse(state.drafts.first().canPublish)

        viewModel.onPublishRequested("d1")
        advanceUntilIdle()

        val event = viewModel.events.first()
        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_publish_blocked_unapproved),
            event,
        )
        collector.cancel()
    }

    @Test
    fun `approval requires confirmation and unlocks publishing`() = runTest {
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        fakeRepository.saveAgentDraft(
            Draft(
                id = "d2",
                topicId = "t2",
                channel = ChannelType.LINKEDIN,
                text = "Bài LinkedIn cần duyệt",
            ),
        )
        advanceUntilIdle()

        viewModel.onApproveRequested("d2")
        assertEquals(ReviewDraftsEvent.ConfirmApprove("d2"), viewModel.events.first())
        // Chưa xác nhận thì vẫn chưa được duyệt
        assertFalse(viewModel.uiState.value.drafts.first().draft.approved)

        viewModel.onApproveConfirmed("d2")
        advanceUntilIdle()

        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_draft_approved_message),
            viewModel.events.first(),
        )
        assertTrue(viewModel.uiState.value.drafts.first().draft.approved)
        assertTrue(viewModel.uiState.value.drafts.first().canPublish)

        viewModel.onPublishRequested("d2")
        advanceUntilIdle()

        val publishEvent = viewModel.events.first()
        assertTrue(publishEvent is ReviewDraftsEvent.ReadyToPublish)
        assertEquals("d2", (publishEvent as ReviewDraftsEvent.ReadyToPublish).draft.id)
        collector.cancel()
    }

    @Test
    fun `revokeApproval re-locks publishing`() = runTest {
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        fakeRepository.saveAgentDraft(
            Draft(
                id = "d3",
                topicId = "t3",
                channel = ChannelType.FACEBOOK,
                text = "Nội dung bản nháp",
            ),
        )
        fakeRepository.approveDraft("d3")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.drafts.first().canPublish)

        viewModel.onRevokeApproval("d3")
        advanceUntilIdle()

        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_draft_revoked_message),
            viewModel.events.first(),
        )
        assertFalse(viewModel.uiState.value.drafts.first().canPublish)
        collector.cancel()
    }

    @Test
    fun `empty repository exposes empty drafts list`() = runTest {
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.drafts.isEmpty())
        collector.cancel()
    }

    private class FakeDraftReviewRepository : DraftReviewRepository {
        private val items = MutableStateFlow<List<Draft>>(emptyList())

        override fun observeDrafts(): Flow<List<Draft>> = items.asStateFlow()

        override suspend fun saveAgentDraft(draft: Draft): Draft {
            val sanitized = DraftApprovalGuard.sanitizeAgentDraft(draft)
            items.value = items.value.filterNot { it.id == sanitized.id } + sanitized
            return sanitized
        }

        override suspend fun approveDraft(draftId: String): Result<Draft> {
            val current = items.value.firstOrNull { it.id == draftId }
                ?: return Result.failure(NoSuchElementException(draftId))
            if (current.text.isBlank()) return Result.failure(IllegalStateException("blank"))
            val updated = current.copy(approved = true)
            items.value = items.value.map { if (it.id == draftId) updated else it }
            return Result.success(updated)
        }

        override suspend fun revokeApproval(draftId: String): Result<Draft> {
            val current = items.value.firstOrNull { it.id == draftId }
                ?: return Result.failure(NoSuchElementException(draftId))
            val updated = current.copy(approved = false)
            items.value = items.value.map { if (it.id == draftId) updated else it }
            return Result.success(updated)
        }

        override suspend fun requestPublish(draftId: String): PublishGateResult {
            val draft = items.value.firstOrNull { it.id == draftId } ?: return PublishGateResult.NotFound
            return when (DraftApprovalGuard.checkPublishEligibility(draft)) {
                PublishEligibility.Allowed -> PublishGateResult.ReadyToPublish(draft)
                PublishEligibility.BlockedNotApproved -> PublishGateResult.BlockedUnapproved(draft)
                PublishEligibility.BlockedEmptyContent -> PublishGateResult.BlockedEmptyContent(draft)
            }
        }
    }
}
