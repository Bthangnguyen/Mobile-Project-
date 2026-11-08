package com.lttbdd.socialagent.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import javax.inject.Inject

/** Đọc và đổi ngôn ngữ giao diện của app. Tách thành interface để ViewModel test được không cần Android. */
interface LanguageManager {
    fun current(): AppLanguage
    fun apply(language: AppLanguage)
}

/**
 * Dùng per-app language của AppCompat: Android 13+ lưu lựa chọn trong hệ thống, Android 12 trở xuống
 * do `AppLocalesMetadataHolderService` (autoStoreLocales) trong manifest lưu lại. Activity tự tạo lại sau khi đổi.
 */
class AppCompatLanguageManager @Inject constructor() : LanguageManager {

    override fun current(): AppLanguage =
        AppLanguage.fromTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())

    override fun apply(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }
}
