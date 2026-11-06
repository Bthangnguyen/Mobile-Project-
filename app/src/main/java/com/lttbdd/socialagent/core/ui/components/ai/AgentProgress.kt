package com.lttbdd.socialagent.core.ui.components.ai

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.textview.MaterialTextView
import com.lttbdd.socialagent.R

enum class AgentStep(@param:StringRes val titleRes: Int) {
    CREATING_BRIEF(R.string.agent_step_creating_brief),
    GENERATING_OUTLINE(R.string.agent_step_generating_outline),
    WRITING_DRAFT(R.string.agent_step_writing_draft),
    REVIEWING_CONTENT(R.string.agent_step_reviewing_content),
    GENERATING_IMAGE(R.string.agent_step_generating_image),
    COMPLETING_RESULT(R.string.agent_step_completing_result),
}

enum class AgentStepStatus(
    @param:StringRes val labelRes: Int,
    @param:ColorRes val colorRes: Int,
) {
    WAITING(R.string.agent_progress_waiting, R.color.brand_disabled),
    IN_PROGRESS(R.string.agent_progress_in_progress, R.color.brand_info),
    COMPLETED(R.string.agent_progress_completed, R.color.brand_success),
    FAILED(R.string.agent_progress_failed, R.color.brand_error),
}

class AgentProgress @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val statusViews = mutableMapOf<AgentStep, MaterialTextView>()

    init {
        orientation = VERTICAL
        AgentStep.entries.forEach { step ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val title = MaterialTextView(context).apply {
                setText(step.titleRes)
                setTextAppearance(R.style.TextAppearance_SocialAgent_Body)
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val status = MaterialTextView(context).apply {
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.component_spacing_small),
                    resources.getDimensionPixelSize(R.dimen.component_spacing_xsmall),
                    resources.getDimensionPixelSize(R.dimen.component_spacing_small),
                    resources.getDimensionPixelSize(R.dimen.component_spacing_xsmall),
                )
                setStatus(AgentStepStatus.WAITING)
            }
            row.addView(title)
            row.addView(status)
            addView(row)
            statusViews[step] = status
        }
    }

    fun setStepStatus(step: AgentStep, status: AgentStepStatus) {
        statusViews.getValue(step).setStatus(status)
    }

    fun setStatuses(statuses: Map<AgentStep, AgentStepStatus>) {
        AgentStep.entries.forEach { step ->
            setStepStatus(step, statuses[step] ?: AgentStepStatus.WAITING)
        }
    }

    private fun MaterialTextView.setStatus(status: AgentStepStatus) {
        setText(status.labelRes)
        setTextColor(ContextCompat.getColor(context, status.colorRes))
        contentDescription = text
    }
}
