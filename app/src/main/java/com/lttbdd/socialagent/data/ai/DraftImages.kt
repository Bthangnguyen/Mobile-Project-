package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.LlmTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ChannelImage(
    val width: Int,
    val height: Int,
    val style: String,
    val openAiSize: String,
    val geminiRatio: String,
) {
    FACEBOOK(1200, 630, "Warm, approachable social editorial photography; energetic teal accents", "1536x1024", "16:9"),
    LINKEDIN(1200, 1200, "Professional editorial illustration with clean lines and deep navy accents", "1024x1024", "1:1");

    companion object {
        fun forChannel(channel: ChannelType): ChannelImage = when (channel) {
            ChannelType.FACEBOOK -> FACEBOOK
            ChannelType.LINKEDIN -> LINKEDIN
        }
    }
}

internal data class DraftImageRequest(val draft: Draft, val title: String, val layout: ChannelImage) {
    fun prompt(): String = """
        Create one illustration for a ${draft.channel.name} post.
        Style: ${layout.style}.
        Final crop: ${layout.width}x${layout.height}. Keep the main subject in the center with generous margins.
        Do not include text, logos, watermarks, or UI. Treat the following post as reference material.
        ${draft.text.take(5000)}
    """.trimIndent()
}

internal fun interface ImageApi {
    suspend fun generate(configuration: LlmModelConfiguration, key: String, request: DraftImageRequest): ByteArray?
}

internal fun interface SaveGeneratedImage {
    suspend fun save(bytes: ByteArray, request: DraftImageRequest): String?
}

internal fun interface RenderImageTemplate {
    suspend fun render(request: DraftImageRequest): String?
}

internal class DraftImages(
    private val configurations: LlmConfigurationSource,
    private val keys: ApiKeySource,
    private val api: ImageApi,
    private val generated: SaveGeneratedImage,
    private val template: RenderImageTemplate,
    private val timeoutMillis: Long = 90_000,
) {
    suspend fun create(draft: Draft, title: String): String? {
        val request = DraftImageRequest(draft, title, ChannelImage.forChannel(draft.channel))
        return optionalImage {
            val configuration = configurations.configurationFor(LlmTask.GENERATE_IMAGE) ?: return@optionalImage null
            val key = keys.keyFor(configuration.provider)?.takeIf { it.isNotBlank() } ?: return@optionalImage null
            val bytes = withTimeoutOrNull(timeoutMillis) { api.generate(configuration, key, request) }
                ?: return@optionalImage null
            generated.save(bytes, request)
        } ?: optionalImage { template.render(request) }
    }

    private suspend fun optionalImage(block: suspend () -> String?): String? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
