package com.lttbdd.socialagent.feature.agentlog

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.ui.components.AppDialog
import com.lttbdd.socialagent.core.ui.components.AppSnackbar
import com.lttbdd.socialagent.databinding.FragmentAgentLogBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Màn hình Nhật ký agent (F2, Issue #31): xem từng bước, prompt, kết quả, số token và chi phí ước tính.
 */
@AndroidEntryPoint
class AgentLogFragment : Fragment() {

    private var _binding: FragmentAgentLogBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AgentLogViewModel by viewModels()
    private lateinit var adapter: AgentLogAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAgentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        observeViewModel()
    }

    private fun setupViews() {
        adapter = AgentLogAdapter(
            onToggleExpand = viewModel::onToggleRunExpanded,
        )
        binding.rvAgentLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAgentLogs.adapter = adapter

        binding.rgChannelFilter.setOnCheckedChangeListener { _, checkedId ->
            val channel = when (checkedId) {
                R.id.rbFilterFacebook -> ChannelType.FACEBOOK
                R.id.rbFilterLinkedIn -> ChannelType.LINKEDIN
                else -> null
            }
            viewModel.onChannelFilterSelected(channel)
        }

        binding.btnClearLogs.setOnClickListener {
            viewModel.onClearRequested()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect(::renderUiState)
                }
                launch {
                    viewModel.events.collect(::handleEvent)
                }
            }
        }
    }

    private fun renderUiState(state: AgentLogUiState) {
        binding.loadingIndicator.isVisible = state.isLoading || state.isClearing
        binding.btnClearLogs.isEnabled = !state.isClearing && state.totalRunCount > 0

        val targetFilterId = when (state.selectedChannel) {
            ChannelType.FACEBOOK -> R.id.rbFilterFacebook
            ChannelType.LINKEDIN -> R.id.rbFilterLinkedIn
            null -> R.id.rbFilterAll
        }
        if (binding.rgChannelFilter.checkedRadioButtonId != targetFilterId) {
            binding.rgChannelFilter.check(targetFilterId)
        }

        if (state.isLoading) {
            binding.emptyState.isVisible = false
            binding.rvAgentLogs.isVisible = false
            return
        }

        if (state.isLoadError) {
            binding.emptyState.setContent(
                title = getString(R.string.agent_log_load_error_title),
                message = getString(R.string.agent_log_load_error_message),
            )
            binding.emptyState.isVisible = true
            binding.rvAgentLogs.isVisible = false
            return
        }

        if (state.items.isEmpty()) {
            binding.emptyState.setContent(
                title = getString(R.string.agent_log_empty_title),
                message = getString(R.string.agent_log_empty_message),
            )
            binding.emptyState.isVisible = true
            binding.rvAgentLogs.isVisible = false
            return
        }

        binding.emptyState.isVisible = false
        binding.rvAgentLogs.isVisible = true
        adapter.submitList(state.items)
    }

    private fun handleEvent(event: AgentLogEvent) {
        when (event) {
            AgentLogEvent.ConfirmClearAll -> {
                AppDialog.show(
                    context = requireContext(),
                    titleRes = R.string.agent_log_clear_dialog_title,
                    messageRes = R.string.agent_log_clear_dialog_message,
                    positiveActionRes = R.string.agent_log_clear_confirm,
                    negativeActionRes = R.string.action_cancel,
                    onPositiveAction = viewModel::onClearConfirmed,
                )
            }
            is AgentLogEvent.ShowMessage -> {
                AppSnackbar.show(binding.root, event.messageRes)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
