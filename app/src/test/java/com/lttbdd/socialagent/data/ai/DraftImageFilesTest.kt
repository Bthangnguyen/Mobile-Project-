package com.lttbdd.socialagent.data.ai

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.model.ChannelType
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DraftImageFilesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = DraftImageFiles(context)
    private val request = ChannelImageRequest("topic", ChannelType.FACEBOOK, "A blue field", "Nội dung")

    @Test
    fun rejectsUnsupportedMimeAndOversizedImages() = runTest {
        val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        val gifBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(gif, 0, gif.size, gifBounds)
        assertEquals("image/gif", gifBounds.outMimeType)

        assertNull(files.save(gif, request))
        assertNull(files.save(png(4097, 1), request))
    }

    @Test
    fun centerCropsBothChannelSizesAndLogsDiscardFailures() = runTest {
        val source = png(300, 600) { canvas ->
            canvas.drawColor(Color.BLUE)
            val edge = Paint().apply { color = Color.RED }
            canvas.drawRect(0f, 0f, 300f, 100f, edge)
            canvas.drawRect(0f, 500f, 300f, 600f, edge)
        }
        for (channel in ChannelType.entries) {
            val input = request.copy(channel = channel)
            val uri = Uri.parse(checkNotNull(files.save(source, input)))
            val result = checkNotNull(context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            })
            val warningCount = ShadowLog.getLogsForTag("DraftImageFiles").size
            try {
                assertEquals(1200, result.width)
                assertEquals(if (channel == ChannelType.FACEBOOK) 630 else 1200, result.height)
                assertTrue(Color.blue(result.getPixel(600, 10)) > 200)
                assertTrue(Color.blue(result.getPixel(600, result.height - 10)) > 200)
            } finally {
                result.recycle()
                files.discard(uri.toString())
            }
            assertTrue(runCatching { context.contentResolver.openInputStream(uri)?.close() }.isFailure)
            assertEquals(warningCount, ShadowLog.getLogsForTag("DraftImageFiles").size)

            files.discard(uri.toString())
            val warnings = ShadowLog.getLogsForTag("DraftImageFiles")
            assertEquals(warningCount + 1, warnings.size)
            assertEquals(Log.WARN, warnings.last().type)
            assertEquals("Draft image deletion returned 0.", warnings.last().msg)
        }

        checkDiscardWarningsFromFailingProvider()
    }

    private suspend fun checkDiscardWarningsFromFailingProvider() {
        val invalidUri = IllegalArgumentException("Invalid URI")
        val denied = SecurityException("Permission denied")
        val unavailable = IllegalStateException("Provider unavailable")
        val cancelled = CancellationException("Cleanup cancelled")
        val provider = object : FileProvider() {
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
                when (uri.lastPathSegment) {
                    "invalid" -> throw invalidUri
                    "denied" -> throw denied
                    "failed" -> throw unavailable
                    "cancelled" -> throw cancelled
                    else -> -1
                }
        }
        val providerPackage = "${context.packageName}.cleanup_test"
        val authority = "$providerPackage.draft-images"
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val storage = DraftImageFiles(object : ContextWrapper(context) {
            override fun getPackageName(): String = providerPackage
        })
        val imageUri = "content://$authority/draft_images"
        val warningCount = ShadowLog.getLogsForTag("DraftImageFiles").size

        storage.discard("$imageUri/unavailable")
        storage.discard("$imageUri/invalid")
        storage.discard("$imageUri/denied")
        storage.discard("$imageUri/failed")
        val cancellation = runCatching { storage.discard("$imageUri/cancelled") }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)

        val warnings = ShadowLog.getLogsForTag("DraftImageFiles").drop(warningCount)
        assertEquals(listOf(Log.WARN, Log.WARN, Log.WARN, Log.WARN), warnings.map { it.type })
        assertEquals("Draft image deletion returned -1.", warnings[0].msg)
        assertSame(invalidUri, warnings[1].throwable)
        assertSame(denied, warnings[2].throwable)
        assertSame(unavailable, warnings[3].throwable)
    }

    private fun png(width: Int, height: Int, draw: (Canvas) -> Unit = {}): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            draw(Canvas(bitmap))
            ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
