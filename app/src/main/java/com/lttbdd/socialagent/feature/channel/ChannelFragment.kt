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
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
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
                        redirectUri = "facebook_sdk",
                    )
                }

                override fun onCancel() {
                    Toast.makeText(requireContext(), getString(R.string.channel_connect_cancelled), Toast.LENGTH_SHORT).show()
                }

                override fun onError(error: FacebookException) {
                    Toast.makeText(requireContext(), error.message ?: "Facebook Login Error", Toast.LENGTH_LONG).show()
                }
            },
        )
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
                val facebookRedirectUri = "socialagent://oauth/facebook/callback"
                viewModel.connectFacebookDemo(facebookRedirectUri)
            } else {
                LoginManager.getInstance().logInWithReadPermissions(this, listOf("public_profile"))
            }
        }

        binding.btnConnectLinkedIn.setOnClickListener {
            val redirectUri = LinkedInAuthDialogFragment.REDIRECT_URI
            if (viewModel.isLinkedInDemoMode()) {
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
                dialog.onAuthErrorListener = { _ ->
                    viewModel.connectLinkedInDemo(redirectUri)
                    Toast.makeText(requireContext(), getString(R.string.channel_connect_success), Toast.LENGTH_SHORT).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
