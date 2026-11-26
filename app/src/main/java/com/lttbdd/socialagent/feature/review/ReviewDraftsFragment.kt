package com.lttbdd.socialagent.feature.review

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.ui.components.AppDialog
import com.lttbdd.socialagent.core.ui.components.AppSnackbar
import com.lttbdd.socialagent.databinding.FragmentReviewDraftsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Màn hình duyệt bản nháp (tab Bài viết — F4, Issue #33).
 * Mọi bản nháp mới từ agent mặc định ở trạng thái chờ duyệt; nút Đăng bài chỉ mở sau khi người dùng duyệt.
 */
@AndroidEntryPoint
class ReviewDraftsFragment : Fragment() {

    private var _binding: FragmentReviewDraftsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReviewDraftsViewModel by viewModels()
    private lateinit var adapter: ReviewDraftsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentReviewDraftsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        observeViewModel()
    }

    private fun setupViews() {
        adapter = ReviewDraftsAdapter(
            onPreviewEditClick = { draftId ->
                val args = Bundle().apply {
                    putString(DraftPreviewViewModel.ARG_DRAFT_ID, draftId)
                }
                findNavController().navigate(R.id.action_posts_to_draft_preview, args)
            },
            onApproveClick = viewModel::onApproveRequested,
            onRevokeClick = viewModel::onRevokeApproval,
            onPublishClick = viewModel::onPublishRequested,
        )
        binding.rvDrafts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDrafts.adapter = adapter
        binding.emptyState.setContent(
            title = getString(R.string.review_empty_title),
            message = getString(R.string.review_empty_message),
        )
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderUiState(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        handleEvent(event)
                    }
                }
            }
        }
    }

    private fun renderUiState(state: ReviewDraftsUiState) {
        binding.loadingIndicator.isVisible = state.isLoading
        val isEmpty = !state.isLoading && state.drafts.isEmpty()
        binding.emptyState.isVisible = isEmpty
        binding.rvDrafts.isVisible = !state.isLoading && state.drafts.isNotEmpty()
        adapter.submitList(state.drafts)
    }

    private fun handleEvent(event: ReviewDraftsEvent) {
        when (event) {
            is ReviewDraftsEvent.ConfirmApprove -> {
                AppDialog.show(
                    context = requireContext(),
                    titleRes = R.string.review_approve_dialog_title,
                    messageRes = R.string.review_approve_dialog_message,
                    positiveActionRes = R.string.review_action_approve,
                    negativeActionRes = R.string.action_cancel,
                    onPositiveAction = {
                        viewModel.onApproveConfirmed(event.draftId, event.expectedText)
                    },
                )
            }
            is ReviewDraftsEvent.ShowMessage -> {
                AppSnackbar.show(binding.root, event.messageRes)
            }
            is ReviewDraftsEvent.ReadyToPublish -> {
                AppSnackbar.show(binding.root, R.string.review_publish_ready_message)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
