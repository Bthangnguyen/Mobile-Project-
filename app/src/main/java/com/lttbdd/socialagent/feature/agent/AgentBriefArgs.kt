package com.lttbdd.socialagent.feature.agent

import android.os.Bundle
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ContentLanguage
import com.lttbdd.socialagent.core.model.TopicBrief

/**
 * Đóng gói và giải nén [TopicBrief] qua [Bundle] cho Navigation Component mà không cần thay đổi
 * hợp đồng `core/model/Agent.kt`.
 */
object AgentBriefArgs {
    private const val KEY_ID = "brief_id"
    private const val KEY_TOPIC = "brief_topic"
    private const val KEY_GOAL = "brief_goal"
    private const val KEY_AUDIENCE = "brief_audience"
    private const val KEY_TONE = "brief_tone"
    private const val KEY_LANGUAGE = "brief_language"
    private const val KEY_CHANNELS = "brief_channels"

    fun toBundle(brief: TopicBrief): Bundle = Bundle().apply {
        putString(KEY_ID, brief.id)
        putString(KEY_TOPIC, brief.topic)
        putString(KEY_GOAL, brief.goal)
        putString(KEY_AUDIENCE, brief.audience)
        putString(KEY_TONE, brief.tone)
        putString(KEY_LANGUAGE, brief.language.name)
        putStringArrayList(KEY_CHANNELS, ArrayList(brief.channels.map { it.name }))
    }

    fun fromBundle(bundle: Bundle?): TopicBrief? {
        if (bundle == null) return null
        val id = bundle.getString(KEY_ID)?.takeIf { it.isNotBlank() } ?: return null
        val topic = bundle.getString(KEY_TOPIC)?.takeIf { it.isNotBlank() } ?: return null
        val goal = bundle.getString(KEY_GOAL).orEmpty()
        val audience = bundle.getString(KEY_AUDIENCE).orEmpty()
        val tone = bundle.getString(KEY_TONE).orEmpty()
        val language = bundle.getString(KEY_LANGUAGE)
            ?.let { name -> ContentLanguage.entries.firstOrNull { it.name == name } }
            ?: ContentLanguage.VI
        val channels = bundle.getStringArrayList(KEY_CHANNELS)
            ?.mapNotNull { name -> ChannelType.entries.firstOrNull { it.name == name } }
            ?.toSet()
            .orEmpty()
        if (channels.isEmpty()) return null

        return TopicBrief(
            id = id,
            topic = topic,
            goal = goal,
            audience = audience,
            tone = tone,
            language = language,
            channels = channels,
        )
    }
}
