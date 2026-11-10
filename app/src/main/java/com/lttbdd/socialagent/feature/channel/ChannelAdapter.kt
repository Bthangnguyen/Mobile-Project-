package com.lttbdd.socialagent.feature.channel

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.databinding.ItemCapabilityRowBinding
import com.lttbdd.socialagent.databinding.ItemChannelAccountBinding

class ChannelAdapter(
    private val getScopeForAccount: (ChannelAccount) -> String?,
) : ListAdapter<ChannelAccount, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelAccountBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ChannelViewHolder(binding, getScopeForAccount)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ChannelViewHolder(
        private val binding: ItemChannelAccountBinding,
        private val getScopeForAccount: (ChannelAccount) -> String?,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(account: ChannelAccount) {
            val context = binding.root.context
            binding.tvChannelType.text = account.type.name
            binding.tvDisplayName.text = account.displayName

            val statusText = when (account.status) {
                ConnectionStatus.CONNECTED -> context.getString(R.string.channel_status_connected)
                ConnectionStatus.TOKEN_EXPIRED -> context.getString(R.string.channel_status_expired)
                ConnectionStatus.DISCONNECTED -> context.getString(R.string.channel_status_disconnected)
            }
            binding.tvStatus.text = statusText
            binding.tvStatus.setTextColor(
                when (account.status) {
                    ConnectionStatus.CONNECTED -> Color.parseColor("#2E7D32")
                    ConnectionStatus.TOKEN_EXPIRED -> Color.parseColor("#E65100")
                    ConnectionStatus.DISCONNECTED -> Color.GRAY
                },
            )

            // Render 5 capabilities matrix
            binding.containerCapabilities.removeAllViews()
            val scopeString = getScopeForAccount(account)
            val capabilityItems = ChannelCapabilityEvaluator.evaluateCapabilities(account, scopeString)

            val inflater = LayoutInflater.from(context)
            capabilityItems.forEach { item ->
                val rowBinding = ItemCapabilityRowBinding.inflate(inflater, binding.containerCapabilities, false)
                rowBinding.tvCapName.text = context.getString(item.capabilityNameRes)
                rowBinding.tvCapStatus.text = context.getString(item.statusTextRes)
                rowBinding.tvCapCaption.text = context.getString(item.captionRes)

                val (chipBgColor, chipTextColor) = when (item.statusType) {
                    CapabilityStatusType.SUPPORTED -> Pair(Color.parseColor("#E8F5E9"), Color.parseColor("#1B5E20"))
                    CapabilityStatusType.REQUIRES_PERMISSION -> Pair(Color.parseColor("#FFF3E0"), Color.parseColor("#E65100"))
                    CapabilityStatusType.NOT_SUPPORTED -> Pair(Color.parseColor("#FFEBEE"), Color.parseColor("#B71C1C"))
                    CapabilityStatusType.UNVERIFIED -> Pair(Color.parseColor("#F5F5F5"), Color.parseColor("#616161"))
                    CapabilityStatusType.HANDED_OFF -> Pair(Color.parseColor("#E3F2FD"), Color.parseColor("#0D47A1"))
                }
                rowBinding.tvCapStatus.setBackgroundColor(chipBgColor)
                rowBinding.tvCapStatus.setTextColor(chipTextColor)

                binding.containerCapabilities.addView(rowBinding.root)
            }
        }
    }

    private class ChannelDiffCallback : DiffUtil.ItemCallback<ChannelAccount>() {
        override fun areItemsTheSame(oldItem: ChannelAccount, newItem: ChannelAccount): Boolean {
            return oldItem.type == newItem.type && oldItem.accountId == newItem.accountId
        }

        override fun areContentsTheSame(oldItem: ChannelAccount, newItem: ChannelAccount): Boolean {
            return oldItem == newItem
        }
    }
}
