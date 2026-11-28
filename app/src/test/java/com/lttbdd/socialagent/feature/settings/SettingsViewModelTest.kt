package com.lttbdd.socialagent.feature.settings

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.data.demo.DemoModeStore
import com.lttbdd.socialagent.data.settings.AppLanguage
import com.lttbdd.socialagent.data.settings.LanguageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private class FakeLanguageManager(var language: AppLanguage = AppLanguage.SYSTEM) : LanguageManager {
        val applied = mutableListOf<AppLanguage>()

        override fun current() = language

        override fun apply(language: AppLanguage) {
            applied += language
            this.language = language
        }
    }

    private class FakeDemoModeStore : DemoModeStore {
        val enabled = MutableStateFlow(false)
        val saved = mutableListOf<Boolean>()
        var failure: Exception? = null

        override fun observe(): Flow<Boolean> = enabled

        override suspend fun setEnabled(enabled: Boolean) {
            failure?.let { throw it }
            saved += enabled
            this.enabled.value = enabled
        }
    }

    private val demoStore = FakeDemoModeStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(manager: LanguageManager) = SettingsViewModel(manager, demoStore)

    @Test
    fun `initial state shows the language currently applied`() {
        val viewModel = viewModel(FakeLanguageManager(AppLanguage.VIETNAMESE))

        assertEquals(AppLanguage.VIETNAMESE, viewModel.uiState.value.language)
    }

    @Test
    fun `refresh picks up a language changed outside the app`() {
        val manager = FakeLanguageManager(AppLanguage.VIETNAMESE)
        val viewModel = viewModel(manager)

        manager.language = AppLanguage.ENGLISH // ví dụ: đổi trong Cài đặt hệ thống trên Android 13+
        viewModel.refresh()

        assertEquals(AppLanguage.ENGLISH, viewModel.uiState.value.language)
        assertTrue(manager.applied.isEmpty())
    }

    @Test
    fun `selecting a new language applies it and updates state`() {
        val manager = FakeLanguageManager(AppLanguage.SYSTEM)
        val viewModel = viewModel(manager)

        viewModel.onLanguageSelected(AppLanguage.ENGLISH)

        assertEquals(listOf(AppLanguage.ENGLISH), manager.applied)
        assertEquals(AppLanguage.ENGLISH, viewModel.uiState.value.language)
    }

    @Test
    fun `selecting the current language does nothing`() {
        val manager = FakeLanguageManager(AppLanguage.VIETNAMESE)
        val viewModel = viewModel(manager)

        viewModel.onLanguageSelected(AppLanguage.VIETNAMESE)

        assertTrue(manager.applied.isEmpty())
    }

    // ---------- #73: chế độ Demo ----------

    @Test
    fun `demo mode starts from the stored value`() {
        demoStore.enabled.value = true

        assertTrue(viewModel(FakeLanguageManager()).uiState.value.demoMode)
    }

    @Test
    fun `toggling demo mode saves it`() {
        val viewModel = viewModel(FakeLanguageManager())
        assertFalse(viewModel.uiState.value.demoMode)

        viewModel.onDemoModeToggled(true)

        assertTrue(viewModel.uiState.value.demoMode)
        assertEquals(listOf(true), demoStore.saved)
    }

    @Test
    fun `toggling to the current value does not save again`() {
        val viewModel = viewModel(FakeLanguageManager())

        viewModel.onDemoModeToggled(false)

        assertTrue(demoStore.saved.isEmpty())
    }

    @Test
    fun `demo switch reverts and reports when saving fails`() = runTest {
        val viewModel = viewModel(FakeLanguageManager())
        demoStore.failure = IOException("disk full")
        val messages = mutableListOf<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }

        viewModel.onDemoModeToggled(true)

        assertFalse("Lưu lỗi thì công tắc trở về giá trị cũ", viewModel.uiState.value.demoMode)
        assertEquals(listOf(R.string.settings_demo_save_failed), messages)
        assertTrue(demoStore.saved.isEmpty())
        job.cancel()
    }
}
