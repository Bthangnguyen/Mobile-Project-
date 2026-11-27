package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChannelType
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

internal val ChannelImageRequest.layout: ChannelImage
    get() = ChannelImage.forChannel(channel)

internal fun ChannelImageRequest.prompt(): String = """
    Create one illustration for a ${channel.name} post.
    Style: ${layout.style}.
    Final crop: ${layout.width}x${layout.height}. Keep the main subject in the center with generous margins.
    Do not include text, logos, watermarks, or UI.
    Visual description:
    ${description.take(5000)}
""".trimIndent()

internal fun interface ImageApi {
    suspend fun generate(configuration: LlmModelConfiguration, key: String, request: ChannelImageRequest): ByteArray?
}

internal fun interface SaveGeneratedImage {
    suspend fun save(bytes: ByteArray, request: ChannelImageRequest): String?
}

internal fun interface RenderImageTemplate {
    suspend fun render(request: ChannelImageRequest): String?
}

/** Sinh ảnh theo mô tả của từng kênh và dùng template offline khi API không trả về ảnh dùng được. */
internal class DraftImages(
    private val configurations: LlmConfigurationSource,
    private val keys: ApiKeySource,
    private val api: ImageApi,
    private val generated: SaveGeneratedImage,
    private val template: RenderImageTemplate,
    private val timeoutMillis: Long = 90_000,
) : ChannelImageGenerator {
    override suspend fun generate(request: ChannelImageRequest): GeneratedImage? =
        create(request)?.let { GeneratedImage(it) }

    suspend fun create(request: ChannelImageRequest): String? {
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
