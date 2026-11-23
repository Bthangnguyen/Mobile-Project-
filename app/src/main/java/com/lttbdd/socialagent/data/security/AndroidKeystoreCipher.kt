package com.lttbdd.socialagent.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Trình thực thi mã hóa/giải mã trực tiếp bằng Android Keystore system.
 * Nguồn tham chiếu:
 * - Android Keystore system: https://developer.android.com/privacy-and-security/keystore
 * - Android Cryptography Guide: https://developer.android.com/privacy-and-security/cryptography
 *
 * Đảm bảo:
 * 1. Khóa được sinh và lưu trữ cách ly hoàn toàn trong Android Keystore với provider "AndroidKeyStore".
 * 2. Thuật toán AES/GCM/NoPadding (256-bit key, 12-byte IV).
 * 3. Tuyệt đối không sử dụng EncryptedSharedPreferences hay androidx.security:security-crypto (đã bị deprecated).
 */
class AndroidKeystoreCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply {
        load(null)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        if (keyStore.containsAlias(keyAlias)) {
            val entry = keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER,
        )

        val keyGenSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(256)
            setRandomizedEncryptionRequired(true)
        }.build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    /**
     * Mã hóa mảng byte dữ liệu. Kết quả trả về gồm 12-byte IV nối với ciphertext.
     */
    fun encrypt(plaintextBytes: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintextBytes)
        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
        return result
    }

    /**
     * Giải mã mảng byte chứa (12-byte IV + ciphertext).
     */
    fun decrypt(encryptedBytes: ByteArray): ByteArray {
        require(encryptedBytes.size > GCM_IV_LENGTH) { "Invalid encrypted payload size" }
        val secretKey = getOrCreateSecretKey()
        val iv = encryptedBytes.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encryptedBytes.copyOfRange(GCM_IV_LENGTH, encryptedBytes.size)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val DEFAULT_KEY_ALIAS = "SocialAgentOAuthMasterKey"
    }
}
