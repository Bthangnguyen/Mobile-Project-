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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.DialogFragment
import com.lttbdd.socialagent.R

/**
 * DialogFragment hiển thị WebView đăng nhập OAuth Facebook nội bộ app.
 * Không xóa cookie mỗi lần mở form để bảo vệ cookie thiết bị (datr, sb).
 * Người dùng tự xem và bấm xác nhận các quyền trên trang của Facebook: không có JS tự bấm hộ,
 * không có cầu nối JS (`addJavascriptInterface`) cho trang web bên ngoài.
 * URL redirect được phân tích bởi [FacebookAuthRedirect] (kiểm tra `state`, đúng redirect URI).
 */
class FacebookAuthDialogFragment : DialogFragment() {

    var onAuthTokenReceivedListener: ((token: String) -> Unit)? = null
    var onAuthErrorListener: ((error: String) -> Unit)? = null

    private var webView: WebView? = null
    private var loadingOverlay: View? = null
    private var isHandled = false

    /** Giá trị `state` đã gửi trong URL OAuth; redirect trả về phải khớp mới nhận token. */
    private var expectedState: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private var fallbackRunnable: Runnable? = null

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

        val scope = "public_profile,pages_show_list,pages_read_engagement,pages_manage_posts,pages_manage_engagement,read_insights"
        expectedState = java.util.UUID.randomUUID().toString()

        // response_type=token: app chỉ dùng access token, không dùng `code` (code không phải token).
        val authUrl = Uri.parse("https://m.facebook.com/v20.0/dialog/oauth")
            .buildUpon()
            .appendQueryParameter("client_id", facebookAppId())
            .appendQueryParameter("redirect_uri", FacebookAuthRedirect.REDIRECT_URI)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("scope", scope)
            .appendQueryParameter("state", expectedState)
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
                    if (handleUrlRedirect(url)) {
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
                    if (handleUrlRedirect(url)) {
                        view?.stopLoading()
                        return
                    }
                    updateVisualState(url)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!url.isNullOrEmpty()) {
                    if (handleUrlRedirect(url)) {
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

                if (handleUrlRedirect(urlString)) {
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
                    handleUrlRedirect(urlString)
                    return true
                }
                return handleUrlRedirect(urlString)
            }

            @Deprecated("Deprecated in API 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (isCustomSchemeRedirect(url)) {
                    handleUrlRedirect(url)
                    return true
                }
                return handleUrlRedirect(url)
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
            onAuthErrorListener?.invoke(stringOrEmpty(R.string.channel_facebook_blocked))
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

        // Trang cấp quyền: luôn hiện để người dùng tự xem các quyền và bấm xác nhận.
        if (lowerUrl.contains("/dialog/oauth") || lowerUrl.contains("oauth/read") || lowerUrl.contains("oauth/confirm")) {
            cancelSafetyFallbackTimeout()
            activity?.runOnUiThread {
                loadingOverlay?.visibility = View.GONE
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

    private fun facebookAppId(): String = com.lttbdd.socialagent.BuildConfig.FACEBOOK_APP_ID.trim()

    private fun stringOrEmpty(resId: Int): String = if (isAdded) getString(resId) else ""

    private fun isCustomSchemeRedirect(url: String): Boolean =
        FacebookAuthRedirect.isRedirect(url, facebookAppId())

    /**
     * Xử lý URL mà WebView đang mở. Chỉ phản ứng khi URL đúng là redirect của luồng đăng nhập;
     * token chỉ được nhận khi `state` khớp. Trả về true nếu đã xử lý xong và đóng hộp thoại.
     */
    private fun handleUrlRedirect(url: String): Boolean {
        if (isHandled || url.isBlank()) return false

        val result = FacebookAuthRedirect.parse(url, expectedState, facebookAppId()) ?: return false

        isHandled = true
        cancelSafetyFallbackTimeout()
        when (result) {
            is FacebookAuthRedirect.Result.Token -> onAuthTokenReceivedListener?.invoke(result.accessToken)
            FacebookAuthRedirect.Result.Cancelled -> onAuthErrorListener?.invoke(USER_CANCELLED)
            is FacebookAuthRedirect.Result.Failure ->
                onAuthErrorListener?.invoke(stringOrEmpty(R.string.channel_facebook_login_error))
        }
        dismissAllowingStateLoss()
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelSafetyFallbackTimeout()
        webView = null
        loadingOverlay = null
    }

    companion object {
        const val TAG = "FacebookAuthDialogFragment"

        /** Giá trị báo lỗi mà ChannelFragment hiểu là người dùng hủy đăng nhập. */
        const val USER_CANCELLED = "user_cancelled"

        fun newInstance(): FacebookAuthDialogFragment {
            return FacebookAuthDialogFragment()
        }
    }
}
