package com.lttbdd.socialagent.feature.compose

import androidx.annotation.StringRes
import com.lttbdd.socialagent.R

/**
 * Tùy chọn giọng văn (Tone of Voice) cho bài viết ở màn F3.
 */
data class ToneOption(
    val key: String,
    @param:StringRes val labelRes: Int,
) {
    companion object {
        val ALL = listOf(
            ToneOption("professional", R.string.tone_professional),
            ToneOption("friendly", R.string.tone_friendly),
            ToneOption("casual", R.string.tone_casual),
            ToneOption("inspirational", R.string.tone_inspirational),
            ToneOption("persuasive", R.string.tone_persuasive),
        )
    }
}
