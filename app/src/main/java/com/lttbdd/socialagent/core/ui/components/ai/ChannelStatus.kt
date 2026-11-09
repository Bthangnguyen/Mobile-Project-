package com.lttbdd.socialagent.core.ui.components.ai

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.core.ui.components.StatusIndicator

class ChannelStatus @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val icon = ImageView(context)
    private val platform = MaterialTextView(context)
    private val account = MaterialTextView(context)
    private val status = StatusIndicator(context)
    private val action = MaterialButton(context)

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val iconSize = resources.getDimensionPixelSize(R.dimen.icon_size_large)
        addView(icon, LayoutParams(iconSize, iconSize))

        val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.component_spacing)
            }
        }
        platform.setTextAppearance(R.style.TextAppearance_SocialAgent_Title)
        account.setTextAppearance(R.style.TextAppearance_SocialAgent_Caption)
        labels.addView(platform)
        labels.addView(account)
        addView(labels)
        addView(status)
        action.visibility = View.GONE
        addView(action)
    }

    fun setChannel(
        channelName: CharSequence,
        accountName: CharSequence,
        connectionState: ConnectionStatus,
        @DrawableRes iconRes: Int? = null,
        actionLabel: CharSequence? = null,
        isConnecting: Boolean = false,
        onAction: (() -> Unit)? = null,
    ) {
        platform.text = channelName
        account.text = accountName
        if (isConnecting) {
            status.setConnecting()
        } else {
            status.setStatus(connectionState)
        }
        contentDescription = context.getString(
            R.string.channel_status_content_description,
            channelName,
            accountName,
            status.text,
        )
        if (iconRes == null) {
            icon.visibility = View.GONE
        } else {
            icon.setImageResource(iconRes)
            icon.visibility = View.VISIBLE
            icon.contentDescription = channelName
        }
        if (actionLabel == null || onAction == null) {
            action.visibility = View.GONE
            action.setOnClickListener(null)
        } else {
            action.text = actionLabel
            action.visibility = View.VISIBLE
            action.setOnClickListener { onAction() }
        }
    }
}
