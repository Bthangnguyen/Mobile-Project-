package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ChatChunk
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.CommentCategory
import com.lttbdd.socialagent.core.model.ContentLanguage
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.TopicBrief
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftImagesTest {
    private val configuration = LlmModelConfiguration(LlmProvider.OPENAI, "gpt-image-2.5-flare")
    private val title = "Cà phê và những câu chuyện"

    @Test
    fun generatedImagesUseSavedBytesAndChannelSpecificRequests() = runTest {
        val requests = mutableListOf<ChannelImageRequest>()
        val images = images(
            api = ImageApi { config, key, request ->
                assertEquals(configuration, config)
                assertEquals("test-key", key)
                requests += request
                byteArrayOf(1, 2, 3)
            },
            generated = SaveGeneratedImage { bytes, request ->
                assertTrue(bytes.contentEquals(byteArrayOf(1, 2, 3)))
                "content://generated/${request.channel}"
            },
            template = RenderImageTemplate { error("Unexpected template") },
        )

        val facebook = request(ChannelType.FACEBOOK)
        val linkedIn = request(ChannelType.LINKEDIN)
        assertEquals("content://generated/FACEBOOK", images.create(facebook))
        assertEquals("content://generated/LINKEDIN", images.create(linkedIn))
        val (fbRequest, liRequest) = requests
        assertEquals(1200 to 630, fbRequest.layout.width to fbRequest.layout.height)
        assertEquals(1200 to 1200, liRequest.layout.width to liRequest.layout.height)
        assertFalse(fbRequest.layout.style == liRequest.layout.style)
        assertTrue(fbRequest.prompt().contains(facebook.description))
        assertFalse(fbRequest.prompt().contains(facebook.postText))
        assertTrue(liRequest.prompt().contains(linkedIn.description))
    }

    @Test
    fun noModelOrNoKeySkipsTheImageApi() = runTest {
        var apiCalls = 0
        val api = ImageApi { _, _, _ -> apiCalls++; null }
        val draft = request(ChannelType.FACEBOOK)

        assertEquals("content://template/FACEBOOK", images(api = api, config = null).create(draft))
        assertEquals("content://template/FACEBOOK", images(api = api, key = null).create(draft))
        assertEquals(0, apiCalls)
    }

    @Test
    fun apiFailureAndRejectedImageUseTheTemplate() = runTest {
        val draft = request(ChannelType.LINKEDIN)
        val failed = images(api = ImageApi { _, _, _ -> throw IOException("offline") })
        val invalid = images(api = ImageApi { _, _, _ -> byteArrayOf(42) }, generated = SaveGeneratedImage { _, _ -> null })

        assertEquals("content://template/LINKEDIN", failed.create(draft))
        assertEquals("content://template/LINKEDIN", invalid.create(draft))
    }

    @Test
    fun timeoutUsesTemplateButCancellationPropagates() = runTest {
        val started = CompletableDeferred<Unit>()
        var renders = 0
        val images = images(
            api = ImageApi { _, _, _ -> started.complete(Unit); awaitCancellation() },
            template = RenderImageTemplate { renders++; "content://template" },
        )
        val draft = request(ChannelType.FACEBOOK)
        val cancelled = launch { images.create(draft) }
        started.await()
        cancelled.cancelAndJoin()
        assertEquals(0, renders)
        assertEquals("content://template", images.create(draft))
        assertEquals(1, renders)
    }

    @Test
    fun failedTemplateStillSavesTextAndOtherChannelCompletes() = runTest {
        val images = images(
            api = ImageApi { _, _, _ -> throw IOException("offline") },
            template = RenderImageTemplate { request ->
                if (request.channel == ChannelType.FACEBOOK) throw IOException("template failed")
                "content://template/linkedin"
            },
        )
        val saved = mutableListOf<Draft>()
        val agent = SavingContentAgent(ContentAgentImpl(llmChannelSubAgents(writer, images))) { draft ->
            draft.also { saved += it }
        }

        val events = mutableListOf<AgentEvent>()
        agent.generate(brief()).collect { event ->
            if (event is AgentEvent.ChannelDone) assertTrue(event.draft in saved)
            events += event
        }
        for (channel in ChannelType.entries) {
            assertEquals(AgentStep.entries, events.filterIsInstance<AgentEvent.StepStarted>()
                .filter { it.channel == channel }.map { it.step })
        }
        assertEquals(2, events.filterIsInstance<AgentEvent.ChannelDone>().size)
        assertTrue(events.filterIsInstance<AgentEvent.ChannelFailed>().isEmpty())
        val byChannel = saved.associateBy { it.channel }
        val facebook = checkNotNull(byChannel[ChannelType.FACEBOOK])
        val linkedin = checkNotNull(byChannel[ChannelType.LINKEDIN])
        assertEquals(title, facebook.text)
        assertNull(facebook.imageUri)
        assertEquals("content://template/linkedin", linkedin.imageUri)
        assertTrue(saved.none { it.approved })
    }

    @Test
    fun missingWritingKeyFailsWithoutCreatingAnImageOrSaving() = runTest {
        val images = ChannelImageGenerator { throw AssertionError("Image generation should not start") }
        val agent = SavingContentAgent(ContentAgentImpl(llmChannelSubAgents(missingKeyWriter, images))) {
            throw AssertionError("No draft should be saved")
        }

        val events = agent.generate(brief(setOf(ChannelType.FACEBOOK))).toList()

        assertEquals(
            listOf(AgentEvent.ChannelFailed(ChannelType.FACEBOOK, AgentStep.OUTLINE, "LLM_MISSING_API_KEY")),
            events.filterIsInstance<AgentEvent.ChannelFailed>(),
        )
        assertTrue(events.filterIsInstance<AgentEvent.ChannelDone>().isEmpty())
    }

    private val writer = object : LlmClient {
        override fun streamChat(request: ChatRequest) = flowOf(ChatChunk(title))
        override suspend fun classifyComment(comment: Comment): CommentCategory = error("Unexpected classification")
        override suspend fun draftReply(comment: Comment, postText: String): String = error("Unexpected reply")
    }
    private val missingKeyWriter = object : LlmClient by writer {
        override fun streamChat(request: ChatRequest) = flow<ChatChunk> {
            throw LlmException(LlmFailure.MISSING_API_KEY)
        }
    }

    private fun brief(channels: Set<ChannelType> = ChannelType.entries.toSet()) =
        TopicBrief("topic", title, "Chia sẻ", "Bạn đọc", "Thân thiện", ContentLanguage.VI, channels)

    private fun request(channel: ChannelType) =
        ChannelImageRequest("topic", channel, "Coffee cup on a wooden table", "Nội dung dành cho ${channel.name}", title)

    private fun images(
        api: ImageApi,
        config: LlmModelConfiguration? = configuration,
        key: String? = "test-key",
        generated: SaveGeneratedImage = SaveGeneratedImage { _, _ -> error("Unexpected storage") },
        template: RenderImageTemplate = RenderImageTemplate { "content://template/${it.channel}" },
    ) = DraftImages(LlmConfigurationSource { config }, ApiKeySource { key }, api, generated, template, timeoutMillis = 100)
}
