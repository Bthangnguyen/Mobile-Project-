package com.lttbdd.socialagent.feature.publishing

import android.app.Activity
import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Mở bài gốc (#40): Intent đúng loại, và máy không có app mở link thì không crash. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostLinkOpenerTest {

    private val url = "https://www.facebook.com/123_456"

    @Test
    fun `opens the link with a browsable view intent`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        assertTrue(PostLinkOpener.open(activity, url))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun `returns false instead of crashing when no app can open the link`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // Bật kiểm tra như máy thật: không có Activity nào nhận Intent thì ném ActivityNotFoundException.
        shadowOf(activity.application).checkActivities(true)

        assertFalse(PostLinkOpener.open(activity, url))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
