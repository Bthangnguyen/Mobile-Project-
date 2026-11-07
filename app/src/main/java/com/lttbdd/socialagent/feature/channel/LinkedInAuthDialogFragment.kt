package com.lttbdd.socialagent.feature.channel

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import com.lttbdd.socialagent.R
import java.net.URLEncoder

/**
 * DialogFragment hiển thị WebView đăng nhập OAuth LinkedIn và tự động đóng khi nhận được callback code.
 */
class LinkedInAuthDialogFragment : DialogFragment() {

    var onAuthCodeReceivedListener: ((authCode: String) -> Unit)? = null
    var onAuthErrorListener: ((error: String) -> Unit)? = null

    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.dialog_linkedin_auth, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webView = view.findViewById(R.id.webView)
        progressBar = view.findViewById(R.id.progressBar)

        val clientId = com.lttbdd.socialagent.BuildConfig.LINKEDIN_CLIENT_ID.trim()
        val redirectUri = REDIRECT_URI
        val scope = "openid profile email"
        val state = java.util.UUID.randomUUID().toString()

        val authUrl = Uri.parse("https://www.linkedin.com/oauth/v2/authorization")
            .buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", scope)
            .build()
            .toString()

        android.util.Log.d("LinkedInOAuth", "Auth URL: $authUrl")

        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        cookieManager.setAcceptCookie(true)

        webView?.let {
            cookieManager.setAcceptThirdPartyCookies(it, true)
            it.clearCache(true)
            it.clearHistory()
            it.clearFormData()
        }
        android.webkit.WebStorage.getInstance().deleteAllData()

        val defaultUserAgent = webView?.settings?.userAgentString ?: ""
        val cleanUserAgent = if (defaultUserAgent.isNotBlank()) {
            defaultUserAgent.replace("; wv", "").replace("Version/4.0 ", "")
        } else {
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        }

        webView?.settings?.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = cleanUserAgent
        }

        webView?.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar?.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar?.visibility = View.GONE
                val title = view?.title ?: ""
                if (title.contains("Blocked", ignoreCase = true) || title.contains("Cloudflare", ignoreCase = true) || title.contains("unable to access", ignoreCase = true)) {
                    android.util.Log.e("LinkedInOAuth", "Cloudflare Blocked Page detected: $title")
                    onAuthErrorListener?.invoke("Cloudflare Blocked")
                    dismissAllowingStateLoss()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val errorMsg = error?.description?.toString() ?: "WebView Error"
                    android.util.Log.e("LinkedInOAuth", "onReceivedError: $errorMsg")
                    onAuthErrorListener?.invoke(errorMsg)
                    dismissAllowingStateLoss()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val urlString = request.url.toString()
                return handleUrlRedirect(urlString, request.url)
            }

            @Deprecated("Deprecated in API 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrlRedirect(url, url.toUri())
            }
        }

        val extraHeaders = HashMap<String, String>()
        extraHeaders["X-Requested-With"] = ""
        webView?.loadUrl(authUrl, extraHeaders)
    }

    private fun handleUrlRedirect(url: String, uri: Uri): Boolean {
        if (url.startsWith(REDIRECT_URI) || url.startsWith("com.lttbdd.socialagent://") || uri.getQueryParameter("code") != null) {
            val code = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")

            if (!code.isNullOrEmpty()) {
                android.util.Log.d("LinkedInOAuth", "Authorization Code received: $code")
                onAuthCodeReceivedListener?.invoke(code)
                dismiss()
                return true
            } else if (!error.isNullOrEmpty()) {
                android.util.Log.d("LinkedInOAuth", "OAuth Error: $error")
                onAuthErrorListener?.invoke(error)
                dismiss()
                return true
            }
        }
        return false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webView = null
        progressBar = null
    }

    companion object {
        const val REDIRECT_URI = "https://www.linkedin.com/developers/tools/oauth/redirect"
        const val TAG = "LinkedInAuthDialogFragment"

        fun clearLinkedInCookies() {
            try {
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                android.webkit.WebStorage.getInstance().deleteAllData()
            } catch (_: Exception) {}
        }

        fun newInstance(): LinkedInAuthDialogFragment {
            return LinkedInAuthDialogFragment()
        }
    }
}
