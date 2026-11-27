package com.lttbdd.socialagent.feature.agent

import com.lttbdd.socialagent.core.ai.ContentAgent
import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ContentLanguage
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.RewriteInstruction
import com.lttbdd.socialagent.core.model.TokenUsage
import com.lttbdd.socialagent.core.model.TopicBrief
import com.lttbdd.socialagent.core.ui.components.ai.AgentStepStatus
import com.lttbdd.socialagent.data.ai.ChannelFailureReason
import com.lttbdd.socialagent.data.ai.LlmFailure
import com.lttbdd.socialagent.data.review.DraftApprovalGuard
import com.lttbdd.socialagent.data.review.DraftApprovalResult
import com.lttbdd.socialagent.data.review.DraftContentUpdateResult
import com.lttbdd.socialagent.data.review.DraftReviewRepository
import com.lttbdd.socialagent.data.review.DraftRevokeResult
import com.lttbdd.socialagent.data.review.PublishGateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
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
class AgentProgressViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var draftRepository: FakeDraftReviewRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        draftRepository = FakeDraftReviewRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `tracks brief ready, step progress, and multi-channel completion`() = runTest(testDispatcher) {
        val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 32)
        val agent = ControllableContentAgent(generateFlow = { events })
        val viewModel = AgentProgressViewModel(agent, draftRepository)

        val brief = sampleBrief(setOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN))
        viewModel.startGeneration(brief)
        advanceUntilIdle()

        assertEquals(AgentRunStatus.RUNNING, viewModel.uiState.value.runStatus)
        assertEquals(AgentStepStatus.IN_PROGRESS, viewModel.uiState.value.briefStatus)
        assertEquals(2, viewModel.uiState.value.channels.size)
        assertEquals(ChannelType.FACEBOOK, viewModel.uiState.value.channels[0].channel)
        assertEquals(ChannelType.LINKEDIN, viewModel.uiState.value.channels[1].channel)

        events.emit(AgentEvent.BriefReady("Tóm tắt brief chung"))
        events.emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.OUTLINE))
        events.emit(AgentEvent.TextDelta(ChannelType.FACEBOOK, AgentStep.OUTLINE, "- Ý 1\n"))
        events.emit(AgentEvent.TextDelta(ChannelType.FACEBOOK, AgentStep.OUTLINE, "- Ý 2"))
        advanceUntilIdle()

        val midState = viewModel.uiState.value
        assertEquals(AgentStepStatus.COMPLETED, midState.briefStatus)
        assertEquals("Tóm tắt brief chung", midState.briefSummary)

        val fbMid = midState.channels.first { it.channel == ChannelType.FACEBOOK }
        assertEquals(ChannelRunStatus.RUNNING, fbMid.status)
        assertEquals(AgentStepStatus.IN_PROGRESS, fbMid.stepStatuses[AgentStep.OUTLINE])
        assertEquals(AgentStepStatus.WAITING, fbMid.stepStatuses[AgentStep.DRAFT])
        assertEquals("- Ý 1\n- Ý 2", fbMid.streamedContent)
        assertEquals(AgentStep.OUTLINE, fbMid.streamingStep)

        events.emit(AgentEvent.StepFinished(ChannelType.FACEBOOK, AgentStep.OUTLINE, TokenUsage(10, 20, 0.0)))
        advanceUntilIdle()

        val fbAfterOutline = viewModel.uiState.value.channels.first { it.channel == ChannelType.FACEBOOK }
        assertEquals(AgentStepStatus.COMPLETED, fbAfterOutline.stepStatuses[AgentStep.OUTLINE])
        assertEquals(30, fbAfterOutline.totalTokens)
    }

    @Test
    fun `streaming transitions smoothly across outline, draft, self-review and keeps image prompt separate`() =
        runTest(testDispatcher) {
            val agent = ControllableContentAgent(
                generateFlow = {
                    flow {
                        emit(AgentEvent.BriefReady("Brief chung"))
                        emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.OUTLINE))
                        emit(AgentEvent.TextDelta(ChannelType.FACEBOOK, AgentStep.OUTLINE, "Dàn ý A"))
                        emit(AgentEvent.StepFinished(ChannelType.FACEBOOK, AgentStep.OUTLINE, TokenUsage(5, 5, 0.0)))

                        emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.DRAFT))
                        emit(AgentEvent.TextDelta(ChannelType.FACEBOOK, AgentStep.DRAFT, "Bản nháp "))
                        emit(AgentEvent.TextDelta(ChannelType.FACEBOOK, AgentStep.DRAFT, "đầu tiên."))
                        emit(AgentEvent.StepFinished(ChannelType.FACEBOOK, AgentStep.DRAFT, TokenUsage(10, 15, 0.0)))

                        emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.SELF_REVIEW))
                        emit(AgentEvent.TextDelta(ChannelType.FACEBOOK, AgentStep.SELF_REVIEW, "Bài đã rà "))
                        emit(AgentEvent.TextDelta(ChannelType.FACEBOOK, AgentStep.SELF_REVIEW, "chuẩn kênh."))
                        emit(AgentEvent.StepFinished(ChannelType.FACEBOOK, AgentStep.SELF_REVIEW, TokenUsage(12, 18, 0.0)))

                        emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.IMAGE_PROMPT))
                        emit(
                            AgentEvent.TextDelta(
                                ChannelType.FACEBOOK,
                                AgentStep.IMAGE_PROMPT,
                                "A clean modern workspace.",
                            ),
                        )
                        emit(AgentEvent.StepFinished(ChannelType.FACEBOOK, AgentStep.IMAGE_PROMPT, TokenUsage(8, 7, 0.0)))

                        emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.IMAGE))
                        emit(AgentEvent.StepFinished(ChannelType.FACEBOOK, AgentStep.IMAGE, TokenUsage.ZERO))

                        emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.PACKAGE))
                        emit(AgentEvent.StepFinished(ChannelType.FACEBOOK, AgentStep.PACKAGE, TokenUsage.ZERO))

                        emit(
                            AgentEvent.ChannelDone(
                                Draft(
                                    id = "brief-1-facebook",
                                    topicId = "brief-1",
                                    channel = ChannelType.FACEBOOK,
                                    text = "Bài đã rà chuẩn kênh.",
                                    approved = true, // Kiểm chứng repository ép về false (Quy tắc 6)
                                ),
                            ),
                        )
                    }
                },
            )
            val viewModel = AgentProgressViewModel(agent, draftRepository)

            viewModel.startGeneration(sampleBrief(setOf(ChannelType.FACEBOOK)))
            advanceUntilIdle()

            val finalState = viewModel.uiState.value
            assertEquals(AgentRunStatus.COMPLETED, finalState.runStatus)
            assertTrue(finalState.hasAnySavedDraft)

            val fb = finalState.channels.single()
            assertEquals(ChannelRunStatus.DONE, fb.status)
            assertEquals("Bài đã rà chuẩn kênh.", fb.streamedContent)
            assertEquals("A clean modern workspace.", fb.imageDescriptionPreview)
            assertEquals("brief-1-facebook", fb.savedDraftId)
            assertFalse(fb.draftSaveError)
            assertEquals(80, fb.totalTokens)
            AgentStep.entries.forEach { step ->
                assertEquals(AgentStepStatus.COMPLETED, fb.stepStatuses[step])
            }

            // Đảm bảo bản nháp được lưu ở trạng thái chưa duyệt (Quy tắc 6)
            assertEquals(1, draftRepository.savedDrafts.size)
            assertFalse(draftRepository.savedDrafts.single().approved)
        }

    @Test
    fun `channel failure isolates error to failed channel while other channel completes`() =
        runTest(testDispatcher) {
            val agent = ControllableContentAgent(
                generateFlow = {
                    flow {
                        emit(AgentEvent.BriefReady("Brief song song"))
                        emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.DRAFT))
                        emit(
                            AgentEvent.ChannelFailed(
                                channel = ChannelType.FACEBOOK,
                                step = AgentStep.DRAFT,
                                reason = ChannelFailureReason.fromLlm(LlmFailure.RATE_LIMIT),
                            ),
                        )

                        emit(AgentEvent.StepStarted(ChannelType.LINKEDIN, AgentStep.DRAFT))
                        emit(AgentEvent.TextDelta(ChannelType.LINKEDIN, AgentStep.DRAFT, "LinkedIn post"))
                        emit(AgentEvent.StepFinished(ChannelType.LINKEDIN, AgentStep.DRAFT, TokenUsage(5, 10, 0.0)))
                        emit(
                            AgentEvent.ChannelDone(
                                Draft(
                                    id = "brief-1-linkedin",
                                    topicId = "brief-1",
                                    channel = ChannelType.LINKEDIN,
                                    text = "LinkedIn post",
                                ),
                            ),
                        )
                    }
                },
            )
            val viewModel = AgentProgressViewModel(agent, draftRepository)

            viewModel.startGeneration(sampleBrief(setOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN)))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(AgentRunStatus.PARTIAL_FAILURE, state.runStatus)
            assertEquals(1, state.completedChannelCount)
            assertEquals(1, state.failedChannelCount)

            val fb = state.channels.first { it.channel == ChannelType.FACEBOOK }
            assertEquals(ChannelRunStatus.FAILED, fb.status)
            assertEquals(AgentStepStatus.FAILED, fb.stepStatuses[AgentStep.DRAFT])
            assertEquals(AgentStep.DRAFT, fb.failedStep)
            assertEquals("LLM_RATE_LIMIT", fb.failureReasonCode)
            assertNull(fb.savedDraftId)

            val li = state.channels.first { it.channel == ChannelType.LINKEDIN }
            assertEquals(ChannelRunStatus.DONE, li.status)
            assertEquals("brief-1-linkedin", li.savedDraftId)
            assertEquals("LinkedIn post", li.streamedContent)
        }

    @Test
    fun `surfaces draftSaveError when Room save fails after channel finishes`() = runTest(testDispatcher) {
        draftRepository.failOnSave = true
        val agent = ControllableContentAgent(
            generateFlow = {
                flow {
                    emit(AgentEvent.BriefReady("Brief"))
                    emit(
                        AgentEvent.ChannelDone(
                            Draft(
                                id = "brief-1-facebook",
                                topicId = "brief-1",
                                channel = ChannelType.FACEBOOK,
                                text = "Nội dung đã sinh",
                            ),
                        ),
                    )
                }
            },
        )
        val viewModel = AgentProgressViewModel(agent, draftRepository)

        viewModel.startGeneration(sampleBrief(setOf(ChannelType.FACEBOOK)))
        advanceUntilIdle()

        val fb = viewModel.uiState.value.channels.single()
        assertEquals(ChannelRunStatus.DONE, fb.status)
        assertNull(fb.savedDraftId)
        assertTrue(fb.draftSaveError)
        assertFalse(viewModel.uiState.value.hasAnySavedDraft)
    }

    @Test
    fun `duplicate startGeneration with same brief id does not restart generation`() = runTest(testDispatcher) {
        var generateCalls = 0
        val agent = ControllableContentAgent(
            generateFlow = {
                generateCalls++
                flow {
                    emit(AgentEvent.BriefReady("Brief"))
                    emit(
                        AgentEvent.ChannelDone(
                            Draft(
                                id = "brief-1-facebook",
                                topicId = "brief-1",
                                channel = ChannelType.FACEBOOK,
                                text = "Post",
                            ),
                        ),
                    )
                }
            },
        )
        val viewModel = AgentProgressViewModel(agent, draftRepository)
        val brief = sampleBrief(setOf(ChannelType.FACEBOOK))

        viewModel.startGeneration(brief)
        advanceUntilIdle()
        viewModel.startGeneration(brief)
        advanceUntilIdle()

        assertEquals(1, generateCalls)
    }

    @Test
    fun `unexpected flow exception marks unfinished channels as failed`() = runTest(testDispatcher) {
        val agent = ControllableContentAgent(
            generateFlow = {
                flow {
                    emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.OUTLINE))
                    throw IllegalStateException("Unexpected stream error")
                }
            },
        )
        val viewModel = AgentProgressViewModel(agent, draftRepository)

        viewModel.startGeneration(sampleBrief(setOf(ChannelType.FACEBOOK)))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AgentRunStatus.FAILED, state.runStatus)
        val fb = state.channels.single()
        assertEquals(ChannelRunStatus.FAILED, fb.status)
        assertEquals(AgentStep.OUTLINE, fb.failedStep)
        assertEquals(ChannelFailureReason.UNKNOWN, fb.failureReasonCode)
    }

    @Test
    fun `onMissingBrief sets missingBrief flag safely`() = runTest(testDispatcher) {
        val agent = ControllableContentAgent(generateFlow = { emptyFlow() })
        val viewModel = AgentProgressViewModel(agent, draftRepository)

        viewModel.onMissingBrief()

        assertTrue(viewModel.uiState.value.missingBrief)
        assertEquals(AgentRunStatus.IDLE, viewModel.uiState.value.runStatus)
    }

    private fun sampleBrief(channels: Set<ChannelType>) = TopicBrief(
        id = "brief-1",
        topic = "Ra mắt tính năng AI mới",
        goal = "Tăng tương tác",
        audience = "Lập trình viên",
        tone = "professional",
        language = ContentLanguage.VI,
        channels = channels,
    )

    private class ControllableContentAgent(
        private val generateFlow: (TopicBrief) -> Flow<AgentEvent>,
    ) : ContentAgent {
        override fun generate(brief: TopicBrief): Flow<AgentEvent> = generateFlow(brief)
        override fun regenerate(brief: TopicBrief, channel: ChannelType): Flow<AgentEvent> = emptyFlow()
        override suspend fun rewriteParagraph(
            draft: Draft,
            paragraph: String,
            instruction: RewriteInstruction,
        ): String = paragraph

        override suspend fun regenerateImage(draft: Draft): String = ""
    }

    private class FakeDraftReviewRepository : DraftReviewRepository {
        val savedDrafts = mutableListOf<Draft>()
        var failOnSave: Boolean = false

        override fun observeDrafts(): Flow<List<Draft>> = emptyFlow()

        override suspend fun saveAgentDraft(draft: Draft): Result<Draft> {
            if (failOnSave) return Result.failure(IllegalStateException("Disk full"))
            val sanitized = DraftApprovalGuard.sanitizeAgentDraft(draft)
            savedDrafts.add(sanitized)
            return Result.success(sanitized)
        }

        override suspend fun updateDraftContent(draftId: String, newText: String): DraftContentUpdateResult =
            DraftContentUpdateResult.NotFound

        override suspend fun approveDraft(draftId: String, expectedText: String): DraftApprovalResult =
            DraftApprovalResult.NotFound

        override suspend fun revokeApproval(draftId: String): DraftRevokeResult = DraftRevokeResult.NotFound

        override suspend fun requestPublish(draftId: String): PublishGateResult = PublishGateResult.NotFound
    }
}
