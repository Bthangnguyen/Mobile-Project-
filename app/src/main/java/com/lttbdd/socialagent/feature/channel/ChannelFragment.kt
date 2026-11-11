package com.lttbdd.socialagent.feature.channel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.lttbdd.socialagent.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.repository.ChannelRepository
import com.lttbdd.socialagent.databinding.FragmentChannelBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Fragment hiển thị và quản lý màn hình Kênh của tôi (F1).
 * Nguồn tham chiếu:
 * - RFC 8252 Section 8.1: Mở trình duyệt ngoài (Custom Tabs / System Browser), không dùng WebView nhúng.
 * - AGENTS.md: ViewModel expose StateFlow, Fragment collect bằng repeatOnLifecycle(STARTED), _binding nullable.
 */
@AndroidEntryPoint
class ChannelFragment : Fragment() {

    private var _binding: FragmentChannelBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChannelViewModel by viewModels()

    private var facebookPageDialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var adapter: ChannelAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentChannelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeUiState()
    }

    /**
     * Đăng nhập Facebook: Mở FacebookAuthDialogFragment để hiển thị WebView nội bộ.
     */
    private fun loginWithFacebook() {
        val dialog = FacebookAuthDialogFragment.newInstance()
        dialog.onAuthTokenReceivedListener = { token ->
            viewModel.handleOAuthCallback(
                channelType = ChannelType.FACEBOOK,
                authCode = token,
                redirectUri = ChannelRepository.FACEBOOK_SDK_REDIRECT,
            )
        }
        dialog.onAuthErrorListener = { error ->
            if (isAdded) {
                if (error.startsWith(FacebookAuthDialogFragment.USER_CANCELLED)) {
                    // Người dùng bấm "Hủy" trên trang Facebook -> hỏi có muốn đăng nhập tài khoản khác không.
                    showFacebookCancelledDialog()
                } else {
                    val message = error.ifBlank { getString(R.string.channel_facebook_login_error) }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show(parentFragmentManager, FacebookAuthDialogFragment.TAG)
    }

    private fun loginWithAnotherFacebookAccount() {
        loginWithFacebook()
    }

    private fun showFacebookCancelledDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.channel_facebook_cancel_title)
            .setMessage(R.string.channel_facebook_cancel_message)
            .setPositiveButton(R.string.channel_facebook_login_another) { _, _ ->
                loginWithAnotherFacebookAccount()
            }
            .setNegativeButton(R.string.channel_close, null)
            .show()
    }

    private fun setupRecyclerView() {
        adapter = ChannelAdapter(
            getScopeForAccount = { account ->
                viewModel.getScopeForAccount(account)
            },
        )
        binding.rvChannels.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChannels.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnConnectFacebook.setOnClickListener {
            if (viewModel.isFacebookDemoMode()) {
                showDemoModeNotice("FACEBOOK_APP_ID")
                val facebookRedirectUri = "socialagent://oauth/facebook/callback"
                viewModel.connectFacebookDemo(facebookRedirectUri)
            } else {
                loginWithFacebook()
            }
        }

        binding.btnConnectLinkedIn.setOnClickListener {
            val redirectUri = LinkedInAuthDialogFragment.REDIRECT_URI
            if (viewModel.isLinkedInDemoMode()) {
                // Chưa cấu hình LINKEDIN_CLIENT_ID -> không có trang đăng nhập thật, báo rõ cho người dùng.
                showDemoModeNotice("LINKEDIN_CLIENT_ID")
                viewModel.connectLinkedInDemo(redirectUri)
            } else {
                val dialog = LinkedInAuthDialogFragment.newInstance()
                dialog.onAuthCodeReceivedListener = { code ->
                    viewModel.handleOAuthCallback(
                        channelType = ChannelType.LINKEDIN,
                        authCode = code,
                        redirectUri = LinkedInAuthDialogFragment.REDIRECT_URI,
                    )
                }
                dialog.onAuthErrorListener = { error ->
                    // Lỗi/hủy đăng nhập: chỉ báo lỗi, KHÔNG tự kết nối tài khoản demo.
                    if (isAdded) {
                        val message = if (error.startsWith("user_cancelled")) {
                            getString(R.string.channel_linkedin_cancelled)
                        } else {
                            getString(R.string.channel_linkedin_login_failed, error)
                        }
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
                dialog.show(parentFragmentManager, LinkedInAuthDialogFragment.TAG)
            }
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.pagePicker.collect { picker ->
                        picker?.let { showFacebookPagePicker(it.pages) }
                    }
                }
                launch {
                    viewModel.notices.collect { notice ->
                        when (notice) {
                            ChannelNotice.NO_FACEBOOK_PAGES -> Toast.makeText(
                                requireContext(),
                                R.string.channel_facebook_no_pages,
                                Toast.LENGTH_LONG,
                            ).show()
                            ChannelNotice.FACEBOOK_PAGES_LOAD_FAILED -> Toast.makeText(
                                requireContext(),
                                R.string.channel_facebook_pages_load_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                viewModel.uiState.collect { state ->
                    when (state) {
                        is ChannelUiState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.tvEmptyState.visibility = View.GONE
                        }
                        is ChannelUiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            // DiffUtil chỉ so sánh tài khoản; quyền/ma trận lấy từ ViewModel nên có thể đổi
                            // khi tài khoản không đổi (cấp lại quyền) -> buộc vẽ lại sau khi danh sách được áp dụng.
                            adapter.submitList(state.channels) {
                                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                            }
                            if (state.channels.isEmpty()) {
                                binding.tvEmptyState.visibility = View.VISIBLE
                            } else {
                                binding.tvEmptyState.visibility = View.GONE
                            }
                            state.connectedName?.let { name ->
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.channel_connected_named, name),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                        is ChannelUiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(
                                requireContext(),
                                state.message ?: getString(state.messageRes),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    /** Hộp thoại chọn Page; không mở trùng khi màn hình quay lại STARTED. */
    private fun showFacebookPagePicker(pages: List<FacebookPage>) {
        if (facebookPageDialog?.isShowing == true) return
        facebookPageDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.channel_facebook_pick_page_title)
            .setItems(pages.map { it.name }.toTypedArray()) { _, which ->
                viewModel.selectFacebookPage(pages[which])
            }
            .setNegativeButton(R.string.channel_close) { _, _ -> viewModel.cancelFacebookPagePicker() }
            .setOnCancelListener { viewModel.cancelFacebookPagePicker() }
            .show()
    }

    private fun showDemoModeNotice(configKey: String) {
        Toast.makeText(
            requireContext(),
            getString(R.string.channel_demo_mode_notice, configKey),
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // dismiss() không gọi onCancel nên lựa chọn Page vẫn được giữ trong ViewModel.
        facebookPageDialog?.dismiss()
        facebookPageDialog = null
        _binding = null
    }
}