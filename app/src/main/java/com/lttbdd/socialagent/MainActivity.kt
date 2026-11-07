package com.lttbdd.socialagent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.lttbdd.socialagent.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint

import androidx.lifecycle.lifecycleScope
import com.lttbdd.socialagent.data.repository.ChannelRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Activity duy nhất của app: chứa NavHostFragment và bottom navigation 5 tab. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var channelRepository: ChannelRepository

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        binding.bottomNav.setupWithNavController(navHost.navController)

        handleOAuthDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthDeepLink(intent)
    }

    private fun handleOAuthDeepLink(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        val isFacebookScheme = uri.scheme?.startsWith("fb") == true
        val isCustomScheme = uri.scheme == "socialagent" || (uri.scheme == "com.lttbdd.socialagent" && uri.host == "oauth") || isFacebookScheme
        val isHttpsScheme = uri.scheme == "https" && uri.host == "socialagent.app"

        if (isCustomScheme || isHttpsScheme) {
            val error = uri.getQueryParameter("error")
            if (error == "access_denied") {
                Toast.makeText(this, getString(R.string.channel_connect_cancelled), Toast.LENGTH_LONG).show()
                return
            }

            val code = uri.getQueryParameter("code")
            if (!code.isNullOrEmpty()) {
                lifecycleScope.launch {
                    channelRepository.connectFacebookPage(
                        authCode = code,
                        codeVerifier = "verifier_deep_link",
                        redirectUri = uri.toString(),
                    )
                    Toast.makeText(this@MainActivity, getString(R.string.channel_connect_success), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
