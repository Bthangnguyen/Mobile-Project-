package com.lttbdd.socialagent.feature.agent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.ui.components.ai.AgentStepStatus
import com.lttbdd.socialagent.databinding.ItemAgentChannelProgressBinding

/**
 * Adapter hiển thị card tiến độ từng bước và nội dung stream trực tiếp cho mỗi kênh (F2, Issue #29).
 */
class AgentChannelProgressAdapter(
    private val onReviewDraftClicked: (String) -> Unit,
) : ListAdapter<ChannelProgressUiState, AgentChannelProgressAdapter.ChannelViewHolder>(DiffCallback) {

    private var briefStatus: AgentStepStatus = AgentStepStatus.WAITING

    fun submitProgress(briefStatus: AgentStepStatus, items: List<ChannelProgressUiState>) {
        val briefChanged = this.briefStatus != briefStatus
        this.briefStatus = briefStatus
        submitList(items) {
            if (briefChanged && items.isNotEmpty()) {
                notifyItemRangeChanged(0, items.size)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemAgentChannelProgressBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ChannelViewHolder(binding, onReviewDraftClicked)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position), briefStatus)
    }

    class ChannelViewHolder(
        private val binding: ItemAgentChannelProgressBinding,
        private val onReviewDraftClicked: (String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChannelProgressUiState, briefStatus: AgentStepStatus) {
            val context = binding.root.context

            binding.tvChannelName.setText(channelNameRes(item.channel))

            val (statusLabelRes, statusColorRes) = channelStatusDisplay(item.status)
            binding.tvChannelStatus.setText(statusLabelRes)
            binding.tvChannelStatus.setTextColor(ContextCompat.getColor(context, statusColorRes))

            binding.viewAgentProgress.setBriefStatus(briefStatus)
            binding.viewAgentProgress.setStatuses(item.stepStatuses)

            // Khung nội dung stream trực tiếp
            binding.tvStreamLabel.setText(streamLabelRes(item))
            if (item.streamedContent.isNotEmpty()) {
                binding.tvStreamContent.text = item.streamedContent
                binding.tvStreamContent.visibility = View.VISIBLE
            } else {
                binding.tvStreamContent.text = ""
                binding.tvStreamContent.visibility = View.GONE
            }

            // Khung mô tả ảnh riêng (không ghi đè bài viết)
            if (item.imageDescriptionPreview.isNotBlank()) {
                binding.layoutImagePromptPreview.visibility = View.VISIBLE
                binding.tvImagePromptContent.text = item.imageDescriptionPreview
            } else {
                binding.layoutImagePromptPreview.visibility = View.GONE
            }

            // Thông báo lỗi kênh hoặc lỗi lưu Room
            val errorText = when {
                item.status == ChannelRunStatus.FAILED -> {
                    val reasonText = context.getString(AgentErrorMapper.toMessageRes(item.failureReasonCode))
                    val failedStep = item.failedStep
                    if (failedStep != null) {
                        context.getString(
                            R.string.agent_error_with_step,
                            context.getString(stepTitleRes(failedStep)),
                            reasonText,
                        )
                    } else {
                        reasonText
                    }
                }

                item.draftSaveError -> context.getString(R.string.agent_error_save_draft_failed)
                else -> null
            }

            if (errorText != null) {
                binding.tvChannelError.text = errorText
                binding.tvChannelError.visibility = View.VISIBLE
            } else {
                binding.tvChannelError.visibility = View.GONE
            }

            // Nút mở màn hình xem trước & duyệt bản nháp
            val savedDraftId = item.savedDraftId
            if (item.status == ChannelRunStatus.DONE && savedDraftId != null) {
                binding.btnReviewDraft.visibility = View.VISIBLE
                binding.btnReviewDraft.setOnClickListener { onReviewDraftClicked(savedDraftId) }
            } else {
                binding.btnReviewDraft.visibility = View.GONE
                binding.btnReviewDraft.setOnClickListener(null)
            }
        }

        @StringRes
        private fun channelNameRes(channel: ChannelType): Int = when (channel) {
            ChannelType.FACEBOOK -> R.string.channel_type_facebook
            ChannelType.LINKEDIN -> R.string.channel_type_linkedin
        }

        private fun channelStatusDisplay(status: ChannelRunStatus): Pair<Int, Int> = when (status) {
            ChannelRunStatus.WAITING -> R.string.agent_progress_waiting to R.color.brand_disabled
            ChannelRunStatus.RUNNING -> R.string.agent_progress_in_progress to R.color.brand_info
            ChannelRunStatus.DONE -> R.string.agent_progress_completed to R.color.brand_success
            ChannelRunStatus.FAILED -> R.string.agent_progress_failed to R.color.brand_error
        }

        @StringRes
        private fun streamLabelRes(item: ChannelProgressUiState): Int = when {
            item.status == ChannelRunStatus.DONE -> R.string.agent_stream_final_content
            item.streamingStep == AgentStep.OUTLINE -> R.string.agent_stream_step_outline
            item.streamingStep == AgentStep.DRAFT -> R.string.agent_stream_step_draft
            item.streamingStep == AgentStep.SELF_REVIEW -> R.string.agent_stream_step_self_review
            item.status == ChannelRunStatus.FAILED -> R.string.agent_stream_stopped_on_error
            else -> R.string.agent_stream_waiting_output
        }

        @StringRes
        private fun stepTitleRes(step: AgentStep): Int = when (step) {
            AgentStep.OUTLINE -> R.string.agent_step_generating_outline
            AgentStep.DRAFT -> R.string.agent_step_writing_draft
            AgentStep.SELF_REVIEW -> R.string.agent_step_reviewing_content
            AgentStep.IMAGE_PROMPT -> R.string.agent_step_generating_image_prompt
            AgentStep.IMAGE -> R.string.agent_step_generating_image
            AgentStep.PACKAGE -> R.string.agent_step_completing_result
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChannelProgressUiState>() {
        override fun areItemsTheSame(
            oldItem: ChannelProgressUiState,
            newItem: ChannelProgressUiState,
        ): Boolean = oldItem.channel == newItem.channel

        override fun areContentsTheSame(
            oldItem: ChannelProgressUiState,
            newItem: ChannelProgressUiState,
        ): Boolean = oldItem == newItem
    }
}
