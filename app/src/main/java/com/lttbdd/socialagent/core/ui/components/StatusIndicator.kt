package com.lttbdd.socialagent.core.ui.components

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.textview.MaterialTextView
import com.lttbdd.socialagent.R

enum class StatusKind(
    @param:StringRes val labelRes: Int,
    @param:ColorRes val colorRes: Int,
) {
    CONNECTED(R.string.status_connected, R.color.brand_success),
    DISCONNECTED(R.string.status_disconnected, R.color.brand_disabled),
    CONNECTING(R.string.status_connecting, R.color.brand_info),
    EXPIRED(R.string.status_expired, R.color.brand_warning),
    DRAFT(R.string.status_draft, R.color.brand_disabled),
    PENDING_APPROVAL(R.string.status_pending_approval, R.color.brand_warning),
    PUBLISHING(R.string.status_publishing, R.color.brand_info),
    PUBLISHED(R.string.status_published, R.color.brand_success),
    FAILED(R.string.status_failed, R.color.brand_error),
    HANDED_OFF(R.string.status_handed_off, R.color.brand_secondary),
}

class StatusIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialTextView(context, attrs) {

    init {
        setPadding(
            resources.getDimensionPixelSize(R.dimen.component_spacing_small),
            resources.getDimensionPixelSize(R.dimen.component_spacing_xsmall),
            resources.getDimensionPixelSize(R.dimen.component_spacing_small),
            resources.getDimensionPixelSize(R.dimen.component_spacing_xsmall),
        )
        setBackgroundResource(R.drawable.bg_status_indicator)
    }

    fun setStatus(status: StatusKind) {
        setText(status.labelRes)
        setTextColor(ContextCompat.getColor(context, status.colorRes))
        contentDescription = text
    }
}
