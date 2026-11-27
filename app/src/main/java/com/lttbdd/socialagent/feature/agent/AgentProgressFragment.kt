package com.lttbdd.socialagent.feature.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.ui.components.ai.AgentStepStatus
import com.lttbdd.socialagent.databinding.FragmentAgentProgressBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Màn hình hiển thị tiến độ từng bước và stream nội dung trực tiếp của AI Agent (F2, Issue #29).
 */
@AndroidEntryPoint
class AgentProgressFragment : Fragment() {

    private var _binding: FragmentAgentProgressBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AgentProgressViewModel by viewModels()

    private lateinit var channelAdapter: AgentChannelProgressAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAgentProgressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupListeners()
        observeUiState()

        val brief = AgentBriefArgs.fromBundle(arguments)
        if (brief != null) {
            viewModel.startGeneration(brief)
        } else {
            viewModel.onMissingBrief()
        }
    }

    private fun setupRecyclerView() {
        channelAdapter = AgentChannelProgressAdapter(
            onReviewDraftClicked = { draftId ->
                val args = Bundle().apply { putString(ARG_DRAFT_ID, draftId) }
                findNavController().navigate(R.id.action_agent_progress_to_draft_preview, args)
            },
        )
        binding.rvChannelProgress.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = channelAdapter
            // Tắt change animation để tránh nháy màn hình khi nhận TextDelta liên tục
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.btnBackToCompose.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.btnCreateAnother.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.btnOpenPosts.setOnClickListener {
            findNavController().navigate(R.id.action_agent_progress_to_posts)
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state)
                }
            }
        }
    }

    private fun renderUiState(state: AgentProgressUiState) {
        if (state.missingBrief) {
            binding.cardMissingBrief.visibility = View.VISIBLE
            binding.cardBriefSummary.visibility = View.GONE
            binding.rvChannelProgress.visibility = View.GONE
            binding.progressOverall.visibility = View.GONE
            binding.layoutBottomActions.visibility = View.GONE
            binding.tvOverallStatus.setText(R.string.agent_progress_missing_brief_title)
            return
        }

        binding.cardMissingBrief.visibility = View.GONE
        binding.cardBriefSummary.visibility = View.VISIBLE
        binding.rvChannelProgress.visibility = View.VISIBLE

        binding.progressOverall.visibility = if (state.isRunning) View.VISIBLE else View.GONE
        binding.tvOverallStatus.text = overallStatusText(state)

        // Thông tin brief chung
        binding.tvBriefTopic.text = state.brief?.topic.orEmpty()
        binding.tvBriefSummary.text = state.briefSummary.ifBlank {
            getString(R.string.agent_progress_preparing_brief)
        }
        binding.tvBriefStatus.setText(state.briefStatus.labelRes)
        binding.tvBriefStatus.setTextColor(
            ContextCompat.getColor(requireContext(), state.briefStatus.colorRes),
        )

        // Danh sách tiến độ và stream theo từng kênh
        channelAdapter.submitProgress(state.briefStatus, state.channels)

        // Hành động cuối phiên
        val isFinished = state.runStatus == AgentRunStatus.COMPLETED ||
            state.runStatus == AgentRunStatus.PARTIAL_FAILURE ||
            state.runStatus == AgentRunStatus.FAILED
        binding.layoutBottomActions.visibility = if (isFinished) View.VISIBLE else View.GONE
        binding.btnOpenPosts.visibility = if (state.hasAnySavedDraft) View.VISIBLE else View.GONE
    }

    private fun overallStatusText(state: AgentProgressUiState): String = when (state.runStatus) {
        AgentRunStatus.IDLE -> getString(R.string.agent_progress_waiting)
        AgentRunStatus.RUNNING -> getString(
            R.string.agent_progress_status_running_count,
            state.completedChannelCount,
            state.channels.size,
        )

        AgentRunStatus.COMPLETED -> getString(
            R.string.agent_progress_status_completed,
            state.completedChannelCount,
        )

        AgentRunStatus.PARTIAL_FAILURE -> getString(
            R.string.agent_progress_status_partial,
            state.completedChannelCount,
            state.channels.size,
        )

        AgentRunStatus.FAILED -> getString(R.string.agent_progress_status_failed)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val ARG_DRAFT_ID = "draftId"
    }
}
