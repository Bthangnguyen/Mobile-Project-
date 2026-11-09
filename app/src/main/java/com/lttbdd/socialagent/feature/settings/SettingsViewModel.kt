package com.lttbdd.socialagent.feature.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SettingsUiState(
    val language: AppLanguage,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val languageManager: LanguageManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(language = languageManager.current()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * Đọc lại ngôn ngữ đang áp dụng. Gọi khi màn hình hiện lại, vì ViewModel sống qua lần tạo lại Activity
     * và trên Android 13+ người dùng có thể đổi ngôn ngữ của app trong Cài đặt hệ thống.
     */
    fun refresh() {
        _uiState.update { it.copy(language = languageManager.current()) }
    }

    fun onLanguageSelected(language: AppLanguage) {
        // Bỏ qua khi chọn lại ngôn ngữ đang dùng, tránh tạo lại Activity không cần thiết.
        if (language == _uiState.value.language) return
        _uiState.update { it.copy(language = language) }
        languageManager.apply(language)
    }
}
