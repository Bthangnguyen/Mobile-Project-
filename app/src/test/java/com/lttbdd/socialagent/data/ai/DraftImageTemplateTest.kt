package com.lttbdd.socialagent.data.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DraftImageTemplateTest {
    @Test
    fun longVietnameseTitleRendersInsideTheSafeAreaForBothChannels() {
        for (channel in ChannelType.entries) {
            val layout = ChannelImage.forChannel(channel)
            val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
            try {
                val title = "Tiếng Việt có dấu và tiêu đề dài ".repeat(60)
                val draft = Draft("draft", "topic", channel, "Một đoạn nội dung có dấu để xem trước.")
                drawChannelTemplate(Canvas(bitmap), DraftImageRequest(draft, title, layout))
                assertEquals(layout.width, bitmap.width)
                assertEquals(layout.height, bitmap.height)

                val inset = if (channel == ChannelType.FACEBOOK) 72 else 104
                val top = if (channel == ChannelType.FACEBOOK) 100 else 258
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                var whiteTextPixels = 0
                for ((index, color) in pixels.withIndex()) {
                    if (color != Color.WHITE) continue
                    val x = index % bitmap.width
                    val y = index / bitmap.width
                    assertTrue(x in inset until bitmap.width - inset)
                    assertTrue(y in top until bitmap.height - inset)
                    whiteTextPixels++
                }
                assertTrue(whiteTextPixels > 100)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
