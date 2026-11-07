package com.lttbdd.socialagent.feature.channel

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import com.lttbdd.socialagent.R

/**
 * DialogFragment hiển thị WebView đăng nhập OAuth Facebook nội bộ app.
 * Dừng hoàn toàn việc xóa cookie mỗi lần mở form để bảo vệ cookie thiết bị (datr, sb),
 * loại bỏ vòng lặp JS auto-click gây nghi ngờ bot và hỗ trợ tự động xác nhận 1 lần nhẹ nhàng sau 1.5s.
 */
class FacebookAuthDialogFragment : DialogFragment() {

    var onAuthTokenReceivedListener: ((token: String) -> Unit)? = null
    var onAuthErrorListener: ((error: String) -> Unit)? = null

    private var webView: WebView? = null
    private var loadingOverlay: View? = null
    private var isHandled = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var fallbackRunnable: Runnable? = null

    inner class FacebookAuthInterface {
        @android.webkit.JavascriptInterface
        fun onUrlChanged(url: String) {
            activity?.runOnUiThread {
                if (url.isNotBlank()) {
                    handleUrlRedirect(url, url.toUri())
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.dialog_facebook_auth, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webView = view.findViewById(R.id.webView)
        loadingOverlay = view.findViewById(R.id.loadingOverlay)

        val appId = com.lttbdd.socialagent.BuildConfig.FACEBOOK_APP_ID.trim()
        val redirectUri = "https://www.facebook.com/connect/login_success.html"
        val scope = "public_profile,pages_show_list,pages_read_engagement,pages_manage_posts,pages_manage_engagement,read_insights"
        val state = java.util.UUID.randomUUID().toString()

        val authUrl = Uri.parse("https://m.facebook.com/v20.0/dialog/oauth")
            .buildUpon()
            .appendQueryParameter("client_id", appId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "token,code")
            .appendQueryParameter("scope", scope)
            .appendQueryParameter("state", state)
            .build()
            .toString()

        // Bỏ hoàn toàn việc xóa cookie mỗi lần bấm nút Connect để bảo vệ cookie thiết bị (datr, sb)
        setupWebViewAndLoad(authUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewAndLoad(authUrl: String) {
        val targetWebView = webView ?: return

        targetWebView.clearCache(true)
        targetWebView.clearHistory()
        targetWebView.clearFormData()

        val defaultUserAgent = targetWebView.settings.userAgentString ?: ""
        val cleanUserAgent = if (defaultUserAgent.isNotBlank()) {
            defaultUserAgent.replace("; wv", "").replace("Version/4.0 ", "")
        } else {
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        }

        targetWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = cleanUserAgent
        }

        targetWebView.addJavascriptInterface(FacebookAuthInterface(), "Android")

        targetWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                val currentUrl = view?.url.orEmpty()
                updateVisualState(currentUrl)
            }
        }

        targetWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrEmpty()) {
                    if (handleUrlRedirect(url, url.toUri())) {
                        view?.stopLoading()
                        return
                    }
                    updateVisualState(url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrEmpty()) {
                    checkIfFacebookBlocked(view)
                    if (handleUrlRedirect(url, url.toUri())) {
                        view?.stopLoading()
                        return
                    }
                    updateVisualState(url)

                    val lowerUrl = url.lowercase()
                    if (lowerUrl.contains("/dialog/oauth")) {
                        runSafeOAuthConfirmScript(view)
                    }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!url.isNullOrEmpty()) {
                    if (handleUrlRedirect(url, url.toUri())) {
                        view?.stopLoading()
                        return
                    }
                    updateVisualState(url)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                val urlString = request?.url?.toString().orEmpty()
                val description = error?.description?.toString().orEmpty()

                if (isCustomSchemeRedirect(urlString) ||
                    urlString.contains("facebook.com") ||
                    description.contains("ERR_ABORTED") ||
                    description.contains("ERR_FAILED") ||
                    isHandled
                ) {
                    return
                }

                if (handleUrlRedirect(urlString, request?.url ?: Uri.EMPTY)) {
                    return
                }

                if (request?.isForMainFrame == true && !isHandled) {
                    val errorMsg = error?.description?.toString() ?: "WebView Error"
                    isHandled = true
                    onAuthErrorListener?.invoke(errorMsg)
                    dismissAllowingStateLoss()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val urlString = request.url.toString()
                if (isCustomSchemeRedirect(urlString)) {
                    handleUrlRedirect(urlString, request.url)
                    return true
                }
                return handleUrlRedirect(urlString, request.url)
            }

            @Deprecated("Deprecated in API 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (isCustomSchemeRedirect(url)) {
                    handleUrlRedirect(url, url.toUri())
                    return true
                }
                return handleUrlRedirect(url, url.toUri())
            }
        }

        val extraHeaders = HashMap<String, String>()
        extraHeaders["X-Requested-With"] = ""
        targetWebView.loadUrl(authUrl, extraHeaders)
    }

    private fun checkIfFacebookBlocked(view: WebView?) {
        if (isHandled) return
        val title = view?.title.orEmpty().lowercase()
        if (title.contains("bị chặn") || title.contains("blocked") || title.contains("temporarily")) {
            isHandled = true
            cancelSafetyFallbackTimeout()
            onAuthErrorListener?.invoke("Tài khoản bị Facebook tạm khóa do thao tác quá nhanh. Vui lòng chờ ít phút rồi thử lại.")
            dismissAllowingStateLoss()
        }
    }

    private fun updateVisualState(url: String) {
        if (isHandled || url.isBlank()) return

        val lowerUrl = url.lowercase()

        // Màn hình Save Login hoặc OAuth Confirm -> Bật overlay che giao diện tạm thời
        if (lowerUrl.contains("/login/save-device") || lowerUrl.contains("save-device")) {
            activity?.runOnUiThread {
                loadingOverlay?.visibility = View.VISIBLE
                startSafetyFallbackTimeout()
            }
            return
        }

        if (lowerUrl.contains("/dialog/oauth") || lowerUrl.contains("oauth/read") || lowerUrl.contains("oauth/confirm")) {
            val cookies = CookieManager.getInstance().getCookie("https://m.facebook.com").orEmpty()
            if (cookies.contains("c_user=")) {
                activity?.runOnUiThread {
                    loadingOverlay?.visibility = View.VISIBLE
                    startSafetyFallbackTimeout()
                }
            } else {
                activity?.runOnUiThread {
                    loadingOverlay?.visibility = View.GONE
                }
            }
            return
        }

        // Màn hình login/checkpoint công khai -> Hiện WebView để người dùng thao tác
        if (lowerUrl.contains("/login/") || lowerUrl.contains("/checkpoint/") || lowerUrl.contains("login.php")) {
            cancelSafetyFallbackTimeout()
            activity?.runOnUiThread {
                loadingOverlay?.visibility = View.GONE
            }
        }
    }

    private fun startSafetyFallbackTimeout() {
        fallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!isHandled && isAdded) {
                loadingOverlay?.visibility = View.GONE
            }
        }
        fallbackRunnable = runnable
        mainHandler.postDelayed(runnable, 2000L)
    }

    private fun cancelSafetyFallbackTimeout() {
        fallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        fallbackRunnable = null
    }

    private fun runSafeOAuthConfirmScript(view: WebView?) {
        if (isHandled) return
        val js = """
            (function() {
                try {
                    if (window.Android && window.location && window.location.href) {
                        window.Android.onUrlChanged(window.location.href);
                    }
                } catch(e) {}

                if (window.__fbConfirmClickedThisPage) return;

                setTimeout(function() {
                    if (window.__fbConfirmClickedThisPage) return;

                    try {
                        if (window.Android && window.location && window.location.href) {
                            window.Android.onUrlChanged(window.location.href);
                        }

                        var confirmBtn = document.querySelector('button[name="__CONFIRM__"]') ||
                                         document.querySelector('input[name="__CONFIRM__"]');

                        if (confirmBtn) {
                            window.__fbConfirmClickedThisPage = true;
                            confirmBtn.click();
                        }
                    } catch(e) {}
                }, 1500);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun isCustomSchemeRedirect(url: String): Boolean {
        val appId = com.lttbdd.socialagent.BuildConfig.FACEBOOK_APP_ID.trim()
        return url.startsWith("https://www.facebook.com/connect/login_success.html") ||
                url.startsWith("fbconnect://success") ||
                url.startsWith("com.lttbdd.socialagent://oauth") ||
                url.startsWith("socialagent://oauth") ||
                (appId.isNotBlank() && url.startsWith("fb$appId://authorize"))
    }

    private fun handleUrlRedirect(url: String, uri: Uri): Boolean {
        if (isHandled || url.isBlank()) return false

        // 1. Kiểm tra access_token hoặc code ở login_success.html hoặc redirect_uri
        val token = findAccessToken(url, uri)
        if (!token.isNullOrEmpty()) {
            isHandled = true
            cancelSafetyFallbackTimeout()
            onAuthTokenReceivedListener?.invoke(token)
            dismissAllowingStateLoss()
            return true
        }

        // 2. Nếu có lỗi từ OAuth -> Đóng Dialog
        val error = findError(url, uri)
        if (!error.isNullOrEmpty()) {
            isHandled = true
            cancelSafetyFallbackTimeout()
            onAuthErrorListener?.invoke(error)
            dismissAllowingStateLoss()
            return true
        }

        // 3. Nếu chạm vào Redirect URI chính thức mà chưa parse được token -> Đóng Dialog
        if (isCustomSchemeRedirect(url) && (url.contains("login_success.html") || url.contains("authorize"))) {
            isHandled = true
            cancelSafetyFallbackTimeout()
            onAuthErrorListener?.invoke("user_cancelled")
            dismissAllowingStateLoss()
            return true
        }

        return false
    }

    private fun findAccessToken(url: String, uri: Uri): String? {
        uri.getQueryParameter("access_token")?.takeIf { it.isNotBlank() }?.let { return it }
        uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }?.let { return it }

        val uriFragment = uri.fragment.orEmpty()
        if (uriFragment.isNotBlank()) {
            extractParamFromFragmentString(uriFragment, "access_token")?.takeIf { it.isNotBlank() }?.let { return it }
            extractParamFromFragmentString(uriFragment, "code")?.takeIf { it.isNotBlank() }?.let { return it }
        }

        extractParamFromUrlFragment(url, "access_token")?.takeIf { it.isNotBlank() }?.let { return it }
        extractParamFromUrlFragment(url, "code")?.takeIf { it.isNotBlank() }?.let { return it }

        val tokenRegex = Regex("(?:access_token|code)=([^&\\s#]+)")
        val match = tokenRegex.find(url)
        if (match != null && match.groupValues.size > 1) {
            val rawValue = match.groupValues[1]
            return try {
                java.net.URLDecoder.decode(rawValue, "UTF-8")
            } catch (_: Exception) {
                rawValue
            }
        }
        return null
    }

    private fun findError(url: String, uri: Uri): String? {
        uri.getQueryParameter("error")?.takeIf { it.isNotBlank() }?.let { return it }

        val uriFragment = uri.fragment.orEmpty()
        if (uriFragment.isNotBlank()) {
            extractParamFromFragmentString(uriFragment, "error")?.takeIf { it.isNotBlank() }?.let { return it }
        }

        extractParamFromUrlFragment(url, "error")?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun extractParamFromUrlFragment(url: String, paramName: String): String? {
        val fragmentIndex = url.indexOf('#')
        if (fragmentIndex == -1) return null
        val fragment = url.substring(fragmentIndex + 1)
        return extractParamFromFragmentString(fragment, paramName)
    }

    private fun extractParamFromFragmentString(fragment: String, paramName: String): String? {
        val params = fragment.split('&')
        for (param in params) {
            val keyValue = param.split('=')
            if (keyValue.size == 2 && keyValue[0] == paramName) {
                return try {
                    java.net.URLDecoder.decode(keyValue[1], "UTF-8")
                } catch (_: Exception) {
                    keyValue[1]
                }
            }
        }
        return null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelSafetyFallbackTimeout()
        webView = null
        loadingOverlay = null
    }

    companion object {
        const val TAG = "FacebookAuthDialogFragment"

        fun newInstance(): FacebookAuthDialogFragment {
            return FacebookAuthDialogFragment()
        }
    }
}
