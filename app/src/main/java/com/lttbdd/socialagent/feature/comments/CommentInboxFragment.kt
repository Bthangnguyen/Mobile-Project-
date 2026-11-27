package com.lttbdd.socialagent.feature.comments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.database.dao.CommentPostKey
import com.lttbdd.socialagent.core.database.dao.CommentPostPreview
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.CommentStatus
import com.lttbdd.socialagent.databinding.FragmentCommentInboxBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CommentInboxFragment : Fragment() {
    private var _binding: FragmentCommentInboxBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: CommentInboxViewModel by viewModels()
    private var visiblePosts: List<CommentPostPreview>? = null
    private var excerpts: Map<CommentPostKey, String?> = emptyMap()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCommentInboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val items = CommentInboxAdapter()
        binding.comments.layoutManager = LinearLayoutManager(requireContext())
        binding.comments.adapter = items

        binding.channelFilter.setOptions(listOf(getString(R.string.comment_inbox_all_channels)) +
            ChannelType.entries.map { getString(it.inboxLabel()) })
        binding.channelFilter.setOnItemClickListener { _, _, position, _ ->
            val channel = ChannelType.entries.getOrNull(position - 1)
            if (channel != viewModel.uiState.value.filter.channel) binding.postFilter.isEnabled = false
            viewModel.selectChannel(channel)
        }
        binding.statusFilter.setOptions(listOf(getString(R.string.comment_inbox_all_statuses)) +
            CommentStatus.entries.map { getString(it.inboxLabel()) })
        binding.statusFilter.setOnItemClickListener { _, _, position, _ ->
            viewModel.selectStatus(CommentStatus.entries.getOrNull(position - 1))
        }
        binding.postFilter.setOnItemClickListener { _, _, position, _ ->
            viewModel.selectPost(visiblePosts?.getOrNull(position - 1)?.key)
        }
        binding.clearFilters.setOnClickListener { viewModel.clearFilters() }
        binding.previousPage.setOnClickListener { viewModel.previousPage() }
        binding.nextPage.setOnClickListener { viewModel.nextPage() }
        binding.retry.setOnClickListener { viewModel.retry() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state, items)
                }
            }
        }
    }

    private fun render(state: CommentInboxState, items: CommentInboxAdapter) {
        val filter = state.filter
        if (visiblePosts != state.posts) {
            visiblePosts = state.posts
            excerpts = state.posts.associate { it.key to it.excerpt }
            binding.postFilter.setOptions(
                listOf(getString(R.string.comment_inbox_all_posts)) + state.posts.map { postLabel(it.key, it.excerpt) },
            )
        }
        binding.channelFilter.setText(getString(filter.channel?.inboxLabel() ?: R.string.comment_inbox_all_channels), false)
        binding.statusFilter.setText(getString(filter.status?.inboxLabel() ?: R.string.comment_inbox_all_statuses), false)
        binding.postFilter.setText(
            filter.post?.let { postLabel(it, excerpts[it]) } ?: getString(R.string.comment_inbox_all_posts),
            false,
        )
        binding.postFilter.isEnabled = filter.post != null || state.posts.isNotEmpty()
        binding.clearFilters.isEnabled = state.hasFilters
        binding.loading.isVisible = state.isLoading
        binding.retry.isVisible = state.hasError
        val message = when (state.emptyState) {
            CommentInboxEmptyState.ERROR -> R.string.comment_inbox_load_failed
            CommentInboxEmptyState.NO_RESULTS -> R.string.comment_inbox_no_results
            CommentInboxEmptyState.NO_COMMENTS -> R.string.comment_inbox_empty
            null -> null
        }
        binding.message.isVisible = message != null
        if (message != null) binding.message.setText(message)
        binding.previousPage.isVisible = state.hasPrevious && !state.isLoading && !state.hasError
        binding.nextPage.isVisible = state.hasMore
        binding.comments.isVisible = state.comments.isNotEmpty()
        if (state.isLoading) binding.comments.scrollToPosition(0)
        items.submitList(state.comments.map { comment ->
            CommentInboxItem(comment, excerpts[CommentPostKey(comment.channel, comment.remotePostId)])
        })
    }

    private fun postLabel(post: CommentPostKey, excerpt: String?): String =
        requireContext().inboxPostLabel(post, excerpt)

    private fun MaterialAutoCompleteTextView.setOptions(options: List<String>) {
        setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, options))
    }

    override fun onDestroyView() {
        binding.comments.adapter = null
        visiblePosts = null
        excerpts = emptyMap()
        _binding = null
        super.onDestroyView()
    }
}
