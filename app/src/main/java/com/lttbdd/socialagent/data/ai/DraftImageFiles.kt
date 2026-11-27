package com.lttbdd.socialagent.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Giải mã, cắt ảnh theo kích thước kênh và lưu ảnh JPEG riêng của ứng dụng. */
internal class DraftImageFiles(private val context: Context) : SaveGeneratedImage, RenderImageTemplate {
    override suspend fun save(bytes: ByteArray, request: ChannelImageRequest): String? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
            bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp")
        ) return@withContext null
        val options = BitmapFactory.Options().apply {
            inSampleSize = if (maxOf(bounds.outWidth, bounds.outHeight) > 2400) 2 else 1
        }
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withContext null
        try {
            saveBitmap(request.layout) { canvas ->
                val scale = maxOf(canvas.width.toFloat() / source.width, canvas.height.toFloat() / source.height)
                val width = source.width * scale
                val height = source.height * scale
                val left = (canvas.width - width) / 2
                val top = (canvas.height - height) / 2
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(source, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        } finally {
            source.recycle()
        }
    }

    override suspend fun render(request: ChannelImageRequest): String = withContext(Dispatchers.IO) {
        saveBitmap(request.layout) { canvas -> drawChannelTemplate(canvas, request) }
    }

    /** Xóa ảnh nội bộ không còn được bản nháp sử dụng. */
    suspend fun discard(value: String?) = withContext(Dispatchers.IO) {
        val uri = value?.let(Uri::parse) ?: return@withContext
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.draft-images") return@withContext
        try {
            val deletedCount = context.contentResolver.delete(uri, null, null)
            if (deletedCount <= 0) {
                Log.w(LOG_TAG, "Draft image deletion returned $deletedCount.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            Log.w(LOG_TAG, "Invalid draft image URI for deletion.", error)
        } catch (error: SecurityException) {
            Log.w(LOG_TAG, "Draft image deletion was denied.", error)
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Draft image deletion failed.", error)
        }
    }

    private fun saveBitmap(layout: ChannelImage, draw: (Canvas) -> Unit): String {
        val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap))
            val directory = File(context.filesDir, "draft-images")
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Không tạo được thư mục ảnh bản nháp")
            }
            val file = File.createTempFile("draft-", ".jpg", directory)
            var saved = false
            try {
                val compressed = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                if (!compressed) throw IOException("Không lưu được ảnh bản nháp")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.draft-images", file)
                saved = true
                return uri.toString()
            } finally {
                if (!saved) file.delete()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val LOG_TAG = "DraftImageFiles"
    }
}

internal fun drawChannelTemplate(canvas: Canvas, request: ChannelImageRequest) {
    val wide = request.layout == ChannelImage.FACEBOOK
    val background = if (wide) 0xff0d3c3b.toInt() else 0xff10203e.toInt()
    val accent = if (wide) 0xff66e0bd.toInt() else 0xff92b9ff.toInt()
    val tint = if (wide) 0xff1b6b61.toInt() else 0xff284979.toInt()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), background, tint, Shader.TileMode.CLAMP)
    canvas.drawPaint(paint)
    paint.shader = null
    paint.color = accent
    val inset = if (wide) 72f else 104f
    val top = if (wide) 100f else 258f
    if (wide) canvas.drawCircle(canvas.width - 60f, 25f, 124f, paint)
    else canvas.drawRoundRect(inset, 106f, canvas.width - inset, 118f, 6f, 6f, paint)

    val width = (canvas.width - inset * 2).toInt()
    val title = request.title.ifBlank { request.postText.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
    val heading = templateText(title, width, if (wide) 64f else 92f, 3, true)
    val excerpt = request.postText.removePrefix(title).trim()
    val summary = templateText(excerpt, width, if (wide) 34f else 46f, 3, false)

    canvas.save()
    canvas.clipRect(inset, top, canvas.width - inset, canvas.height - inset)
    canvas.translate(inset, top)
    heading.draw(canvas)
    canvas.translate(0f, heading.height + 28f)
    summary.draw(canvas)
    canvas.restore()
}

private fun templateText(text: String, width: Int, size: Float, maxLines: Int, bold: Boolean): StaticLayout {
    val limited = text.take(2048)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = size
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    return StaticLayout.Builder.obtain(limited, 0, limited.length, paint, width)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()
}
