package com.lttbdd.socialagent.data.security

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.OAuthToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "social_agent_secure_tokens")

/**
 * Bộ lưu trữ Token an toàn cho ứng dụng Android.
 * Nguồn tham chiếu:
 * - Android Keystore system: https://developer.android.com/privacy-and-security/keystore
 * - Android Cryptography Guide: https://developer.android.com/privacy-and-security/cryptography
 * - DataStore Preferences kết hợp AndroidKeystoreCipher.
 *
 * Nguyên tắc bảo mật:
 * 1. Mọi OAuthToken trước khi lưu vào DataStore đều được mã hóa bằng Android Keystore (AES-256-GCM).
 * 2. Tuyệt đối không lưu mã thông báo dạng rõ (plaintext), không log token ra Logcat hay đưa vào repo.
 */
@Singleton
class SecureTokenStore @Inject constructor(
    private val context: Context,
    private val keystoreCipher: AndroidKeystoreCipher,
) : TokenStore {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Lưu trữ mã OAuthToken mã hóa theo kênh và ID tài khoản.
     */
    override suspend fun saveToken(channelType: ChannelType, accountId: String, token: OAuthToken) {
        val prefKey = stringPreferencesKey(buildTokenKey(channelType, accountId))
        val jsonString = json.encodeToString(token)
        val plaintextBytes = jsonString.toByteArray(Charsets.UTF_8)
        val encryptedBytes = keystoreCipher.encrypt(plaintextBytes)
        val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

        context.dataStore.edit { preferences ->
            preferences[prefKey] = encryptedBase64
        }
    }

    /**
     * Đọc và giải mã OAuthToken của một kênh. Trả về null nếu chưa có hoặc token bị hỏng.
     */
    override fun getToken(channelType: ChannelType, accountId: String): Flow<OAuthToken?> {
        val prefKey = stringPreferencesKey(buildTokenKey(channelType, accountId))
        return context.dataStore.data.map { preferences ->
            val encryptedBase64 = preferences[prefKey] ?: return@map null
            try {
                val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
                val decryptedBytes = keystoreCipher.decrypt(encryptedBytes)
                val jsonString = String(decryptedBytes, Charsets.UTF_8)
                json.decodeFromString<OAuthToken>(jsonString)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Xóa token khỏi bộ lưu trữ khi người dùng ngắt kết nối kênh.
     */
    override suspend fun removeToken(channelType: ChannelType, accountId: String) {
        val prefKey = stringPreferencesKey(buildTokenKey(channelType, accountId))
        context.dataStore.edit { preferences ->
            preferences.remove(prefKey)
        }
    }

    private fun buildTokenKey(channelType: ChannelType, accountId: String): String {
        return "token_${channelType.name}_$accountId"
    }
}
