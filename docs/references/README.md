# Danh mục Tài liệu Tham chiếu Kỹ thuật (Documentation References)

Thư mục này chứa toàn bộ các tài liệu tham chiếu kỹ thuật chính thức, chuẩn RFC và hướng dẫn bảo mật cho dự án **Social Agent**.

---

## 1. OAuth 2.0 & PKCE RFC Standards

- 📄 **[RFC 6749 – The OAuth 2.0 Authorization Framework](oauth/rfc6749-oauth2-core.md)**
  - *URL gốc:* [https://www.rfc-editor.org/rfc/rfc6749](https://www.rfc-editor.org/rfc/rfc6749)
  - *Tóm tắt:* Chuẩn gốc định nghĩa các vai trò (Client, Resource Server, Authorization Server), 4 luồng ủy quyền (Authorization Code, Implicit, Password, Client Credentials), vòng đời Access/Refresh Token và công thức tính `expiresAt`.

- 📄 **[RFC 7636 – Proof Key for Code Exchange (PKCE)](oauth/rfc7636-pkce.md)**
  - *URL gốc:* [https://www.rfc-editor.org/rfc/rfc7636](https://www.rfc-editor.org/rfc/rfc7636)
  - *Tóm tắt:* Cơ chế bảo vệ Authorization Code cho Public Client trên ứng dụng di động native. Chi tiết phương thức sinh `code_verifier` (256-bit entropy) và `code_challenge` S256 (`BASE64URL(SHA256(verifier))`).

- 📄 **[RFC 8252 – OAuth 2.0 for Native Apps (BCP 212)](oauth/rfc8252-native-apps.md)**
  - *URL gốc:* [https://www.rfc-editor.org/rfc/rfc8252](https://www.rfc-editor.org/rfc/rfc8252)
  - *Tóm tắt:* Quy chuẩn thực hành tốt nhất cho ứng dụng native Android/iOS. Yêu cầu bắt buộc dùng External User-Agent (Custom Tabs), cấm dùng WebView nhúng và quy định các pattern Redirect URI (App Links, Custom Scheme).

---

## 2. Secure Storage & Cryptography trên Android

- 📄 **[Android Keystore System Technical Reference](security/android-keystore.md)**
  - *URL gốc:* [https://developer.android.com/privacy-and-security/keystore](https://developer.android.com/privacy-and-security/keystore)
  - *Tóm tắt:* Đặc tả hệ thống lưu trữ khóa mật mã an toàn phần cứng (Hardware-backed Keystore). Phân biệt cấp độ bảo mật TEE vs StrongBox KeyMint (Secure Element), mã mẫu AES-256-GCM và ràng buộc xác thực sinh trắc học.

- 📄 **[Android Cryptography & Jetpack Security Deprecation Reference](security/android-cryptography.md)**
  - *URL gốc:* [https://developer.android.com/privacy-and-security/cryptography](https://developer.android.com/privacy-and-security/cryptography)
  - *Tóm tắt:* Tổng quan về mã hóa Android, phân tích lý do dừng bảo trì thư viện Jetpack Security (`EncryptedSharedPreferences`) và chiến lược di chuyển sang DataStore Preferences kết hợp Android Keystore AES-256-GCM.

- 📄 **[KINTO Tech Blog – EncryptedSharedPreferences Migration Analysis](security/kinto-migration-analysis.md)**
  - *URL gốc:* [https://blog.kinto-technologies.com/posts/2025-06-16-encrypted-shared-preferences-migration/](https://blog.kinto-technologies.com/posts/2025-06-16-encrypted-shared-preferences-migration/)
  - *Tóm tắt:* Phân tích chuyên sâu và kết quả Microbenchmark so sánh giữa Android Keystore StrongBox (209ms), TEE (7.07ms) và Google Tink (0.573ms) trong việc di chuyển khỏi `EncryptedSharedPreferences`.
