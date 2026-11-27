package com.lttbdd.socialagent.feature.agentlog

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.ai.AgentStepLogEntry
import com.lttbdd.socialagent.data.ai.AgentStepStatus
import com.lttbdd.socialagent.databinding.ItemAgentLogBinding
import com.lttbdd.socialagent.databinding.ItemAgentLogStepBinding
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter hiển thị danh sách lượt chạy agent và chi tiết từng bước (prompt, kết quả, token, chi phí).
 */
class AgentLogAdapter(
    private val onToggleExpand: (String) -> Unit,
) : ListAdapter<AgentLogItemUiState, AgentLogAdapter.RunViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RunViewHolder {
        val binding = ItemAgentLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return RunViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RunViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RunViewHolder(
        private val binding: ItemAgentLogBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AgentLogItemUiState) {
            val context = binding.root.context
            val run = item.run
            val numberFormat = NumberFormat.getIntegerInstance(Locale.getDefault())

            binding.tvTopic.text = run.topic.ifBlank { context.getString(R.string.agent_log_untitled_topic) }
            binding.tvChannel.setText(
                when (run.channel) {
                    ChannelType.FACEBOOK -> R.string.compose_channel_facebook
                    ChannelType.LINKEDIN -> R.string.compose_channel_linkedin
                },
            )
            binding.tvStatus.setText(
                if (run.hasFailure) R.string.agent_log_status_failed else R.string.agent_log_status_success,
            )
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (run.hasFailure) R.color.brand_error else R.color.brand_success,
                ),
            )

            val formattedTime = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
            ).format(Date(run.startedAtMillis))
            val tokensText = context.getString(
                R.string.agent_log_tokens_summary_format,
                numberFormat.format(run.totalTokens),
                numberFormat.format(run.totalInputTokens),
                numberFormat.format(run.totalOutputTokens),
            )
            val costText = formatRunCost(context, run.knownCostUsd, run.hasUnknownCost, run.totalTokens)
            binding.tvRunMeta.text = context.getString(
                R.string.agent_log_run_meta_format,
                formattedTime,
                tokensText,
                costText,
            )

            binding.btnToggleDetails.setText(
                if (item.isExpanded) R.string.agent_log_hide_steps else R.string.agent_log_show_steps,
            )
            binding.btnToggleDetails.setOnClickListener { onToggleExpand(run.id) }

            binding.stepsContainer.isVisible = item.isExpanded
            if (item.isExpanded) {
                renderSteps(context, run.steps, numberFormat)
            } else {
                binding.stepsContainer.removeAllViews()
            }
        }

        private fun renderSteps(
            context: Context,
            steps: List<AgentStepLogEntry>,
            numberFormat: NumberFormat,
        ) {
            binding.stepsContainer.removeAllViews()
            val inflater = LayoutInflater.from(context)
            for (entry in steps) {
                val stepBinding = ItemAgentLogStepBinding.inflate(inflater, binding.stepsContainer, true)
                stepBinding.tvStepName.setText(stepTitleRes(entry.step))
                stepBinding.tvStepStatus.setText(statusRes(entry.status))
                stepBinding.tvStepStatus.setTextColor(
                    ContextCompat.getColor(
                        context,
                        when (entry.status) {
                            AgentStepStatus.SUCCEEDED -> R.color.brand_success
                            AgentStepStatus.FAILED -> R.color.brand_error
                            AgentStepStatus.RUNNING,
                            AgentStepStatus.CANCELLED,
                            -> R.color.brand_warning
                        },
                    ),
                )

                val costText = formatStepCost(context, entry.costUsd, entry.totalTokens)
                stepBinding.tvStepMetrics.text = context.getString(
                    R.string.agent_log_step_metrics_format,
                    numberFormat.format(entry.inputTokens),
                    numberFormat.format(entry.outputTokens),
                    costText,
                )

                stepBinding.tvStepPrompt.text = entry.prompt.ifBlank {
                    context.getString(R.string.agent_log_empty_field)
                }
                stepBinding.tvStepOutput.text = entry.output.ifBlank {
                    context.getString(R.string.agent_log_empty_field)
                }
                if (!entry.errorCode.isNullOrBlank()) {
                    stepBinding.tvStepError.text = context.getString(
                        R.string.agent_log_error_code_format,
                        entry.errorCode,
                    )
                    stepBinding.tvStepError.isVisible = true
                } else {
                    stepBinding.tvStepError.isVisible = false
                }
            }
        }
    }

    private fun formatRunCost(
        context: Context,
        knownCostUsd: Double,
        hasUnknownCost: Boolean,
        totalTokens: Int,
    ): String = when {
        hasUnknownCost && knownCostUsd == 0.0 -> context.getString(R.string.agent_log_cost_unknown)
        hasUnknownCost -> context.getString(
            R.string.agent_log_cost_partial_format,
            formatUsd(knownCostUsd),
        )
        totalTokens == 0 && knownCostUsd == 0.0 -> formatUsd(0.0)
        else -> formatUsd(knownCostUsd)
    }

    private fun formatStepCost(context: Context, costUsd: Double?, totalTokens: Int): String = when {
        costUsd != null -> formatUsd(costUsd)
        totalTokens > 0 -> context.getString(R.string.agent_log_cost_unknown)
        else -> formatUsd(0.0)
    }

    private fun formatUsd(amount: Double): String = String.format(Locale.US, "$%.4f", amount)

    private fun stepTitleRes(step: AgentStep): Int = when (step) {
        AgentStep.OUTLINE -> R.string.agent_log_step_outline
        AgentStep.DRAFT -> R.string.agent_log_step_draft
        AgentStep.SELF_REVIEW -> R.string.agent_log_step_self_review
        AgentStep.IMAGE_PROMPT -> R.string.agent_log_step_image_prompt
        AgentStep.IMAGE -> R.string.agent_log_step_image
        AgentStep.PACKAGE -> R.string.agent_log_step_package
    }

    private fun statusRes(status: AgentStepStatus): Int = when (status) {
        AgentStepStatus.RUNNING -> R.string.agent_log_step_status_running
        AgentStepStatus.SUCCEEDED -> R.string.agent_log_step_status_succeeded
        AgentStepStatus.FAILED -> R.string.agent_log_step_status_failed
        AgentStepStatus.CANCELLED -> R.string.agent_log_step_status_cancelled
    }

    private object DiffCallback : DiffUtil.ItemCallback<AgentLogItemUiState>() {
        override fun areItemsTheSame(
            oldItem: AgentLogItemUiState,
            newItem: AgentLogItemUiState,
        ): Boolean = oldItem.run.id == newItem.run.id

        override fun areContentsTheSame(
            oldItem: AgentLogItemUiState,
            newItem: AgentLogItemUiState,
        ): Boolean = oldItem == newItem
    }
}
