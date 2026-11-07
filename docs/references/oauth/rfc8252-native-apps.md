# RFC 8252 – OAuth 2.0 for Native Apps (Best Current Practice 212)

> **Nguồn chính thức:** IETF RFC 8252 (BCP 212)  
> **URL:** [https://www.rfc-editor.org/rfc/rfc8252](https://www.rfc-editor.org/rfc/rfc8252)  
> **Mục đích:** Đặc tả quy chuẩn thực hành tốt nhất (Best Current Practice) cho việc tích hợp xác thực OAuth 2.0 trên ứng dụng native di động (Android / iOS) và desktop.

---

## 1. External User-Agent vs. Embedded User-Agent (WebView)

RFC 8252 đưa ra quy định nghiêm ngặt về loại trình duyệt được phép sử dụng cho luồng ủy quyền OAuth trên ứng dụng native:

### 1.1. BẮT BUỘC dùng External User-Agent (Trình duyệt bên ngoài / Custom Tabs)
- Native app **BẮT BUỘC** phải thực hiện request ủy quyền OAuth qua một **External User-Agent** (trình duyệt hệ thống hoặc In-App Browser Tabs như Android Custom Tabs / iOS Safari View Controller).
- **Ưu điểm bảo mật & UX:**
  - **Bảo vệ Thông tin Đăng nhập:** App native hoàn toàn không thể can thiệp, đọc nội dung DOM hay đánh cắp mật khẩu/cookies của người dùng.
  - **Single Sign-On (SSO):** Tận dụng phiên đăng nhập (Cookies) đã có sẵn trên trình duyệt hệ thống của người dùng, cho phép đăng nhập 1 chạm mà không cần nhập lại tài khoản.

### 1.2. TUYỆT ĐỐI CẤM dùng Embedded User-Agent (Android WebView)
- **Rủi ro an ninh nghiêm trọng:** WebView nhúng nằm trong cùng process của app. App độc hại có thể đăng ký listener để ghi lại toàn bộ thao tác bàn phím (Keylogging), đọc mật khẩu, đánh cắp cookie session và can thiệp mã mã hóa.
- Google và Meta đều chặn hoàn toàn việc gọi luồng OAuth trên WebView nhúng.

---

## 2. Các Loại Pattern Redirect URI cho Native Apps

RFC 8252 định nghĩa 3 dạng Redirect URI cho ứng dụng native:

### 2.1. Claimed "https" Scheme URIs (Android App Links / iOS Universal Links)
- **Quy chuẩn:** Sử dụng domain HTTPS thuộc sở hữu của nhà phát triển (ví dụ: `https://socialagent.app/oauth/facebook/callback`).
- **Ưu điểm:** Hệ điều hành Android kiểm tra file xác thực domain (`assetlinks.json`) trên server để đảm bảo **duy nhất** app chính chủ được nhận Intent. Không thể bị app độc hại giả mạo hay chặn bắt.

### 2.2. Private-Use URI Schemes (Custom URL Schemes)
- **Quy chuẩn:** Sử dụng Custom Scheme đảo ngược domain name (Reverse Domain Name Notation), ví dụ: `com.lttbdd.socialagent://oauth/facebook`.
- **Cú pháp:** Phải có dấu gạch chéo đơn sau scheme (ví dụ: `com.example.app:/oauth2redirect`).
- **Khuyến nghị:** Phải kết hợp cùng **PKCE (RFC 7636)** để vô hiệu hóa rủi ro chặn bắt mã authorization code.

### 2.3. Loopback Interface Redirection (Dành cho Desktop Client)
- Sử dụng IP cục bộ `http://127.0.0.1:{port}/callback` hoặc `http://[::1]:{port}/callback`.
- Server phải cho phép chỉ định port động trong request.

---

## 3. Quy định Đăng ký Client & Yêu cầu trên Server

1. **Public Client Status:** Ứng dụng native trên di động phải được đăng ký dưới dạng **Public Client** trên Authorization Server.
2. **Khớp chính xác Redirect URI (Exact String Matching):** Authorization Server phải thực hiện so sánh chính xác 100% từng ký tự của Redirect URI gửi lên với Redirect URI đã đăng ký trên Developer Dashboard.
3. **Từ chối Request không có PKCE:** Authorization Server phải từ chối mọi request xác thực native client không có các tham số PKCE (`code_challenge` và `code_challenge_method`).
