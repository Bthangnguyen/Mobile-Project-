package com.lttbdd.socialagent.feature.settings

/** Đọc và đổi ngôn ngữ giao diện của app. Tách thành interface để ViewModel test được không cần Android. */
interface LanguageManager {
    fun current(): AppLanguage
    fun apply(language: AppLanguage)
}
