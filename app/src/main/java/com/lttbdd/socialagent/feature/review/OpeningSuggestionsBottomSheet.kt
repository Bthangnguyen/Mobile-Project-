package com.lttbdd.socialagent.feature.review

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.databinding.BottomSheetOpeningSuggestionsBinding
import com.lttbdd.socialagent.databinding.ItemOpeningSuggestionBinding
import com.lttbdd.socialagent.feature.review.hook.OpeningOptionUi
import com.lttbdd.socialagent.feature.review.hook.OpeningSuggestionUiState
import kotlinx.coroutines.launch

/**
 * Hộp chọn 3 phương án câu mở đầu (F4, Issue #63).
 *
 * Dùng chung [DraftPreviewViewModel] với [DraftPreviewFragment] (fragment cha), nên xoay màn hình vẫn giữ
 * các phương án và không gọi lại LLM. Sheet chỉ hiển thị state và chuyển sự kiện cho ViewModel;
 * tự đóng khi state về [OpeningSuggestionUiState.Hidden].
 */
class OpeningSuggestionsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetOpeningSuggestionsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DraftPreviewViewModel by viewModels(ownerProducer = { requireParentFragment() })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetOpeningSuggestionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityHeading(binding.tvOpeningSheetTitle, true)
        binding.btnKeepOpening.setOnClickListener { viewModel.onOpeningSuggestionDismissed() }
        binding.btnRegenerateOpening.setOnClickListener { viewModel.onSuggestOpeningClicked() }
        binding.btnRetryOpening.setOnClickListener { viewModel.onSuggestOpeningClicked() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.openingSuggestion.collect { state -> render(state) }
            }
        }
    }

    /** Vuốt xuống, bấm ra ngoài hoặc nút Back: coi như Giữ nguyên và huỷ request đang chạy. */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        viewModel.onOpeningSuggestionDismissed()
    }

    private fun render(state: OpeningSuggestionUiState) {
        val currentOpening = when (state) {
            OpeningSuggestionUiState.Hidden -> {
                dismiss()
                return
            }
            is OpeningSuggestionUiState.Loading -> state.currentOpening
            is OpeningSuggestionUiState.Ready -> state.currentOpening
            is OpeningSuggestionUiState.Error -> state.currentOpening
        }
        binding.tvCurrentOpening.text = currentOpening.orEmpty()
        binding.loadingContainer.isVisible = state is OpeningSuggestionUiState.Loading
        binding.errorContainer.isVisible = state is OpeningSuggestionUiState.Error
        binding.optionsContainer.isVisible = state is OpeningSuggestionUiState.Ready
        binding.btnRegenerateOpening.isEnabled = state is OpeningSuggestionUiState.Ready
        if (state is OpeningSuggestionUiState.Ready) {
            renderOptions(state.options)
        }
    }

    private fun renderOptions(options: List<OpeningOptionUi>) {
        binding.optionsContainer.removeAllViews()
        options.forEachIndexed { index, option ->
            val item = ItemOpeningSuggestionBinding.inflate(layoutInflater, binding.optionsContainer, true)
            val number = index + 1
            item.tvOptionLabel.text = getString(R.string.preview_opening_option_label, number)
            item.tvOptionText.text = option.text
            item.tvOptionCharCount.text = resources.getQuantityString(
                R.plurals.preview_opening_option_char_count,
                option.characterCount,
                option.characterCount,
            )
            item.tvOptionOverLimit.isVisible = option.exceedsChannelLimit

            // TalkBack đọc cả thẻ một lần: số thứ tự, nội dung, số ký tự và cảnh báo (nếu có).
            val description = resources.getQuantityString(
                R.plurals.preview_opening_option_description,
                option.characterCount,
                number,
                option.text,
                option.characterCount,
            )
            item.root.contentDescription = if (option.exceedsChannelLimit) {
                "$description ${getString(R.string.preview_opening_option_over_limit)}"
            } else {
                description
            }

            item.root.isEnabled = !option.exceedsChannelLimit
            item.root.alpha = if (option.exceedsChannelLimit) DISABLED_ALPHA else 1f
            if (!option.exceedsChannelLimit) {
                item.root.setOnClickListener { viewModel.onOpeningSelected(index) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "OpeningSuggestionsBottomSheet"
        private const val DISABLED_ALPHA = 0.6f
    }
}
