package com.lttbdd.socialagent.feature.channel

import android.content.Intent
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
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginBehavior
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.lttbdd.socialagent.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lttbdd.socialagent.core.model.ChannelType
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
    private lateinit var adapter: ChannelAdapter
    private lateinit var callbackManager: CallbackManager

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

        setupFacebookLoginSdk()
        setupRecyclerView()
        setupListeners()
        observeUiState()
    }

    private fun setupFacebookLoginSdk() {
        callbackManager = CallbackManager.Factory.create()
        LoginManager.getInstance().registerCallback(
            callbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    val accessToken = result.accessToken.token
                    viewModel.handleOAuthCallback(
                        channelType = ChannelType.FACEBOOK,
                        authCode = accessToken,
                        redirectUri = ChannelRepository.FACEBOOK_SDK_REDIRECT,
                    )
                }

                override fun onCancel() {
                    if (!isAdded) return
                    // Người dùng bấm "Hủy" ở màn "Tiếp tục với tên ..." -> hỏi có muốn đăng nhập tài khoản khác không.
                    showFacebookCancelledDialog()
                }

                override fun onError(error: FacebookException) {
                    if (!isAdded) return
                    Toast.makeText(
                        requireContext(),
                        error.message ?: getString(R.string.channel_facebook_login_error),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }

    /**
     * Đăng nhập Facebook bình thường (ưu tiên app Facebook, fallback trình duyệt).
     */
    private fun loginWithFacebook() {
        LoginManager.getInstance()
            .setLoginBehavior(LoginBehavior.NATIVE_WITH_FALLBACK)
            .setAuthType(FACEBOOK_AUTH_TYPE_REREQUEST)
            .logInWithReadPermissions(this, FACEBOOK_PERMISSIONS)
    }

    /**
     * Đăng nhập bằng tài khoản Facebook khác: xóa token SDK đang lưu, bỏ qua app Facebook
     * (để không hiện lại "Tiếp tục với tên ...") và yêu cầu Facebook hiện lại trang đăng nhập
     * qua auth_type=reauthenticate trên trình duyệt.
     */
    private fun loginWithAnotherFacebookAccount() {
        LoginManager.getInstance().logOut()
        LoginManager.getInstance()
            .setLoginBehavior(LoginBehavior.WEB_ONLY)
            .setAuthType(FACEBOOK_AUTH_TYPE_REAUTHENTICATE)
            .logInWithReadPermissions(this, FACEBOOK_PERMISSIONS)
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

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        callbackManager.onActivityResult(requestCode, resultCode, data)
        super.onActivityResult(requestCode, resultCode, data)
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
                viewModel.uiState.collect { state ->
                    when (state) {
                        is ChannelUiState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.tvEmptyState.visibility = View.GONE
                        }
                        is ChannelUiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            adapter.submitList(state.channels)
                            if (state.channels.isEmpty()) {
                                binding.tvEmptyState.visibility = View.VISIBLE
                            } else {
                                binding.tvEmptyState.visibility = View.GONE
                            }
                            state.userMessage?.let { msg ->
                                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        is ChannelUiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
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
        _binding = null
    }

    companion object {
        private val FACEBOOK_PERMISSIONS = listOf("public_profile")
        private const val FACEBOOK_AUTH_TYPE_REREQUEST = "rerequest"
        private const val FACEBOOK_AUTH_TYPE_REAUTHENTICATE = "reauthenticate"
    }
}