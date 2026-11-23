package com.lttbdd.socialagent.core.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.card.MaterialCardView
import com.lttbdd.socialagent.R

class AppLoading @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : CircularProgressIndicator(context, attrs)

class AppProgressIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearProgressIndicator(context, attrs)

open class AppFeedbackState @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs) {

    private val titleView = MaterialTextView(context).apply {
        setTextAppearance(R.style.TextAppearance_SocialAgent_Title)
    }
    private val messageView = MaterialTextView(context).apply {
        setTextAppearance(R.style.TextAppearance_SocialAgent_Body)
    }
    private val actionButton = MaterialButton(context).apply {
        visibility = View.GONE
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        addView(titleView)
        addView(messageView)
        addView(actionButton)
    }

    init {
        addView(content)
        setContentPadding(
            resources.getDimensionPixelSize(R.dimen.card_padding),
            resources.getDimensionPixelSize(R.dimen.card_padding),
            resources.getDimensionPixelSize(R.dimen.card_padding),
            resources.getDimensionPixelSize(R.dimen.card_padding),
        )
    }

    fun setContent(title: CharSequence, message: CharSequence) {
        titleView.text = title
        messageView.text = message
    }

    fun setAction(label: CharSequence?, onClick: (() -> Unit)?) {
        if (label == null || onClick == null) {
            actionButton.visibility = View.GONE
            actionButton.setOnClickListener(null)
            return
        }
        actionButton.text = label
        actionButton.visibility = View.VISIBLE
        actionButton.setOnClickListener { onClick() }
    }
}

class AppEmptyState @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppFeedbackState(context, attrs)

class AppErrorState @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppFeedbackState(context, attrs)

object AppSnackbar {
    fun show(view: View, @StringRes messageRes: Int, duration: Int = Snackbar.LENGTH_SHORT) {
        Snackbar.make(view, messageRes, duration).show()
    }
}

object AppDialog {
    fun show(
        context: Context,
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
        @StringRes positiveActionRes: Int = R.string.action_done,
        @StringRes negativeActionRes: Int? = null,
        onPositiveAction: (() -> Unit)? = null,
    ) {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(positiveActionRes) { _, _ -> onPositiveAction?.invoke() }
        negativeActionRes?.let { dialog.setNegativeButton(it, null) }
        dialog.show()
    }
}
