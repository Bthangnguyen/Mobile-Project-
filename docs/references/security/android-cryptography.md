# Android Cryptography & Jetpack Security Deprecation Reference

> **Nguồn chính thức:** Android Developers Privacy & Security Documentation  
> **URL:** [https://developer.android.com/privacy-and-security/cryptography](https://developer.android.com/privacy-and-security/cryptography)  
> **Mục đích:** Đặc tả các quy chuẩn mã hóa trên Android, lý do dừng bảo trì thư viện Jetpack Security (`EncryptedSharedPreferences`) và hướng dẫn di chuyển sang kiến trúc lưu trữ an toàn hiện đại.

---

## 1. Thông báo Deprecation của Jetpack Security (`EncryptedSharedPreferences`)

Thư viện `androidx.security:security-crypto` (chứa 2 class `EncryptedSharedPreferences` và `MasterKey`) đã chính thức bị Google đánh nhãn **DEPRECATED** từ phiên bản `v1.1.0-alpha07`.

### 1.1. Các Nguyên nhân Kỹ thuật dẫn đến Deprecation
1. **Lỗi Rò rỉ Bộ nhớ và Deadlock:** `EncryptedSharedPreferences` hoạt động dựa trên wrapper đọc/ghi đồng bộ tệp XML SharedPreferences truyền thống. Quá trình giải mã/mã hóa khóa Master Key trên Keystore gây ra giật lag UI và nguy cơ deadlock khi gọi từ nhiều thread.
2. **Crash hỏng Key Keystore (`KeyPermanentlyInvalidatedException`):** Khi hệ thống Android tự động cập nhật hoặc khi người dùng thay đổi khóa màn hình, MasterKey của `EncryptedSharedPreferences` thường xuyên rơi vào trạng thái không thể giải mã, dẫn đến crash ứng dụng (`Fatal Exception: java.security.KeyStoreException`).
3. **Không hỗ trợ Coroutines / Asynchronous I/O:** Khác với Jetpack DataStore, SharedPreferences không có cơ chế Flow / Suspend functions, gây cản trở kiến trúc Android hiện đại.

---

## 2. Giải pháp Di chuyển Chuẩn hóa (Migration Strategy)

Google và tài liệu kỹ thuật Android khuyến nghị chuyển đổi theo mô hình:

$$\text{Tầng Lưu trữ (DataStore Preferences)} + \text{Tầng Mã hóa (Android Keystore AES-256-GCM / Tink)}$$

```
+-------------------------------------------------------+
| Application Business Logic / Repository               |
+-------------------------------------------------------+
                           |
                           v
+-------------------------------------------------------+
| SecureTokenStore (DataStore Preferences)              |
| - Key-Value Persistence                               |
| - Store Base64 Encrypted String (IV + Ciphertext)     |
+-------------------------------------------------------+
                           |
                           v
+-------------------------------------------------------+
| AndroidKeystoreManager (AES-256-GCM)                  |
| - Hardware Key Creation (TEE / StrongBox)             |
| - Enclose IV (12 bytes) + Tag (16 bytes)              |
+-------------------------------------------------------+
```

---

## 3. Quy chuẩn Thuật toán Mã hóa Khuyên dùng (Cryptographic Standards)

- **Mã hóa Đối xứng (Symmetric Encryption):** Sử dụng `AES/GCM/NoPadding` với độ dài khóa **256-bit** và độ dài Initialization Vector (IV) là **12 bytes**. AES-GCM cung cấp khả năng mã hóa đi kèm xác thực toàn vẹn (Authenticated Encryption with Associated Data - AEAD).
- **Hàm Băm (Hashing):** Sử dụng `SHA-256` hoặc `SHA-512`. Không dùng `MD5` hay `SHA-1` cho các mục đích bảo mật.
- **Ký số (Digital Signatures):** Sử dụng `ECDSA` với đường cong `P-256` (`SHA256withECDSA`).
