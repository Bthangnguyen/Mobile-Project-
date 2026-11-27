package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.core.model.AgentEvent
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
    private val configuration = LlmModelConfiguration(LlmProvider.OPENAI, "gpt-image-1")
    private val title = "Cà phê và những câu chuyện"

    @Test
    fun generatedImagesUseSavedBytesAndChannelSpecificRequests() = runTest {
        val requests = mutableListOf<DraftImageRequest>()
        val images = images(
            api = ImageApi { config, key, request ->
                assertEquals(configuration, config)
                assertEquals("test-key", key)
                requests += request
                byteArrayOf(1, 2, 3)
            },
            generated = SaveGeneratedImage { bytes, request ->
                assertTrue(bytes.contentEquals(byteArrayOf(1, 2, 3)))
                "content://generated/${request.draft.channel}"
            },
            template = RenderImageTemplate { error("Unexpected template") },
        )

        val facebook = draft(ChannelType.FACEBOOK)
        val linkedIn = draft(ChannelType.LINKEDIN)
        assertEquals("content://generated/FACEBOOK", images.create(facebook, title))
        assertEquals("content://generated/LINKEDIN", images.create(linkedIn, title))
        val (fbRequest, liRequest) = requests
        assertEquals(1200 to 630, fbRequest.layout.width to fbRequest.layout.height)
        assertEquals(1200 to 1200, liRequest.layout.width to liRequest.layout.height)
        assertFalse(fbRequest.layout.style == liRequest.layout.style)
        assertTrue(fbRequest.prompt().contains(facebook.text))
        assertTrue(liRequest.prompt().contains(linkedIn.text))
    }

    @Test
    fun noModelOrNoKeySkipsTheImageApi() = runTest {
        var apiCalls = 0
        val api = ImageApi { _, _, _ -> apiCalls++; null }
        val draft = draft(ChannelType.FACEBOOK)

        assertEquals("content://template/FACEBOOK", images(api = api, config = null).create(draft, title))
        assertEquals("content://template/FACEBOOK", images(api = api, key = null).create(draft, title))
        assertEquals(0, apiCalls)
    }

    @Test
    fun apiFailureAndRejectedImageUseTheTemplate() = runTest {
        val draft = draft(ChannelType.LINKEDIN)
        val failed = images(api = ImageApi { _, _, _ -> throw IOException("offline") })
        val invalid = images(api = ImageApi { _, _, _ -> byteArrayOf(42) }, generated = SaveGeneratedImage { _, _ -> null })

        assertEquals("content://template/LINKEDIN", failed.create(draft, title))
        assertEquals("content://template/LINKEDIN", invalid.create(draft, title))
    }

    @Test
    fun timeoutUsesTemplateButCancellationPropagates() = runTest {
        val started = CompletableDeferred<Unit>()
        var renders = 0
        val images = images(
            api = ImageApi { _, _, _ -> started.complete(Unit); awaitCancellation() },
            template = RenderImageTemplate { renders++; "content://template" },
        )
        val draft = draft(ChannelType.FACEBOOK)
        val cancelled = launch { images.create(draft, title) }
        started.await()
        cancelled.cancelAndJoin()
        assertEquals(0, renders)
        assertEquals("content://template", images.create(draft, title))
        assertEquals(1, renders)
    }

    @Test
    fun failedTemplateStillSavesTextAndOtherChannelCompletes() = runTest {
        val images = images(
            api = ImageApi { _, _, _ -> throw IOException("offline") },
            template = RenderImageTemplate { request ->
                if (request.draft.channel == ChannelType.FACEBOOK) throw IOException("template failed")
                "content://template/linkedin"
            },
        )
        val saved = mutableListOf<Draft>()
        val writer = object : LlmClient {
            override fun streamChat(request: ChatRequest) = flowOf(ChatChunk(title))
            override suspend fun classifyComment(comment: Comment): CommentCategory = error("Unexpected classification")
            override suspend fun draftReply(comment: Comment, postText: String): String = error("Unexpected reply")
        }
        val brief = TopicBrief("topic", title, "Chia sẻ", "Bạn đọc", "Thân thiện", ContentLanguage.VI, ChannelType.entries.toSet())
        val agent = ContentAgentImpl(ChannelType.entries.associateWith { channel ->
            DraftChannelSubAgent(channel, writer, images) { saved += it }
        })

        val events = agent.generate(brief).toList()
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

    private fun draft(channel: ChannelType) = Draft(channel.name, "topic", channel, "Nội dung dành cho ${channel.name}")

    private fun images(
        api: ImageApi,
        config: LlmModelConfiguration? = configuration,
        key: String? = "test-key",
        generated: SaveGeneratedImage = SaveGeneratedImage { _, _ -> error("Unexpected storage") },
        template: RenderImageTemplate = RenderImageTemplate { "content://template/${it.draft.channel}" },
    ) = DraftImages(LlmConfigurationSource { config }, ApiKeySource { key }, api, generated, template, timeoutMillis = 100)
}
