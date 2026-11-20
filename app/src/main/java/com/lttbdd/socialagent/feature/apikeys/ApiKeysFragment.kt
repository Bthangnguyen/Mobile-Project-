package com.lttbdd.socialagent.feature.apikeys

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.data.apikeys.KeyStorageStatus
import com.lttbdd.socialagent.data.apikeys.KeyVerification
import com.lttbdd.socialagent.data.apikeys.VerificationReason
import com.lttbdd.socialagent.databinding.FragmentApiKeysBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ApiKeysFragment : Fragment() {
    private var _binding: FragmentApiKeysBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ApiKeysViewModel by viewModels()
    private var secureFlagWasSet = false
    private var deleteDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentApiKeysBinding.inflate(inflater, container, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.root.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.providerToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                binding.keyInput.text?.clear()
                viewModel.selectProvider(if (checkedId == R.id.open_ai_button) LlmProvider.OPENAI else LlmProvider.GEMINI)
            }
        }
        binding.verifyButton.setOnClickListener { submit(save = false) }
        binding.saveButton.setOnClickListener { submit(save = true) }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        val window = requireActivity().window
        secureFlagWasSet = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        binding.keyInput.text?.clear()
        deleteDialog?.dismiss()
        deleteDialog = null
        if (!secureFlagWasSet) requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    override fun onStop() {
        viewModel.cancelOperation()
        super.onStop()
    }

    override fun onDestroyView() {
        binding.keyInput.text?.clear()
        _binding = null
        super.onDestroyView()
    }

    private fun submit(save: Boolean) {
        val text = binding.keyInput.text
        val input = CharArray(text?.length ?: 0) { index -> text!![index] }
        text?.clear()
        viewModel.verify(input, save)
    }

    private fun confirmDelete() {
        val provider = viewModel.uiState.value.selectedProvider
        val name = getString(if (provider == LlmProvider.OPENAI) R.string.api_keys_openai else R.string.api_keys_gemini)
        deleteDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.api_keys_delete_title)
            .setMessage(getString(R.string.api_keys_delete_confirmation, name))
            .setNegativeButton(R.string.api_keys_cancel, null)
            .setPositiveButton(R.string.api_keys_delete) { _, _ ->
                binding.keyInput.text?.clear()
                viewModel.delete()
            }
            .show()
    }

    private fun render(state: ApiKeysUiState) {
        val busy = state.action != null
        binding.providerToggle.check(if (state.selectedProvider == LlmProvider.OPENAI) R.id.open_ai_button else R.id.gemini_button)
        binding.openAiButton.isEnabled = !busy
        binding.geminiButton.isEnabled = !busy
        binding.keyInput.isEnabled = !busy
        binding.verifyButton.isEnabled = !busy
        binding.saveButton.isEnabled = !busy
        binding.deleteButton.isEnabled = !busy && state.providers[state.selectedProvider] != KeyStorageStatus.NOT_CONFIGURED
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.openAiStatus.text = getString(R.string.api_keys_provider_status, getString(R.string.api_keys_openai), statusText(state.providers[LlmProvider.OPENAI]))
        binding.geminiStatus.text = getString(R.string.api_keys_provider_status, getString(R.string.api_keys_gemini), statusText(state.providers[LlmProvider.GEMINI]))
        binding.saveButton.setText(if (state.providers[state.selectedProvider] == KeyStorageStatus.CONFIGURED) R.string.api_keys_update else R.string.api_keys_save)
        val message = state.message?.let { messageResource(it) }
            ?: state.verification?.let { verificationResource(it) }
        binding.result.text = message?.let { getString(it) }.orEmpty()
    }

    private fun statusText(status: KeyStorageStatus?): String = getString(when (status) {
        null -> R.string.api_keys_loading
        KeyStorageStatus.NOT_CONFIGURED -> R.string.api_keys_not_configured
        KeyStorageStatus.CONFIGURED -> R.string.api_keys_configured_mask
        KeyStorageStatus.UNREADABLE -> R.string.api_keys_unreadable
        KeyStorageStatus.UNAVAILABLE -> R.string.api_keys_storage_error
    })

    @StringRes
    private fun messageResource(message: ApiKeyMessage): Int = when (message) {
        ApiKeyMessage.SAVED -> R.string.api_keys_saved
        ApiKeyMessage.DELETED -> R.string.api_keys_deleted
        ApiKeyMessage.STORAGE_ERROR -> R.string.api_keys_storage_error
        ApiKeyMessage.UNREADABLE -> R.string.api_keys_unreadable
        ApiKeyMessage.INPUT_ERROR -> R.string.api_keys_input_error
        ApiKeyMessage.OPERATION_ERROR -> R.string.api_keys_operation_error
    }

    @StringRes
    private fun verificationResource(verification: KeyVerification): Int = when (verification.reason) {
        VerificationReason.ACCEPTED -> R.string.api_keys_valid
        VerificationReason.INPUT -> R.string.api_keys_input_error
        VerificationReason.AUTHENTICATION -> R.string.api_keys_invalid
        VerificationReason.PERMISSION -> R.string.api_keys_permission
        VerificationReason.RATE_LIMIT -> R.string.api_keys_rate_limit
        VerificationReason.SERVICE -> R.string.api_keys_service
        VerificationReason.NETWORK -> R.string.api_keys_network
        VerificationReason.TIMEOUT -> R.string.api_keys_timeout
        VerificationReason.RESPONSE -> R.string.api_keys_unverified
    }
}
