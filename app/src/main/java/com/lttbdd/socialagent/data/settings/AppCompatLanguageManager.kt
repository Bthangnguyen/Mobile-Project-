package com.lttbdd.socialagent.data.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.lttbdd.socialagent.feature.settings.AppLanguage
import com.lttbdd.socialagent.feature.settings.LanguageManager
import javax.inject.Inject

/**
 * Dùng per-app language của AppCompat: Android 13+ lưu lựa chọn trong hệ thống, Android 12 trở xuống
 * do `AppLocalesMetadataHolderService` (autoStoreLocales) trong manifest lưu lại. Activity tự tạo lại sau khi đổi.
 * Lưu ý: trên Android 12 trở xuống ngôn ngữ chỉ áp dụng cho context của Activity; chuỗi dựng từ Application
 * context (thông báo, WorkManager) có thể không theo ngôn ngữ đã chọn.
 */
class AppCompatLanguageManager @Inject constructor() : LanguageManager {

    override fun current(): AppLanguage =
        AppLanguage.fromTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())

    override fun apply(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }
}
