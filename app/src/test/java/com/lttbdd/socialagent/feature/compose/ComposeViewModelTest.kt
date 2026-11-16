package com.lttbdd.socialagent.feature.compose

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ContentLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComposeViewModelTest {

    private lateinit var viewModel: ComposeViewModel

    @Before
    fun setUp() {
        viewModel = ComposeViewModel()
    }

    @Test
    fun `initial state has empty fields and invalid form`() {
        val state = viewModel.uiState.value
        assertEquals("", state.formState.topic)
        assertEquals("", state.formState.goal)
        assertEquals("", state.formState.audience)
        assertEquals("", state.formState.tone)
        assertEquals(ContentLanguage.VI, state.formState.language)
        assertTrue(state.formState.selectedChannels.isEmpty())
        assertFalse(state.formState.isFormValid)
        assertNull(state.submittedBrief)
        assertNull(state.successMessage)
    }

    @Test
    fun `changing language updates language in form state`() {
        viewModel.onLanguageChanged(ContentLanguage.EN)
        assertEquals(ContentLanguage.EN, viewModel.uiState.value.formState.language)

        viewModel.onLanguageChanged(ContentLanguage.VI)
        assertEquals(ContentLanguage.VI, viewModel.uiState.value.formState.language)
    }

    @Test
    fun `toggling channels adds and removes channels correctly`() {
        viewModel.onChannelToggled(ChannelType.FACEBOOK, true)
        assertEquals(setOf(ChannelType.FACEBOOK), viewModel.uiState.value.formState.selectedChannels)

        viewModel.onChannelToggled(ChannelType.LINKEDIN, true)
        assertEquals(
            setOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN),
            viewModel.uiState.value.formState.selectedChannels,
        )

        viewModel.onChannelToggled(ChannelType.FACEBOOK, false)
        assertEquals(setOf(ChannelType.LINKEDIN), viewModel.uiState.value.formState.selectedChannels)

        viewModel.onChannelToggled(ChannelType.LINKEDIN, false)
        assertTrue(viewModel.uiState.value.formState.selectedChannels.isEmpty())
    }

    @Test
    fun `form becomes valid when all required fields are filled`() {
        assertFalse(viewModel.uiState.value.formState.isFormValid)

        viewModel.onTopicChanged("Kotlin Coroutines Best Practices")
        assertFalse(viewModel.uiState.value.formState.isFormValid)

        viewModel.onGoalChanged("Share technical knowledge")
        assertFalse(viewModel.uiState.value.formState.isFormValid)

        viewModel.onAudienceChanged("Android Developers")
        assertFalse(viewModel.uiState.value.formState.isFormValid)

        viewModel.onToneChanged("professional")
        assertFalse(viewModel.uiState.value.formState.isFormValid)

        viewModel.onChannelToggled(ChannelType.LINKEDIN, true)
        assertTrue(viewModel.uiState.value.formState.isFormValid)
    }

    @Test
    fun `submit with empty fields triggers validation errors and does not submit brief`() {
        viewModel.onSubmit()

        val form = viewModel.uiState.value.formState
        assertEquals(R.string.compose_topic_error, form.topicError)
        assertEquals(R.string.compose_goal_error, form.goalError)
        assertEquals(R.string.compose_audience_error, form.audienceError)
        assertEquals(R.string.compose_tone_error, form.toneError)
        assertEquals(R.string.compose_channels_error, form.channelsError)
        assertFalse(form.isFormValid)
        assertNull(viewModel.uiState.value.submittedBrief)
    }

    @Test
    fun `submit with valid fields produces TopicBrief with correct data`() {
        viewModel.onTopicChanged("Social Agent Launch")
        viewModel.onGoalChanged("Announce product release")
        viewModel.onAudienceChanged("Tech enthusiasts")
        viewModel.onToneChanged("inspirational")
        viewModel.onLanguageChanged(ContentLanguage.EN)
        viewModel.onChannelToggled(ChannelType.FACEBOOK, true)
        viewModel.onChannelToggled(ChannelType.LINKEDIN, true)

        viewModel.onSubmit()

        val state = viewModel.uiState.value
        val brief = state.submittedBrief
        assertNotNull(brief)
        assertEquals("Social Agent Launch", brief?.topic)
        assertEquals("Announce product release", brief?.goal)
        assertEquals("Tech enthusiasts", brief?.audience)
        assertEquals("inspirational", brief?.tone)
        assertEquals(ContentLanguage.EN, brief?.language)
        assertEquals(setOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN), brief?.channels)
        assertNotNull(brief?.id)
        assertEquals(R.string.compose_submit_success, state.successMessage)
    }

    @Test
    fun `dismissing success message clears successMessage from state`() {
        viewModel.onTopicChanged("Topic")
        viewModel.onGoalChanged("Goal")
        viewModel.onAudienceChanged("Audience")
        viewModel.onToneChanged("friendly")
        viewModel.onChannelToggled(ChannelType.FACEBOOK, true)

        viewModel.onSubmit()
        assertNotNull(viewModel.uiState.value.successMessage)

        viewModel.onSuccessMessageDismissed()
        assertNull(viewModel.uiState.value.successMessage)
    }

    @Test
    fun `typing into an error field clears its error once valid`() {
        viewModel.onSubmit()
        assertEquals(R.string.compose_topic_error, viewModel.uiState.value.formState.topicError)

        viewModel.onTopicChanged("Valid Topic")
        assertNull(viewModel.uiState.value.formState.topicError)
    }
}
