package com.lttbdd.socialagent.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.facebook.AccessToken
import com.facebook.AuthenticationToken
import com.facebook.AuthenticationTokenCache
import com.facebook.FacebookSdk
import com.facebook.login.LoginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

// Giá trị dành riêng trong journal, khác định dạng dấu vân tay access:authentication.
internal const val UNVERIFIED_FACEBOOK_SESSION = "unverified"

internal class UnverifiedFacebookSessionException :
    IllegalStateException("Không xác định được phiên Facebook cần đăng xuất.")

/**
 * Cách đọc cache và chờ ghi đăng xuất của Meta SDK 17.0.2.
 * Khi nâng SDK, đối chiếu định dạng cache và chạy FacebookSdkDisconnectAdapterTest.
 */
internal class FacebookSdkDisconnectAdapter {
    /**
     * Chụp phiên SDK cần đăng xuất, chỉ khi phiên đó thuộc [expectedUserId] (ID người cấp quyền
     * đã lưu cùng token Page bị ngắt).
     * - Không có phiên SDK nào: trả null, không còn gì để đăng xuất.
     * - Phiên SDK thuộc người dùng khác: trả null, tuyệt đối không đăng xuất phiên không liên quan.
     * - Không xác minh được chủ phiên (thiếu ID đã lưu, cache lỗi, chỉ có authentication token):
     *   ném [UnverifiedFacebookSessionException] để journal ghi trạng thái chưa xác minh.
     */
    suspend fun currentSessionKey(expectedUserId: String?): String? = withContext(Dispatchers.Main.immediate) {
        if (!FacebookSdk.isInitialized()) return@withContext null
        val cache = accessTokenCache(FacebookSdk.getApplicationContext())
        // Chụp dấu vân tay ngay, trước khi SDK có thể nạp cache hoặc người dùng đổi phiên.
        val current = AccessToken.getCurrentAccessToken()
        val cached = if (current == null) readCachedSession(cache) else null
        val access = current?.sessionFingerprint() ?: cached?.fingerprint
        val sdkUserId = current?.userId ?: cached?.userId
        val session = FacebookSession(access, authenticationFingerprint())
        if (session.access == null && session.authentication == null) return@withContext null
        if (expectedUserId.isNullOrBlank() || sdkUserId.isNullOrBlank()) throw UnverifiedFacebookSessionException()
        if (sdkUserId != expectedUserId) return@withContext null
        session.key
    }

    suspend fun signOut(expectedSession: String) {
        val context = withContext(Dispatchers.Main.immediate) {
            check(FacebookSdk.isInitialized())
            val sdkContext = FacebookSdk.getApplicationContext()
            if (expectedSession == UNVERIFIED_FACEBOOK_SESSION) {
                // Không có bằng chứng để gắn phiên còn tồn tại với thao tác ngắt kết nối cũ.
                if (AccessToken.getCurrentAccessToken() != null ||
                    AuthenticationToken.getCurrentAuthenticationToken() != null ||
                    accessTokenCache(sdkContext).contains(CACHED_ACCESS_TOKEN) ||
                    sdkContext.getSharedPreferences(AUTHENTICATION_TOKEN_CACHE, Context.MODE_PRIVATE)
                        .contains(AuthenticationTokenCache.CACHED_AUTHENTICATION_TOKEN_KEY)
                ) throw UnverifiedFacebookSessionException()

                // SDK đã sạch, vẫn chờ ghi cache xuống đĩa trước khi xác nhận journal.
                return@withContext sdkContext
            }
            val current = currentSession(sdkContext)
            // SDK và OAuth callback chạy trên Main. So sánh và logout liền nhau.
            // Nếu lần trước mới xóa một phần, chỉ tiếp tục khi phần còn lại thuộc phiên cũ.
            if (!current.canFinishSignOut(expectedSession)) return@withContext null
            LoginManager.getInstance().logOut()
            sdkContext
        } ?: return

        commitLogoutPreferences(context)
    }

    /**
     * Đăng xuất phiên Facebook SDK hiện tại mà không đối chiếu dấu vân tay. Chỉ gọi sau khi
     * người dùng tự xác nhận (phiên không xác minh được chủ sở hữu), không bao giờ gọi tự động.
     */
    suspend fun forceSignOut() {
        val context = withContext(Dispatchers.Main.immediate) {
            check(FacebookSdk.isInitialized())
            LoginManager.getInstance().logOut()
            FacebookSdk.getApplicationContext()
        }
        commitLogoutPreferences(context)
    }

    // Meta SDK 17 dùng SharedPreferences.apply(). Chờ ghi xuống đĩa trước khi xác nhận journal.
    private suspend fun commitLogoutPreferences(context: Context) {
        withContext(Dispatchers.IO) {
            for (name in FACEBOOK_LOGOUT_PREFERENCES) {
                check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit())
            }
        }
    }

    private suspend fun currentSession(context: Context): FacebookSession {
        // SDK có thể khởi tạo xong trước khi nạp token từ cache. Báo lỗi khi hết thời gian chờ
        // để repository vẫn dọn dữ liệu cục bộ và giữ journal cho lần thử lại.
        val cache = accessTokenCache(context)
        withTimeoutOrNull(CACHE_LOAD_TIMEOUT_MILLIS) {
            while (cache.contains(CACHED_ACCESS_TOKEN) && AccessToken.getCurrentAccessToken() == null) {
                delay(25L)
            }
        } ?: error("Chưa khôi phục được phiên Facebook SDK.")
        return FacebookSession(AccessToken.getCurrentAccessToken()?.sessionFingerprint(), authenticationFingerprint())
    }

    private fun accessTokenCache(context: Context) =
        context.getSharedPreferences(ACCESS_TOKEN_CACHE, Context.MODE_PRIVATE)

    private fun AccessToken.sessionFingerprint(): String =
        fingerprint("$applicationId:$userId:${lastRefresh.time}:$token")

    private class CachedSession(val userId: String, val fingerprint: String)

    private fun readCachedSession(cache: SharedPreferences): CachedSession? = try {
        cache.getString(CACHED_ACCESS_TOKEN, null)?.let(::parseCachedSession)
    } catch (_: ClassCastException) {
        throw UnverifiedFacebookSessionException()
    }

    private fun parseCachedSession(serialized: String): CachedSession = try {
        val cached = JSONObject(serialized)
        // Dùng cùng các trường với AccessToken để retry nhận ra đúng phiên sau khi SDK nạp cache.
        val applicationId = cached.getString("application_id")
        val userId = cached.getString("user_id")
        val lastRefresh = cached.getLong("last_refresh")
        val token = cached.getString("token")
        if (applicationId.isBlank() || userId.isBlank() || token.isBlank()) {
            throw UnverifiedFacebookSessionException()
        }
        CachedSession(userId, fingerprint("$applicationId:$userId:$lastRefresh:$token"))
    } catch (_: JSONException) {
        throw UnverifiedFacebookSessionException()
    }

    private fun authenticationFingerprint(): String? {
        val authentication = AuthenticationToken.getCurrentAuthenticationToken() ?: AuthenticationTokenCache().load()
        return authentication?.let { fingerprint(it.token) }
    }

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class FacebookSession(val access: String?, val authentication: String?) {
        val key: String get() = "${access.orEmpty()}:${authentication.orEmpty()}"

        fun canFinishSignOut(expected: String): Boolean =
            (access == null || access == expected.substringBefore(':')) &&
                (authentication == null || authentication == expected.substringAfter(':'))
    }

    private companion object {
        // Tên cache và tùy chọn đăng nhập trong Meta SDK 17.0.2.
        const val ACCESS_TOKEN_CACHE = "com.facebook.AccessTokenManager.SharedPreferences"
        const val CACHED_ACCESS_TOKEN = "com.facebook.AccessTokenManager.CachedAccessToken"
        const val CACHE_LOAD_TIMEOUT_MILLIS = 1_000L
        const val AUTHENTICATION_TOKEN_CACHE = "com.facebook.AuthenticationTokenManager.SharedPreferences"
        val FACEBOOK_LOGOUT_PREFERENCES = listOf(
            ACCESS_TOKEN_CACHE,
            AUTHENTICATION_TOKEN_CACHE,
            "com.facebook.loginManager",
        )
    }
}
