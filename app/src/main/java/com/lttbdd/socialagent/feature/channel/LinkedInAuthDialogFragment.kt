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
import androidx.fragment.app.DialogFragment
import com.lttbdd.socialagent.R

/**
 * DialogFragment hiển thị WebView đăng nhập OAuth LinkedIn và tự động đóng khi nhận được callback code.
 * Việc nhận diện redirect và kiểm tra `state` nằm ở [LinkedInAuthRedirect] (logic thuần, có unit test).
 */
class LinkedInAuthDialogFragment : DialogFragment() {

    var onAuthCodeReceivedListener: ((authCode: String) -> Unit)? = null
    var onAuthErrorListener: ((error: String) -> Unit)? = null

    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null

    /** `state` của phiên đăng nhập hiện tại; redirect chỉ được chấp nhận khi khớp giá trị này. */
    private var expectedState: String = ""

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
        val scope = SCOPE
        val state = java.util.UUID.randomUUID().toString()
        expectedState = state

        val authUrl = Uri.parse("https://www.linkedin.com/oauth/v2/authorization")
            .buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", scope)
            .build()
            .toString()

        // Không log authUrl/code/state: chúng chứa client_id và giá trị OAuth nhạy cảm (AGENTS.md quy tắc 5).

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
                return handleUrlRedirect(request.url.toString())
            }

            @Deprecated("Deprecated in API 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrlRedirect(url)
            }
        }

        val extraHeaders = HashMap<String, String>()
        extraHeaders["X-Requested-With"] = ""
        webView?.loadUrl(authUrl, extraHeaders)
    }

    /**
     * Trả `true` (đã xử lý, WebView không tải tiếp) khi [url] là redirect đích. Chỉ URL đúng tiền tố
     * [LinkedInAuthRedirect.REDIRECT_URI] mới được xét, và `code` chỉ được nhận khi `state` khớp.
     * Dùng `dismissAllowingStateLoss()` vì callback có thể đến sau khi trạng thái Fragment đã được lưu.
     */
    private fun handleUrlRedirect(url: String): Boolean {
        val result = LinkedInAuthRedirect.parse(url, expectedState) ?: return false
        when (result) {
            is LinkedInAuthRedirect.Result.Code -> onAuthCodeReceivedListener?.invoke(result.authCode)
            LinkedInAuthRedirect.Result.Cancelled -> onAuthErrorListener?.invoke(USER_CANCELLED)
            is LinkedInAuthRedirect.Result.Failure -> onAuthErrorListener?.invoke(result.reason.name)
        }
        dismissAllowingStateLoss()
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webView = null
        progressBar = null
    }

    companion object {
        const val REDIRECT_URI = LinkedInAuthRedirect.REDIRECT_URI

        /** Giá trị báo cho Fragment khi người dùng hủy đăng nhập (Fragment so khớp bằng `startsWith`). */
        const val USER_CANCELLED = "user_cancelled"

        /**
         * Quyền xin khi đăng nhập LinkedIn: hồ sơ (OpenID, đúng bộ quyền App Member trong tài liệu nghiên cứu) và đăng bài thay mặt thành viên (`w_member_social`).
         * Cần bật sản phẩm "Share on LinkedIn" trong LinkedIn Developer app, nếu không LinkedIn báo lỗi scope.
         */
        const val SCOPE = "openid profile w_member_social"
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
