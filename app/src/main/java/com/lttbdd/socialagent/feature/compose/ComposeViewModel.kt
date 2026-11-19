package com.lttbdd.socialagent.feature.compose

import androidx.lifecycle.ViewModel
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ContentLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * ViewModel quản lý biểu mẫu nhập topic, validation và đóng gói [TopicBrief].
 */
@HiltViewModel
class ComposeViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    private val _events = Channel<ComposeEvent>(Channel.BUFFERED)
    val events: Flow<ComposeEvent> = _events.receiveAsFlow()

    fun onTopicChanged(topic: String) {
        _uiState.update { current ->
            if (topic == current.formState.topic) return@update current
            val updated = current.formState.copy(
                topic = topic,
                topicError = TopicFormValidator.validateTopic(topic),
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onTopicFocusLost() {
        _uiState.update { current ->
            val updated = current.formState.copy(
                topicError = TopicFormValidator.validateTopic(current.formState.topic),
            )
            current.copy(formState = updated)
        }
    }

    fun onGoalChanged(goal: String) {
        _uiState.update { current ->
            if (goal == current.formState.goal) return@update current
            val updated = current.formState.copy(
                goal = goal,
                goalError = TopicFormValidator.validateGoal(goal),
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onGoalFocusLost() {
        _uiState.update { current ->
            val updated = current.formState.copy(
                goalError = TopicFormValidator.validateGoal(current.formState.goal),
            )
            current.copy(formState = updated)
        }
    }

    fun onAudienceChanged(audience: String) {
        _uiState.update { current ->
            if (audience == current.formState.audience) return@update current
            val updated = current.formState.copy(
                audience = audience,
                audienceError = TopicFormValidator.validateAudience(audience),
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onAudienceFocusLost() {
        _uiState.update { current ->
            val updated = current.formState.copy(
                audienceError = TopicFormValidator.validateAudience(current.formState.audience),
            )
            current.copy(formState = updated)
        }
    }

    fun onToneChanged(tone: String) {
        _uiState.update { current ->
            if (tone == current.formState.tone) return@update current
            val updated = current.formState.copy(
                tone = tone,
                toneError = TopicFormValidator.validateTone(tone),
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onToneFocusLost() {
        _uiState.update { current ->
            val updated = current.formState.copy(
                toneError = TopicFormValidator.validateTone(current.formState.tone),
            )
            current.copy(formState = updated)
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
            if (newChannels == current.formState.selectedChannels) return@update current
            val updated = current.formState.copy(
                selectedChannels = newChannels,
                channelsError = TopicFormValidator.validateChannels(newChannels),
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
     * Bấm nút Tiếp tục: kiểm tra hợp lệ, đóng gói TopicBrief và phát sự kiện [ComposeEvent.BriefSubmitted].
     */
    fun onSubmit() {
        if (!validate()) return

        val formState = _uiState.value.formState
        val brief = formState.toTopicBrief()
        _uiState.update { current ->
            current.copy(submittedBrief = brief)
        }
        _events.trySend(ComposeEvent.BriefSubmitted(brief, R.string.compose_submit_success))
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
