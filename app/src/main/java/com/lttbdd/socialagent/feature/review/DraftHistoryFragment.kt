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
import com.lttbdd.socialagent.databinding.FragmentDraftHistoryBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Màn hình xem lịch sử phiên bản bản nháp và khôi phục phiên bản trước (F4, Issue #62).
 */
@AndroidEntryPoint
class DraftHistoryFragment : Fragment() {

    private var _binding: FragmentDraftHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DraftHistoryViewModel by viewModels()
    private lateinit var adapter: DraftHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDraftHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        observeViewModel()
    }

    private fun setupViews() {
        adapter = DraftHistoryAdapter(
            onRestoreClick = viewModel::onRestoreRequested,
        )
        binding.rvVersions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvVersions.adapter = adapter
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

    private fun renderUiState(state: DraftHistoryUiState) {
        binding.loadingIndicator.isVisible = state.isLoading || state.isRestoring
        binding.tvPublishingLockedWarning.isVisible =
            !state.isLoading && !state.isDraftNotFound && !state.isLoadError && state.isRestoreLockedByPublishing

        if (state.isLoading) {
            binding.emptyState.isVisible = false
            binding.rvVersions.isVisible = false
            return
        }

        if (state.isLoadError) {
            binding.emptyState.setContent(
                title = getString(R.string.history_load_error_title),
                message = getString(R.string.history_load_error_message),
            )
            binding.emptyState.isVisible = true
            binding.rvVersions.isVisible = false
            return
        }

        if (state.isDraftNotFound) {
            binding.emptyState.setContent(
                title = getString(R.string.preview_not_found_title),
                message = getString(R.string.preview_not_found_message),
            )
            binding.emptyState.isVisible = true
            binding.rvVersions.isVisible = false
            return
        }

        if (state.versions.isEmpty()) {
            binding.emptyState.setContent(
                title = getString(R.string.history_empty_title),
                message = getString(R.string.history_empty_message),
            )
            binding.emptyState.isVisible = true
            binding.rvVersions.isVisible = false
            return
        }

        binding.emptyState.isVisible = false
        binding.rvVersions.isVisible = true
        adapter.submitList(state.versions)
    }

    private fun handleEvent(event: DraftHistoryEvent) {
        when (event) {
            is DraftHistoryEvent.ConfirmRestore -> {
                AppDialog.show(
                    context = requireContext(),
                    titleRes = R.string.history_restore_dialog_title,
                    messageRes = R.string.history_restore_dialog_message,
                    positiveActionRes = R.string.history_action_restore,
                    negativeActionRes = R.string.action_cancel,
                    onPositiveAction = {
                        viewModel.onRestoreConfirmed(event.version.id)
                    },
                )
            }
            is DraftHistoryEvent.ShowMessage -> {
                AppSnackbar.show(binding.root, event.messageRes)
            }
            DraftHistoryEvent.RestoredSuccessfully -> {
                findNavController().previousBackStackEntry?.savedStateHandle?.set(
                    KEY_VERSION_RESTORED,
                    true,
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val KEY_VERSION_RESTORED = "draft_version_restored"
    }
}
