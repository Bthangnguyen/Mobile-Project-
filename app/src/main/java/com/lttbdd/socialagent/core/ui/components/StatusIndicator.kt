package com.lttbdd.socialagent.core.ui.components

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.textview.MaterialTextView
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.core.model.PostStatus

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

    fun setStatus(status: PostStatus) {
        when (status) {
            PostStatus.DRAFT -> setStatus(R.string.status_draft, R.color.brand_disabled)
            PostStatus.QUEUED -> setStatus(R.string.status_queued, R.color.brand_warning)
            PostStatus.PUBLISHING -> setStatus(R.string.status_publishing, R.color.brand_info)
            PostStatus.PUBLISHED -> setStatus(R.string.status_published, R.color.brand_success)
            PostStatus.FAILED -> setStatus(R.string.status_failed, R.color.brand_error)
            PostStatus.HANDED_OFF -> setStatus(R.string.status_handed_off, R.color.brand_secondary)
        }
    }

    fun setStatus(status: ConnectionStatus) {
        when (status) {
            ConnectionStatus.CONNECTED -> setStatus(R.string.status_connected, R.color.brand_success)
            ConnectionStatus.DISCONNECTED ->
                setStatus(R.string.status_disconnected, R.color.brand_disabled)
            ConnectionStatus.TOKEN_EXPIRED -> setStatus(R.string.status_expired, R.color.brand_warning)
        }
    }

    fun setConnecting() {
        setStatus(R.string.status_connecting, R.color.brand_info)
    }

    fun setPendingApproval() {
        setStatus(R.string.status_pending_approval, R.color.brand_warning)
    }

    private fun setStatus(@StringRes labelRes: Int, @ColorRes colorRes: Int) {
        setText(labelRes)
        setTextColor(ContextCompat.getColor(context, colorRes))
        contentDescription = text
    }
}
