package com.lttbdd.socialagent.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.data.demo.DemoModeStore
import com.lttbdd.socialagent.data.settings.AppLanguage
import com.lttbdd.socialagent.data.settings.LanguageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val language: AppLanguage,
    /** Chế độ Demo (#73): nội dung mẫu, không cần API key. */
    val demoMode: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val languageManager: LanguageManager,
    private val demoMode: DemoModeStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(language = languageManager.current()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            demoMode.observe().collect { enabled -> _uiState.update { it.copy(demoMode = enabled) } }
        }
    }

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

    /** Bật/tắt chế độ Demo; có tác dụng từ lệnh gọi LLM kế tiếp (#73). */
    fun onDemoModeToggled(enabled: Boolean) {
        if (enabled == _uiState.value.demoMode) return
        _uiState.update { it.copy(demoMode = enabled) }
        viewModelScope.launch { demoMode.setEnabled(enabled) }
    }
}
