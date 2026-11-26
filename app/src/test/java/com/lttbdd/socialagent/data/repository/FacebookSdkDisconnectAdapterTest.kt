package com.lttbdd.socialagent.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.facebook.AccessToken
import com.facebook.AccessTokenManager
import com.facebook.AuthenticationToken
import com.facebook.AuthenticationTokenManager
import com.facebook.FacebookSdk
import com.facebook.ProfileManager
import com.facebook.login.LoginManager
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.ChannelError
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.model.toChannelError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [36])
class FacebookSdkDisconnectAdapterTest {
    @Test
    fun disconnectClearsSdkSessionAndPersistsLogoutForTheNextLaunch() = runBlocking {
        assertEquals("Kiểm tra lại cách lưu cache khi nâng Meta SDK.", "17.0.2", FacebookSdk.getSdkVersion())
        val app = initializeSdk()

        AccessToken.setCurrentAccessToken(
            AccessToken("test-access-token", "123456789", "test-user", null, null, null, null, null, null, null),
        )
        AuthenticationToken.setCurrentAuthenticationToken(authenticationTokenFixture())
        persistPreferences(app, ACCESS_TOKEN_CACHE)
        persistPreferences(app, AUTHENTICATION_TOKEN_CACHE)
        assertTrue(reopenPreferences(app, ACCESS_TOKEN_CACHE).contains(CACHED_ACCESS_TOKEN))
        assertTrue(reopenPreferences(app, AUTHENTICATION_TOKEN_CACHE).contains(CACHED_AUTHENTICATION_TOKEN))
        assertNotNull(AuthenticationToken.getCurrentAuthenticationToken())

        val adapter = FacebookSdkDisconnectAdapter()
        adapter.signOut(checkNotNull(adapter.currentSessionKey("test-user")))

        assertNull(AccessToken.getCurrentAccessToken())
        assertNull(AuthenticationToken.getCurrentAuthenticationToken())
        assertNull(adapter.currentSessionKey("test-user"))
        // Đọc lại bằng các đối tượng SharedPreferences mới, giống phần đọc cache khi mở app lại.
        assertFalse(reopenPreferences(app, ACCESS_TOKEN_CACHE).contains(CACHED_ACCESS_TOKEN))
        assertFalse(reopenPreferences(app, AUTHENTICATION_TOKEN_CACHE).contains(CACHED_AUTHENTICATION_TOKEN))
        assertFalse(reopenPreferences(app, LOGIN_MANAGER_PREFERENCES).getBoolean("express_login_allowed", true))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun malformedSdkCacheStillAllowsLocalDisconnectWithoutGuessingTheSdkSession() = runTest {
        initializeSdk()
        assertNull(AccessToken.getCurrentAccessToken())
        val cache = FacebookSdk.getApplicationContext().getSharedPreferences(ACCESS_TOKEN_CACHE, Context.MODE_PRIVATE)
        assertTrue(cache.edit().putString(CACHED_ACCESS_TOKEN, "not a readable token").commit())
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val adapter = FacebookSdkDisconnectAdapter()
            val tokenStore = ChannelRepositoryTest.FakeTokenStore()
            val accounts = ChannelRepositoryTest.FakeAccountStore()
            var cleaned = false
            val repository = ChannelRepositoryImpl(tokenStore, accounts, object : ChannelDataCleanup {
                override suspend fun clear(type: ChannelType, operationId: String) {
                    cleaned = true
                }

                override suspend fun currentFacebookSession(expectedUserId: String?) =
                    adapter.currentSessionKey(expectedUserId)

                override suspend fun signOutFacebook(expectedSession: String) = adapter.signOut(expectedSession)
            })
            repository.connectFacebookSelectedPage(
                FacebookUserToken("test-user-token", Long.MAX_VALUE, null, "test-user"),
                FacebookPage("a", "Page A", "test-page-token"),
            ).getOrThrow()
            val result = repository.disconnectChannel(ChannelType.FACEBOOK, "a")

            assertEquals(ChannelError.FACEBOOK_SIGN_OUT_UNVERIFIED, result.exceptionOrNull()?.toChannelError())
            assertTrue(cleaned)
            assertTrue(accounts.saved.isEmpty())
            assertTrue(tokenStore.storage.isEmpty())
            assertTrue(repository.hasPendingDisconnectCleanup.value)
            assertEquals(UNVERIFIED_FACEBOOK_SESSION, tokenStore.loadPendingWrites().single().facebookSessionToSignOut)
            assertTrue(cache.contains(CACHED_ACCESS_TOKEN))

            val restarted = ChannelRepositoryImpl(tokenStore, accounts, object : ChannelDataCleanup {
                override suspend fun clear(type: ChannelType, operationId: String) = Unit
                override suspend fun signOutFacebook(expectedSession: String) = adapter.signOut(expectedSession)
            })
            assertFalse(restarted.retryPendingDisconnectCleanup())
            val newToken = accessToken("new-session")
            AccessToken.setCurrentAccessToken(newToken)
            assertFalse(restarted.retryPendingDisconnectCleanup())
            assertEquals(newToken, AccessToken.getCurrentAccessToken())

            AccessToken.setCurrentAccessToken(null)
            assertTrue(restarted.retryPendingDisconnectCleanup())
            assertTrue(tokenStore.loadPendingWrites().isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun firstDisconnectCapturesCachedSessionAndRetriesAfterSdkLoadsIt() = runTest {
        initializeSdk()
        val oldToken = cacheSessionWithoutLoadingIt()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val adapter = FacebookSdkDisconnectAdapter()
            val tokenStore = ChannelRepositoryTest.FakeTokenStore()
            val accounts = ChannelRepositoryTest.FakeAccountStore()
            val cleanedChannels = mutableListOf<ChannelType>()
            val repository = ChannelRepositoryImpl(tokenStore, accounts, object : ChannelDataCleanup {
                override suspend fun clear(type: ChannelType, operationId: String) {
                    cleanedChannels.add(type)
                }

                override suspend fun currentFacebookSession(expectedUserId: String?) =
                    adapter.currentSessionKey(expectedUserId)

                override suspend fun signOutFacebook(expectedSession: String) = adapter.signOut(expectedSession)
            })
            repository.connectFacebookSelectedPage(
                FacebookUserToken("test-user-token", Long.MAX_VALUE, null, "test-user"),
                FacebookPage("a", "Page A", "test-page-token"),
            ).getOrThrow()

            val result = repository.disconnectChannel(ChannelType.FACEBOOK, "a")

            assertEquals(ChannelError.FACEBOOK_SIGN_OUT_FAILED, result.exceptionOrNull()?.toChannelError())
            val pendingSession = tokenStore.loadPendingWrites().single().facebookSessionToSignOut
            assertNotNull(pendingSession)
            assertTrue(repository.hasPendingDisconnectCleanup.value)
            assertTrue(accounts.saved.isEmpty())
            assertTrue(tokenStore.storage.isEmpty())
            assertEquals(listOf(ChannelType.FACEBOOK), cleanedChannels)

            AccessToken.setCurrentAccessToken(oldToken)
            assertEquals(pendingSession, adapter.currentSessionKey("test-user"))
            assertTrue(repository.retryPendingDisconnectCleanup())
            assertTrue(tokenStore.loadPendingWrites().isEmpty())

            assertNull(adapter.currentSessionKey("test-user"))
            assertNull(AccessToken.getCurrentAccessToken())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pendingDisconnectPreservesNewLoginForTheSameFacebookUser() = runBlocking {
        val app = initializeSdk()
        cacheSessionWithoutLoadingIt()
        val adapter = FacebookSdkDisconnectAdapter()
        val oldSession = checkNotNull(adapter.currentSessionKey("test-user"))
        val newToken = accessToken("new-test-token")
        AccessToken.setCurrentAccessToken(newToken)
        persistPreferences(app, ACCESS_TOKEN_CACHE)

        adapter.signOut(oldSession)

        assertEquals(newToken, AccessToken.getCurrentAccessToken())
        assertTrue(reopenPreferences(app, ACCESS_TOKEN_CACHE).contains(CACHED_ACCESS_TOKEN))
    }

    @Test
    fun sdkSessionOfAnotherFacebookUserIsNeverSignedOut() = runBlocking {
        val app = initializeSdk()
        val other = AccessToken("other-token", "123456789", "other-user", null, null, null, null, null, null, null)
        AccessToken.setCurrentAccessToken(other)
        persistPreferences(app, ACCESS_TOKEN_CACHE)
        val adapter = FacebookSdkDisconnectAdapter()

        // Page bị ngắt thuộc test-user, còn SDK đang đăng nhập other-user.
        assertNull(adapter.currentSessionKey("test-user"))

        assertEquals(other, AccessToken.getCurrentAccessToken())
        assertTrue(reopenPreferences(app, ACCESS_TOKEN_CACHE).contains(CACHED_ACCESS_TOKEN))
    }

    @Test
    fun cachedSdkSessionOfAnotherFacebookUserIsNeverSignedOut() = runBlocking {
        initializeSdk()
        cacheSessionWithoutLoadingIt()
        val adapter = FacebookSdkDisconnectAdapter()

        assertNull(adapter.currentSessionKey("another-user"))
        assertNotNull(adapter.currentSessionKey("test-user"))
    }

    @Test
    fun sdkSessionIsUnverifiedWhenStoredUserIdIsMissing() = runBlocking {
        initializeSdk()
        AccessToken.setCurrentAccessToken(accessToken("current-token"))
        val adapter = FacebookSdkDisconnectAdapter()

        for (missing in listOf(null, "")) {
            try {
                adapter.currentSessionKey(missing)
                fail("Phải báo chưa xác minh khi thiếu ID người dùng đã lưu.")
            } catch (_: UnverifiedFacebookSessionException) {
            }
        }
        assertNotNull(AccessToken.getCurrentAccessToken())
    }

    @Test
    fun forceSignOutClearsAnUnverifiedSdkSessionAndPersistsIt() = runBlocking {
        val app = initializeSdk()
        AccessToken.setCurrentAccessToken(accessToken("current-token"))
        AuthenticationToken.setCurrentAuthenticationToken(authenticationTokenFixture())
        persistPreferences(app, ACCESS_TOKEN_CACHE)
        persistPreferences(app, AUTHENTICATION_TOKEN_CACHE)
        val adapter = FacebookSdkDisconnectAdapter()
        try {
            adapter.signOut(UNVERIFIED_FACEBOOK_SESSION)
            fail("Tự động thử lại không được đăng xuất phiên chưa xác minh.")
        } catch (_: UnverifiedFacebookSessionException) {
        }
        assertNotNull(AccessToken.getCurrentAccessToken())

        adapter.forceSignOut()

        assertNull(AccessToken.getCurrentAccessToken())
        assertNull(AuthenticationToken.getCurrentAuthenticationToken())
        assertFalse(reopenPreferences(app, ACCESS_TOKEN_CACHE).contains(CACHED_ACCESS_TOKEN))
        assertFalse(reopenPreferences(app, AUTHENTICATION_TOKEN_CACHE).contains(CACHED_AUTHENTICATION_TOKEN))
        // SDK đã sạch nên lần thử lại tự động hoàn tất bình thường.
        adapter.signOut(UNVERIFIED_FACEBOOK_SESSION)
    }

    @Test
    fun noSdkSessionNeedsNoLogoutEvenWithoutStoredUserId() = runBlocking {
        initializeSdk()

        assertNull(FacebookSdkDisconnectAdapter().currentSessionKey(null))
    }

    private fun cacheSessionWithoutLoadingIt(): AccessToken {
        val token = accessToken("old-test-token")
        AccessToken.setCurrentAccessToken(token)
        val cache = FacebookSdk.getApplicationContext().getSharedPreferences(ACCESS_TOKEN_CACHE, Context.MODE_PRIVATE)
        val serialized = checkNotNull(cache.getString(CACHED_ACCESS_TOKEN, null))
        AccessToken.setCurrentAccessToken(null)
        assertTrue(cache.edit().putString(CACHED_ACCESS_TOKEN, serialized).commit())
        assertNull(AccessToken.getCurrentAccessToken())
        return token
    }

    private fun accessToken(value: String) =
        AccessToken(value, "123456789", "test-user", null, null, null, null, null, null, null)

    @Suppress("DEPRECATION")
    private fun initializeSdk(): Application {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Robolectric giữ static SDK giữa các test, nhưng mỗi test có một Application riêng.
        ReflectionHelpers.getStaticField<AtomicBoolean>(FacebookSdk::class.java, "sdkInitialized").set(false)
        ReflectionHelpers.setStaticField(AccessTokenManager::class.java, "instanceField", null)
        ReflectionHelpers.setStaticField(AuthenticationTokenManager::class.java, "instanceField", null)
        ReflectionHelpers.setStaticField(ProfileManager::class.java, "instance", null)
        ReflectionHelpers.setStaticField(LoginManager::class.java, "instance", null)
        // Khởi tạo SDK thật với cache của sandbox, bỏ các tác vụ nền gọi mạng.
        FacebookSdk.setExecutor { }
        FacebookSdk.setApplicationId("123456789")
        FacebookSdk.setClientToken("test-client-token")
        FacebookSdk.setAutoInitEnabled(false)
        FacebookSdk.setAutoLogAppEventsEnabled(false)
        FacebookSdk.sdkInitialize(app)
        return app
    }

    private fun persistPreferences(app: Application, name: String) {
        assertTrue(app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit())
    }

    private fun reopenPreferences(app: Application, name: String): SharedPreferences {
        val file = File(app.applicationInfo.dataDir, "shared_prefs/$name.xml")
        // Tạo trình đọc tệp riêng để tránh bản cache SharedPreferences còn trong process.
        val implementation = Class.forName("android.app.SharedPreferencesImpl").asSubclass(SharedPreferences::class.java)
        return ReflectionHelpers.callConstructor(
            implementation,
            ClassParameter.from(File::class.java, file),
            ClassParameter.from(Int::class.javaPrimitiveType, Context.MODE_PRIVATE),
        )
    }

    private fun authenticationTokenFixture(): AuthenticationToken = ReflectionHelpers.callConstructor(
        AuthenticationToken::class.java,
        ClassParameter.from(
            JSONObject::class.java,
            JSONObject(
                """
                {
                    "token_string": "test-authentication-token",
                    "expected_nonce": "test-nonce",
                    "signature": "test-signature",
                    "header": {"alg": "RS256", "typ": "JWT", "kid": "test-key"},
                    "claims": {
                        "jti": "test-id", "iss": "https://www.facebook.com", "aud": "123456789",
                        "nonce": "test-nonce", "exp": 2000000000, "iat": 1000000000, "sub": "test-user"
                    }
                }
                """.trimIndent(),
            ),
        ),
    )

    private companion object {
        const val ACCESS_TOKEN_CACHE = "com.facebook.AccessTokenManager.SharedPreferences"
        const val AUTHENTICATION_TOKEN_CACHE = "com.facebook.AuthenticationTokenManager.SharedPreferences"
        const val LOGIN_MANAGER_PREFERENCES = "com.facebook.loginManager"
        const val CACHED_ACCESS_TOKEN = "com.facebook.AccessTokenManager.CachedAccessToken"
        const val CACHED_AUTHENTICATION_TOKEN = "com.facebook.AuthenticationManager.CachedAuthenticationToken"
    }
}
