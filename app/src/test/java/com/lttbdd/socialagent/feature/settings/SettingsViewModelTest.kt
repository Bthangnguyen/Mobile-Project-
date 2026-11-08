package com.lttbdd.socialagent.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsViewModelTest {

    private class FakeLanguageManager(var language: AppLanguage = AppLanguage.SYSTEM) : LanguageManager {
        val applied = mutableListOf<AppLanguage>()

        override fun current() = language

        override fun apply(language: AppLanguage) {
            applied += language
            this.language = language
        }
    }

    @Test
    fun `initial state shows the language currently applied`() {
        val viewModel = SettingsViewModel(FakeLanguageManager(AppLanguage.VIETNAMESE))

        assertEquals(AppLanguage.VIETNAMESE, viewModel.uiState.value.language)
        assertEquals(AppLanguage.entries, viewModel.uiState.value.languageOptions)
    }

    @Test
    fun `selecting a new language applies it and updates state`() {
        val manager = FakeLanguageManager(AppLanguage.SYSTEM)
        val viewModel = SettingsViewModel(manager)

        viewModel.onLanguageSelected(AppLanguage.ENGLISH)

        assertEquals(listOf(AppLanguage.ENGLISH), manager.applied)
        assertEquals(AppLanguage.ENGLISH, viewModel.uiState.value.language)
    }

    @Test
    fun `selecting the current language does nothing`() {
        val manager = FakeLanguageManager(AppLanguage.VIETNAMESE)
        val viewModel = SettingsViewModel(manager)

        viewModel.onLanguageSelected(AppLanguage.VIETNAMESE)

        assertTrue(manager.applied.isEmpty())
    }
}
