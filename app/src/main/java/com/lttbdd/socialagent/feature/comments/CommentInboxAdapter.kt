package com.lttbdd.socialagent.feature.comments

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.database.dao.CommentPostKey
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.CommentStatus
import com.lttbdd.socialagent.databinding.ItemInboxCommentBinding
import java.text.DateFormat
import java.util.Date

internal data class CommentInboxItem(val comment: Comment, val postExcerpt: String?)

internal class CommentInboxAdapter : ListAdapter<CommentInboxItem, CommentInboxAdapter.CommentRow>(CommentDiff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentRow =
        CommentRow(ItemInboxCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: CommentRow, position: Int) = holder.bind(getItem(position))

    class CommentRow(private val binding: ItemInboxCommentBinding) : RecyclerView.ViewHolder(binding.root) {
        private val context = binding.root.context
        private val dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            context.resources.configuration.locales[0],
        )

        fun bind(item: CommentInboxItem) {
            val comment = item.comment
            binding.author.text = comment.authorName.ifBlank { context.getString(R.string.comment_inbox_unknown_author) }
            binding.body.text = comment.text
            binding.post.text = context.inboxPostLabel(
                CommentPostKey(comment.channel, comment.remotePostId),
                item.postExcerpt,
            )
            binding.time.text = dateFormat.format(Date(comment.createdAtMillis))
            binding.status.setText(comment.status.inboxLabel())
        }
    }

    private object CommentDiff : DiffUtil.ItemCallback<CommentInboxItem>() {
        override fun areItemsTheSame(oldItem: CommentInboxItem, newItem: CommentInboxItem): Boolean =
            oldItem.comment.channel == newItem.comment.channel && oldItem.comment.id == newItem.comment.id

        override fun areContentsTheSame(oldItem: CommentInboxItem, newItem: CommentInboxItem): Boolean = oldItem == newItem
    }
}

private val postWhitespace = Regex("""[\s\p{Z}]+""")
private const val POST_EXCERPT_LENGTH = 120

internal fun Context.inboxPostLabel(post: CommentPostKey, excerpt: String?): String {
    val channel = getString(post.channel.inboxLabel())
    val text = excerpt.orEmpty()
    val truncated = text.codePointCount(0, text.length) > POST_EXCERPT_LENGTH
    val excerptText = if (truncated) text.substring(0, text.offsetByCodePoints(0, POST_EXCERPT_LENGTH)) else text
    val preview = postWhitespace.replace(excerptText, " ").trim()
    if (preview.isEmpty()) return getString(R.string.comment_inbox_channel_post, channel, post.remotePostId)
    val label = if (truncated) "$preview…" else preview
    return getString(R.string.comment_inbox_channel_post_excerpt, channel, label)
}

internal fun ChannelType.inboxLabel(): Int = when (this) {
    ChannelType.FACEBOOK -> R.string.compose_channel_facebook
    ChannelType.LINKEDIN -> R.string.compose_channel_linkedin
}

internal fun CommentStatus.inboxLabel(): Int = when (this) {
    CommentStatus.NEW -> R.string.comment_inbox_status_new
    CommentStatus.REPLIED -> R.string.comment_inbox_status_replied
    CommentStatus.IGNORED -> R.string.comment_inbox_status_ignored
}
