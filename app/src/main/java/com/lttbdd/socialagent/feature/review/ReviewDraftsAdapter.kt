package com.lttbdd.socialagent.feature.review

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.databinding.ItemReviewDraftBinding

/**
 * Adapter hiển thị danh sách bản nháp cần duyệt cùng trạng thái phê duyệt và nút Đăng bài có khóa an toàn.
 */
class ReviewDraftsAdapter(
    private val onPreviewEditClick: (String) -> Unit,
    private val onApproveClick: (draftId: String, displayedText: String) -> Unit,
    private val onRevokeClick: (String) -> Unit,
    private val onPublishClick: (String) -> Unit,
) : ListAdapter<ReviewDraftItemUiState, ReviewDraftsAdapter.DraftViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DraftViewHolder {
        val binding = ItemReviewDraftBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return DraftViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DraftViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DraftViewHolder(
        private val binding: ItemReviewDraftBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ReviewDraftItemUiState) {
            val context = binding.root.context
            val draft = item.draft

            binding.tvChannel.setText(
                when (draft.channel) {
                    ChannelType.FACEBOOK -> R.string.compose_channel_facebook
                    ChannelType.LINKEDIN -> R.string.compose_channel_linkedin
                },
            )
            binding.tvDraftText.text = draft.text

            if (draft.approved) {
                binding.statusApproval.setText(R.string.review_status_approved)
                binding.statusApproval.setTextColor(
                    ContextCompat.getColor(context, R.color.brand_success),
                )
                binding.statusApproval.contentDescription = binding.statusApproval.text
            } else {
                binding.statusApproval.setPendingApproval()
            }

            binding.tvApprovalHint.isVisible = !draft.approved

            if (item.warnings.isNotEmpty()) {
                binding.tvPrePublishWarnings.text =
                    PrePublishWarningFormatter.formatBulletList(context, item.warnings)
                binding.tvPrePublishWarnings.isVisible = true
            } else {
                binding.tvPrePublishWarnings.isVisible = false
            }

            binding.btnApprove.isVisible = !draft.approved
            binding.btnRevoke.isVisible = draft.approved
            binding.btnPublish.isEnabled = item.canPublish

            binding.btnPreviewEdit.setOnClickListener { onPreviewEditClick(draft.id) }
            binding.btnApprove.setOnClickListener { onApproveClick(draft.id, draft.text) }
            binding.btnRevoke.setOnClickListener { onRevokeClick(draft.id) }
            binding.btnPublish.setOnClickListener { onPublishClick(draft.id) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ReviewDraftItemUiState>() {
        override fun areItemsTheSame(
            oldItem: ReviewDraftItemUiState,
            newItem: ReviewDraftItemUiState,
        ): Boolean = oldItem.draft.id == newItem.draft.id

        override fun areContentsTheSame(
            oldItem: ReviewDraftItemUiState,
            newItem: ReviewDraftItemUiState,
        ): Boolean = oldItem == newItem
    }
}
