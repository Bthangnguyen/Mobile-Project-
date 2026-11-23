package com.lttbdd.socialagent.feature.channel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import com.lttbdd.socialagent.BuildConfig
import com.lttbdd.socialagent.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.facebook.AccessToken
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.databinding.FragmentChannelBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Fragment hiển thị và quản lý màn hình Kênh của tôi (F1).
 * Nguồn tham chiếu:
 * - RFC 8252 Section 8.1: không dùng WebView nhúng. Facebook đăng nhập bằng Meta SDK (LoginManager),
 * - LinkedIn mở trình duyệt hệ thống rồi nhận deep link từ BFF (xem ChannelViewModel.startLinkedInLogin).
 * - AGENTS.md: ViewModel expose StateFlow, Fragment collect bằng repeatOnLifecycle(STARTED), _binding nullable.
 */
@AndroidEntryPoint
class ChannelFragment : Fragment() {

    private var _binding: FragmentChannelBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChannelViewModel by viewModels()

    private var facebookPageDialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var adapter: ChannelAdapter
    private val facebookCallbackManager: CallbackManager = CallbackManager.Factory.create()
    private val facebookLoginManager = BuildConfig.FACEBOOK_APP_ID
        .takeIf { it.isNotBlank() && !it.contains("SOCIAL_AGENT") }
        ?.let { LoginManager.getInstance() }

    // Cấu hình App ID cố định trong APK. Khi có cấu hình, đăng ký ở mỗi lần tạo Fragment
    // để nhận lại kết quả đăng nhập qua CallbackManager sau khi Activity được tạo lại.
    private val facebookLoginLauncher = facebookLoginManager?.let { manager ->
        registerForActivityResult(manager.createLogInActivityResultContract(facebookCallbackManager)) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        facebookLoginManager?.registerCallback(
            facebookCallbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    viewModel.onFacebookLoggedIn(result.accessToken.toUserToken())
                }

                override fun onCancel() = viewModel.onFacebookLoginCancelled()

                override fun onError(error: FacebookException) = viewModel.onFacebookLoginFailed()
            },
        )
    }

    override fun onDestroy() {
        // Gỡ callback khỏi singleton LoginManager để Fragment cũ không bị giữ lại.
        facebookLoginManager?.unregisterCallback(facebookCallbackManager)
        super.onDestroy()
    }

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

    private fun AccessToken.toUserToken() = FacebookUserToken(
        accessToken = token,
        expiresAtMillis = expires.time,
        grantedScope = permissions.filterNotNull().sorted().joinToString(",").ifEmpty { null },
    )

    private fun loginWithAnotherFacebookAccount() {
        // Đăng xuất phiên SDK để màn Facebook Login không tự dùng lại tài khoản cũ.
        facebookLoginManager?.logOut()
        facebookLoginLauncher?.launch(FACEBOOK_PERMISSIONS)
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.channel_linkedin_no_browser, Toast.LENGTH_LONG).show()
        }
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
            getTasksForAccount = { account -> viewModel.getTasksForAccount(account) },
            isFacebookProfile = viewModel::isFacebookProfile,
        )
        binding.rvChannels.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChannels.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnRetryLoadChannels.setOnClickListener { viewModel.retryLoadChannels() }
        binding.btnConnectFacebook.setOnClickListener {
            if (facebookLoginLauncher == null) {
                showDemoModeNotice("FACEBOOK_APP_ID")
                viewModel.connectFacebookDemo(getString(R.string.channel_demo_facebook_name))
            } else {
                facebookLoginLauncher.launch(FACEBOOK_PERMISSIONS)
            }
        }

        binding.btnConnectLinkedIn.setOnClickListener {
            if (viewModel.isLinkedInDemoMode()) {
                // Chưa cấu hình LINKEDIN_MEMBER_CLIENT_ID / LINKEDIN_BFF_URL -> không có đăng nhập thật, báo rõ cho người dùng.
                showDemoModeNotice("LINKEDIN_MEMBER_CLIENT_ID, LINKEDIN_BFF_URL")
                viewModel.connectLinkedInDemo(getString(R.string.channel_demo_linkedin_name))
            } else {
                viewModel.startLinkedInLogin()
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
                    viewModel.connectedEvents.collect { name ->
                        Toast.makeText(requireContext(), getString(R.string.channel_connected_named, name), Toast.LENGTH_SHORT).show()
                    }
                }
                launch {
                    viewModel.errors.collect { res -> Toast.makeText(requireContext(), res, Toast.LENGTH_LONG).show() }
                }
                launch {
                    viewModel.browserRequests.collect { url -> openInBrowser(url) }
                }
                launch {
                    viewModel.notices.collect { notice ->
                        when (notice) {
                            ChannelNotice.FACEBOOK_LOGIN_CANCELLED -> showFacebookCancelledDialog()
                            else -> Toast.makeText(requireContext(), noticeMessage(notice), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(state: ChannelUiState) {
        val loadFailed = state == ChannelUiState.LoadFailed
        binding.progressBar.visibility = if (state == ChannelUiState.Loading) View.VISIBLE else View.GONE
        binding.btnRetryLoadChannels.visibility = if (loadFailed) View.VISIBLE else View.GONE
        binding.btnConnectFacebook.isEnabled = state is ChannelUiState.Success
        binding.btnConnectLinkedIn.isEnabled = state is ChannelUiState.Success
        binding.tvEmptyState.setText(if (loadFailed) R.string.channel_error_load_failed else R.string.channel_empty_desc)
        binding.tvEmptyState.visibility = if (loadFailed || (state is ChannelUiState.Success && state.channels.isEmpty())) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (state is ChannelUiState.Success) {
            // Quyền và Page task có thể đổi khi bản ghi tài khoản vẫn giống nhau.
            adapter.submitList(state.channels) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
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

    private fun noticeMessage(notice: ChannelNotice): Int = when (notice) {
        ChannelNotice.NO_FACEBOOK_PAGES -> R.string.channel_facebook_no_pages
        ChannelNotice.FACEBOOK_PAGES_LOAD_FAILED -> R.string.channel_facebook_pages_load_failed
        ChannelNotice.FACEBOOK_LOGIN_CANCELLED, ChannelNotice.FACEBOOK_LOGIN_FAILED ->
            R.string.channel_facebook_login_error
        ChannelNotice.LINKEDIN_LOGIN_CANCELLED -> R.string.channel_linkedin_cancelled
        ChannelNotice.LINKEDIN_LOGIN_FAILED -> R.string.channel_linkedin_login_failed
        ChannelNotice.LINKEDIN_LOGIN_TIMEOUT -> R.string.channel_linkedin_login_timeout
        ChannelNotice.LINKEDIN_SESSION_EXPIRED -> R.string.channel_linkedin_session_expired
    }

    /** Bản debug: báo đang ở chế độ demo (kênh giả chỉ trong RAM). Bản khác: chỉ báo chưa cấu hình, không kết nối gì. */
    private fun showDemoModeNotice(configKey: String) {
        val message = if (viewModel.isDemoEnabled()) R.string.channel_demo_mode_notice else R.string.channel_not_configured_notice
        Toast.makeText(requireContext(), getString(message, configKey), Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // dismiss() không gọi onCancel nên lựa chọn Page vẫn được giữ trong ViewModel.
        facebookPageDialog?.dismiss()
        facebookPageDialog = null
        _binding = null
    }

    private companion object {
        /** Quyền xin khi đăng nhập Facebook; quyền thật được cấp nằm trong AccessToken.permissions. */
        val FACEBOOK_PERMISSIONS = listOf(
            "public_profile",
            "pages_show_list",
            "pages_read_engagement",
            "pages_read_user_content",
            "pages_manage_posts",
            "pages_manage_engagement",
            "read_insights",
        )
    }
}
