# Android Keystore System – Technical Reference

> **Nguồn chính thức:** Android Developers Security Documentation  
> **URL:** [https://developer.android.com/privacy-and-security/keystore](https://developer.android.com/privacy-and-security/keystore)  
> **Mục đích:** Đặc tả hệ thống lưu trữ và quản lý khóa mật mã an toàn phần cứng (Hardware-backed Keystore) trên Android.

---

## 1. Tổng quan Kiến trúc Android Keystore Provider

Hệ thống Android Keystore cho phép ứng dụng lưu trữ và thực hiện các thao tác mật mã (mã hóa, giải mã, ký số, xác thực HMAC) mà **không bao giờ để lộ vật liệu khóa (Key Material)** ra ngoài RAM process của ứng dụng.

```
+-------------------------------------------------------+
| App Process (com.lttbdd.socialagent)                  |
|   - Calling Cipher.doFinal(data)                      |
|   - Key handles / Aliases (No Plaintext Key Material) |
+-------------------------------------------------------+
                           | IPC
                           v
+-------------------------------------------------------+
| Android Keymaster / KeyMint Daemon (System Service)   |
+-------------------------------------------------------+
                           | Secure IPC
                           v
+-------------------------------------------------------+
| Hardware Isolated Domain                              |
|   - TEE (Trusted Execution Environment)               |
|   - StrongBox KeyMint (Secure Element / iSE)          |
|   * Performs Encryption/Decryption Inside Hardware *  |
+-------------------------------------------------------+
```

---

## 2. Phân biệt Cấp độ Bảo mật Phần cứng (Security Levels)

### 2.1. TEE (Trusted Execution Environment)
- Vùng môi trường thực thi tin cậy nằm trực tiếp trên vi xử lý chính (SoC).
- Khóa được tạo và lưu trữ cách ly hoàn toàn với hệ điều hành Android chính (Rich OS).
- Phương thức kiểm tra: `KeyInfo.getSecurityLevel() == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT`.

### 2.2. StrongBox KeyMint (Secure Element / SE / iSE)
- Được giới thiệu từ **Android 9 (API 28)**.
- Là một chip phần cứng hoàn toàn độc lập (Secure Element) có CPU riêng, vùng nhớ RAM/Flash an toàn riêng, bộ sinh số ngẫu nhiên phần cứng (TRNG) riêng và khả năng chống lại các tấn công vật lý (Physical Tamper Resistance).
- **Các thuật toán hỗ trợ trong StrongBox:**
  - AES (128, 256)
  - RSA 2048
  - ECDSA / ECDH P-256
  - HMAC-SHA256 (key size 8 đến 64 bytes)
- **Kích hoạt StrongBox trong Kotlin:**
  ```kotlin
  val keyGenSpec = KeyGenParameterSpec.Builder(
      alias,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
  ).apply {
      setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      setKeySize(256)
      setIsStrongBoxBacked(true) // Kích hoạt StrongBox SE
  }.build()
  ```
- *Lưu ý:* StrongBox có tốc độ truy xuất chậm hơn TEE do giao tiếp qua chip riêng. Nếu phần cứng thiết bị không có StrongBox, gọi `setIsStrongBoxBacked(true)` sẽ ném ra ngoại lệ `StrongBoxUnavailableException`.

---

## 3. Quy trình Tạo và Mã hóa Dữ liệu (AES-256-GCM)

Dưới đây là mẫu mã chuẩn khởi tạo Master Key trong Android Keystore và thực hiện mã hóa AES-256-GCM:

```kotlin
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

object AndroidKeystoreManager {

    private const val KEY_ALIAS = "SocialAgentMasterKey"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12

    fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(256)
            setRandomizedEncryptionRequired(true)
        }.build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv // 12 bytes IV
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext // Gộp IV và Ciphertext
    }

    fun decrypt(encryptedPayload: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey()
        val iv = encryptedPayload.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encryptedPayload.copyOfRange(GCM_IV_LENGTH, encryptedPayload.size)
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }
}
```

---

## 4. Ràng buộc Xác thực Người dùng (User Authentication Restrictions)

Android Keystore hỗ trợ gắn quyền truy cập khóa với sinh trắc học hoặc màn hình khóa thiết bị:

- **Duration-based Auth:** Khóa mở ra và cho phép dùng trong X giây sau khi người dùng mở khóa màn hình (`setUserAuthenticationParameters(timeoutSeconds, AUTH_BIOMETRIC_OR_CORRESPONDING_KEYGUARD)`).
- **Per-operation Auth:** Yêu cầu gọi `BiometricPrompt.authenticate()` trước **mỗi** thao tác mã hóa/ký số.
- **Biometric Invalidation:** Mặc định khi người dùng đăng ký thêm vân tay mới vào máy, các khóa mã hóa yêu cầu sinh trắc học sẽ bị vô hiệu hóa (`setInvalidatedByBiometricEnrollment(true)`) để chống tấn công thêm vân tay lạ.
