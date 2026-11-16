package com.lttbdd.socialagent.feature.compose

import androidx.lifecycle.ViewModel
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ContentLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * ViewModel quản lý biểu mẫu nhập topic, validation và đóng gói [TopicBrief].
 */
@HiltViewModel
class ComposeViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    fun onTopicChanged(topic: String) {
        _uiState.update { current ->
            val updated = current.formState.copy(
                topic = topic,
                topicError = if (current.formState.topicError != null) TopicFormValidator.validateTopic(topic) else null,
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onGoalChanged(goal: String) {
        _uiState.update { current ->
            val updated = current.formState.copy(
                goal = goal,
                goalError = if (current.formState.goalError != null) TopicFormValidator.validateGoal(goal) else null,
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onAudienceChanged(audience: String) {
        _uiState.update { current ->
            val updated = current.formState.copy(
                audience = audience,
                audienceError = if (current.formState.audienceError != null) TopicFormValidator.validateAudience(audience) else null,
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onToneChanged(tone: String) {
        _uiState.update { current ->
            val updated = current.formState.copy(
                tone = tone,
                toneError = if (current.formState.toneError != null) TopicFormValidator.validateTone(tone) else null,
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onLanguageChanged(language: ContentLanguage) {
        _uiState.update { current ->
            val updated = current.formState.copy(language = language)
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onChannelToggled(channel: ChannelType, isSelected: Boolean) {
        _uiState.update { current ->
            val newChannels = if (isSelected) {
                current.formState.selectedChannels + channel
            } else {
                current.formState.selectedChannels - channel
            }
            val updated = current.formState.copy(
                selectedChannels = newChannels,
                channelsError = if (current.formState.channelsError != null) TopicFormValidator.validateChannels(newChannels) else null,
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    /**
     * Xác thực toàn bộ biểu mẫu. Nếu có trường lỗi, gắn thông báo lỗi tương ứng.
     * Trả về true nếu toàn bộ dữ liệu hợp lệ.
     */
    fun validate(): Boolean {
        var isValid = true
        _uiState.update { current ->
            val state = current.formState
            val topicError = TopicFormValidator.validateTopic(state.topic)
            val goalError = TopicFormValidator.validateGoal(state.goal)
            val audienceError = TopicFormValidator.validateAudience(state.audience)
            val toneError = TopicFormValidator.validateTone(state.tone)
            val channelsError = TopicFormValidator.validateChannels(state.selectedChannels)

            isValid = topicError == null &&
                goalError == null &&
                audienceError == null &&
                toneError == null &&
                channelsError == null

            val validatedForm = state.copy(
                topicError = topicError,
                goalError = goalError,
                audienceError = audienceError,
                toneError = toneError,
                channelsError = channelsError,
                isFormValid = isValid,
            )
            current.copy(formState = validatedForm)
        }
        return isValid
    }

    /**
     * Bấm nút Tiếp tục: kiểm tra hợp lệ, đóng gói TopicBrief và cập nhật state.
     */
    fun onSubmit() {
        if (!validate()) return

        val formState = _uiState.value.formState
        val brief = formState.toTopicBrief()
        _uiState.update { current ->
            current.copy(
                submittedBrief = brief,
                successMessage = R.string.compose_submit_success,
            )
        }
    }

    fun onSuccessMessageDismissed() {
        _uiState.update { it.copy(successMessage = null) }
    }

    private fun updateValidity(state: TopicFormState): TopicFormState {
        val valid = TopicFormValidator.isFormValid(
            topic = state.topic,
            goal = state.goal,
            audience = state.audience,
            tone = state.tone,
            channels = state.selectedChannels,
        )
        return state.copy(isFormValid = valid)
    }
}
