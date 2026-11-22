package com.lttbdd.socialagent.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.data.settings.AppLanguage
import com.lttbdd.socialagent.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** Màn Cài đặt (F9). Hiện có đổi ngôn ngữ VI/EN và mục API key (F8); mục Kênh của tôi (F1) sẽ được thêm vào đây. */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    private val languageListener = RadioGroup.OnCheckedChangeListener { _, checkedId ->
        languageFor(checkedId)?.let(viewModel::onLanguageSelected)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Đánh dấu tiêu đề để TalkBack cho phép nhảy giữa các mục (android:accessibilityHeading cần API 28).
        ViewCompat.setAccessibilityHeading(binding.settingsTitle, true)
        ViewCompat.setAccessibilityHeading(binding.languageTitle, true)
        ViewCompat.setAccessibilityHeading(binding.apiKeysSectionTitle, true)
        binding.manageApiKeys.setOnClickListener { openApiKeys() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    private fun render(state: SettingsUiState) {
        val group = binding.languageGroup
        // Gỡ listener khi cập nhật từ state để không gửi ngược lựa chọn về ViewModel.
        group.setOnCheckedChangeListener(null)
        group.check(radioIdFor(state.language))
        group.setOnCheckedChangeListener(languageListener)
    }

    /** Mở màn quản lý API key (F8). Bỏ qua lần bấm lặp khi đã rời Settings để tránh lỗi điều hướng. */
    private fun openApiKeys() {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.settingsFragment) {
            navController.navigate(R.id.action_settings_to_api_keys)
        }
    }

    private fun radioIdFor(language: AppLanguage) = when (language) {
        AppLanguage.SYSTEM -> R.id.language_system
        AppLanguage.VIETNAMESE -> R.id.language_vietnamese
        AppLanguage.ENGLISH -> R.id.language_english
    }

    private fun languageFor(radioId: Int) = when (radioId) {
        R.id.language_system -> AppLanguage.SYSTEM
        R.id.language_vietnamese -> AppLanguage.VIETNAMESE
        R.id.language_english -> AppLanguage.ENGLISH
        else -> null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
