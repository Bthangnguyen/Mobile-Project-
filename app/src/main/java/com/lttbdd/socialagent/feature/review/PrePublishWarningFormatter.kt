package com.lttbdd.socialagent.feature.review

import android.content.Context
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.data.review.PrePublishWarning
import com.lttbdd.socialagent.data.review.PrePublishWarningType

/**
 * Định dạng danh sách [PrePublishWarning] thành chuỗi đa ngôn ngữ hiển thị trên UI (F4, Issue #65).
 */
internal object PrePublishWarningFormatter {

    fun formatBulletList(context: Context, warnings: List<PrePublishWarning>): String =
        warnings.joinToString("\n") { warning ->
            val detail = when (warning.type) {
                PrePublishWarningType.UNSOURCED_STATISTIC -> context.getString(
                    R.string.warning_unsourced_statistic_item,
                    warning.matchedSnippet,
                )
                PrePublishWarningType.PERSONAL_INFO -> context.getString(
                    R.string.warning_personal_info_item,
                    warning.matchedSnippet,
                )
                PrePublishWarningType.SENSITIVE_LANGUAGE -> context.getString(
                    R.string.warning_sensitive_language_item,
                    warning.matchedSnippet,
                )
            }
            context.getString(R.string.warning_bullet_item, detail)
        }
}
