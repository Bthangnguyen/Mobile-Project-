package com.lttbdd.socialagent.feature.review

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.ui.components.AppDialog
import com.lttbdd.socialagent.core.ui.components.AppSnackbar
import com.lttbdd.socialagent.databinding.FragmentDraftPreviewBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Màn hình xem trước bài viết theo giao diện từng kênh (Facebook Page / LinkedIn),
 * sửa tay nội dung và đếm ký tự theo giới hạn kênh (F4, Issue #34).
 */
@AndroidEntryPoint
class DraftPreviewFragment : Fragment() {

    private var _binding: FragmentDraftPreviewBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DraftPreviewViewModel by viewModels()

    private val unsavedChangesBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            AppDialog.show(
                context = requireContext(),
                titleRes = R.string.preview_discard_dialog_title,
                messageRes = R.string.preview_discard_dialog_message,
                positiveActionRes = R.string.preview_discard_action,
                negativeActionRes = R.string.preview_stay_action,
                onPositiveAction = {
                    isEnabled = false
                    if (!findNavController().navigateUp()) {
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                },
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDraftPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            unsavedChangesBackCallback,
        )
        setupEmptyState()
        setupListeners()
        observeViewModel()
    }

    private fun setupEmptyState() {
        binding.emptyStateNotFound.setContent(
            title = getString(R.string.preview_not_found_title),
            message = getString(R.string.preview_not_found_message),
        )
    }

    private fun setupListeners() {
        binding.rgPreviewChannel.setOnCheckedChangeListener { _, checkedId ->
            val channel = if (checkedId == R.id.rbPreviewLinkedIn) {
                ChannelType.LINKEDIN
            } else {
                ChannelType.FACEBOOK
            }
            viewModel.onChannelPreviewSelected(channel)
        }

        binding.etDraftContent.doAfterTextChanged { editable ->
            viewModel.onTextChanged(editable?.toString().orEmpty())
        }

        binding.btnResetEdits.setOnClickListener {
            viewModel.onResetEdits()
        }

        binding.btnSaveEdits.setOnClickListener {
            viewModel.onSaveClicked()
        }
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
                        when (event) {
                            is DraftPreviewEvent.ShowMessage -> {
                                AppSnackbar.show(binding.root, event.messageRes)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderUiState(state: DraftPreviewUiState) {
        binding.loadingIndicator.isVisible = state.isLoading
        binding.emptyStateNotFound.isVisible = !state.isLoading && state.isDraftNotFound
        binding.contentContainer.isVisible = !state.isLoading && !state.isDraftNotFound
        unsavedChangesBackCallback.isEnabled = state.hasUnsavedChanges

        if (state.isLoading || state.isDraftNotFound) return

        // Cảnh báo & khóa sửa khi bản nháp đang có bài chờ đăng / đang đăng (F5)
        binding.tvPublishingLockedWarning.isVisible = state.isEditingLockedByPublishing
        binding.etDraftContent.isEnabled = !state.isEditingLockedByPublishing

        // Cập nhật bộ chọn kênh xem trước
        val targetRadioId = if (state.selectedChannel == ChannelType.LINKEDIN) {
            R.id.rbPreviewLinkedIn
        } else {
            R.id.rbPreviewFacebook
        }
        if (binding.rgPreviewChannel.checkedRadioButtonId != targetRadioId) {
            binding.rgPreviewChannel.check(targetRadioId)
        }

        // Hiển thị giao diện xem trước mô phỏng theo từng kênh
        renderChannelPreview(state.selectedChannel, state.editedText)

        // Đồng bộ ô nhập sửa tay (tránh nhảy con trỏ khi người dùng đang gõ)
        if (binding.etDraftContent.text?.toString() != state.editedText) {
            binding.etDraftContent.setText(state.editedText)
        }
        binding.layoutDraftContent.error = state.textErrorRes?.let { getString(it) }

        // Bộ đếm ký tự theo giới hạn kênh xem trước
        renderCharacterCounter(state.characterCount, state.selectedChannel, state.draft?.channel)

        // Cảnh báo nội dung trước khi đăng (số liệu chưa nguồn, PII, lời lẽ nhạy cảm)
        if (state.prePublishWarnings.isNotEmpty()) {
            binding.tvPrePublishWarnings.text =
                PrePublishWarningFormatter.formatBulletList(requireContext(), state.prePublishWarnings)
            binding.cardPrePublishWarnings.isVisible = true
        } else {
            binding.cardPrePublishWarnings.isVisible = false
        }

        // Nút Khôi phục và Lưu chỉnh sửa
        binding.btnResetEdits.isEnabled = state.hasUnsavedChanges
        binding.btnSaveEdits.isEnabled = state.canSave && state.hasUnsavedChanges
    }

    private fun renderChannelPreview(channel: ChannelType, text: String) {
        val isLinkedIn = channel == ChannelType.LINKEDIN
        val shouldFoldLinkedIn = isLinkedIn &&
            DraftCharacterCounter.countCharacters(text) > LINKEDIN_FOLD_CHARS
        val previewBody = when {
            text.isBlank() -> getString(R.string.preview_placeholder_body)
            shouldFoldLinkedIn -> DraftCharacterCounter.truncateByCodePoints(text, LINKEDIN_FOLD_CHARS)
            else -> text
        }

        binding.tvPreviewAuthorName.setText(
            if (isLinkedIn) R.string.preview_linkedin_author else R.string.preview_facebook_author,
        )
        binding.tvPreviewMeta.setText(
            if (isLinkedIn) R.string.preview_linkedin_meta else R.string.preview_facebook_meta,
        )
        binding.tvPreviewBody.text = previewBody
        binding.tvPreviewSeeMore.isVisible = shouldFoldLinkedIn
        binding.tvPreviewActions.setText(
            if (isLinkedIn) R.string.preview_linkedin_actions else R.string.preview_facebook_actions,
        )
    }

    private fun renderCharacterCounter(
        status: CharacterCountStatus,
        selectedChannel: ChannelType,
        draftChannel: ChannelType?,
    ) {
        binding.tvCharCount.text = getString(
            R.string.preview_char_count_format,
            status.currentLength,
            status.maxChars,
        )
        val colorRes = when (status.level) {
            CharacterLimitLevel.NORMAL -> R.color.brand_on_surface_variant
            CharacterLimitLevel.NEAR_LIMIT -> R.color.brand_warning
            CharacterLimitLevel.EXCEEDED -> R.color.brand_error
        }
        binding.tvCharCount.setTextColor(ContextCompat.getColor(requireContext(), colorRes))

        if (status.isOverLimit) {
            binding.tvCharExcess.text = getString(
                R.string.preview_char_excess_format,
                status.excessChars,
            )
            binding.tvCharExcess.isVisible = true
        } else {
            binding.tvCharExcess.isVisible = false
        }

        val showReferenceNote = status.isOverLimit &&
            draftChannel != null &&
            selectedChannel != draftChannel
        if (showReferenceNote) {
            val channelLabel = getString(
                if (draftChannel == ChannelType.LINKEDIN) {
                    R.string.compose_channel_linkedin
                } else {
                    R.string.compose_channel_facebook
                },
            )
            binding.tvPreviewReferenceNote.text = getString(
                R.string.preview_char_reference_note,
                channelLabel,
            )
            binding.tvPreviewReferenceNote.isVisible = true
        } else {
            binding.tvPreviewReferenceNote.isVisible = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Ngưỡng ước lượng điểm gập nội dung trên feed LinkedIn (thực tế thay đổi theo thiết bị và số dòng). */
        const val LINKEDIN_FOLD_CHARS = 210
    }
}
