package com.lttbdd.socialagent.feature.apikeys

import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lttbdd.socialagent.MainActivity
import com.lttbdd.socialagent.R
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Kiểm tra điều hướng/Hilt và bảo vệ đầu vào khi xoay màn hình; không lưu hoặc gửi key. */
@RunWith(AndroidJUnit4::class)
class ApiKeysPrivacyTest {
    @Test
    fun passwordIsNotRestoredAfterActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.settingsFragment)).perform(click())
            onView(withId(R.id.manage_api_keys)).perform(click())
            onView(withId(R.id.key_input)).check(matches(isDisplayed()))
            onView(withId(R.id.open_ai_button)).check(matches(withText(R.string.api_keys_openai)))
            onView(withId(R.id.gemini_button)).check(matches(withText(R.string.api_keys_gemini)))
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.key_input)
                assertTrue(input.transformationMethod is PasswordTransformationMethod)
                assertFalse(input.isSaveEnabled)
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                // Chỉ nhập key giả trong RAM; không nhấn kiểm tra/lưu, không gọi API thật.
                input.setText(UUID.randomUUID().toString())
            }
            scenario.recreate()
            onView(withId(R.id.key_input)).check(matches(withText("")))
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }
}
