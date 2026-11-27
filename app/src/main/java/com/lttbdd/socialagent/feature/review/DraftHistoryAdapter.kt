package com.lttbdd.socialagent.feature.review

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.data.review.DraftVersion
import com.lttbdd.socialagent.data.review.DraftVersionSource
import com.lttbdd.socialagent.databinding.ItemDraftVersionBinding
import java.text.DateFormat
import java.util.Date

/**
 * Adapter hiển thị danh sách các phiên bản lịch sử của một bản nháp (F4, Issue #62).
 */
class DraftHistoryAdapter(
    private val onRestoreClick: (DraftVersion) -> Unit,
) : ListAdapter<DraftHistoryItemUiState, DraftHistoryAdapter.VersionViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VersionViewHolder {
        val binding = ItemDraftVersionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VersionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VersionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VersionViewHolder(
        private val binding: ItemDraftVersionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DraftHistoryItemUiState) {
            val context = binding.root.context
            val version = item.version

            binding.tvVersionNumber.text = context.getString(
                R.string.history_version_badge,
                version.versionNumber,
            )
            binding.tvVersionSource.setText(
                when (version.source) {
                    DraftVersionSource.INITIAL -> R.string.history_source_initial
                    DraftVersionSource.AI_GENERATED -> R.string.history_source_ai
                    DraftVersionSource.MANUAL_EDIT -> R.string.history_source_manual
                    DraftVersionSource.RESTORED -> R.string.history_source_restored
                },
            )
            binding.tvCurrentBadge.isVisible = item.isCurrent

            val formattedTime = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
            ).format(Date(version.createdAtMillis))
            val charsText = context.getString(R.string.history_char_count_format, item.characterCount)
            binding.tvVersionMeta.text = context.getString(
                R.string.history_meta_format,
                formattedTime,
                charsText,
            )
            binding.tvVersionText.text = version.text

            binding.btnRestoreVersion.isVisible = !item.isCurrent
            binding.btnRestoreVersion.isEnabled = item.canRestore
            binding.btnRestoreVersion.setOnClickListener {
                onRestoreClick(version)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<DraftHistoryItemUiState>() {
        override fun areItemsTheSame(
            oldItem: DraftHistoryItemUiState,
            newItem: DraftHistoryItemUiState,
        ): Boolean = oldItem.version.id == newItem.version.id

        override fun areContentsTheSame(
            oldItem: DraftHistoryItemUiState,
            newItem: DraftHistoryItemUiState,
        ): Boolean = oldItem == newItem
    }
}
