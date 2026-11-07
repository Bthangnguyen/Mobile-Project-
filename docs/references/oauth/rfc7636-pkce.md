# RFC 7636 – Proof Key for Code Exchange (PKCE) by OAuth Public Clients

> **Nguồn chính thức:** IETF RFC 7636  
> **URL:** [https://www.rfc-editor.org/rfc/rfc7636](https://www.rfc-editor.org/rfc/rfc7636)  
> **Mục đích:** Đặc tả cơ chế mở rộng bảo vệ Authorization Code cho Public Clients (như ứng dụng Android native) chống lại tấn công chặn bắt mã ủy quyền (Authorization Code Interception Attack).

---

## 1. Mô hình Tấn công Chặn bắt Authorization Code (Attack Vector)

Trên các hệ điều hành di động như Android hay iOS, nhiều ứng dụng có thể đăng ký cùng một Custom URI Scheme (ví dụ: `com.example.app://oauth`).

```
+-------------------+                               +-------------------+
| Legitimate App    |                               | Malicious App     |
| (Custom Scheme A) |                               | (Custom Scheme A) |
+-------------------+                               +-------------------+
          |                                                   ^
          | (1) Authorization Request                         |
          v                                                   | (4) OS dispatches
+-------------------------------------------------------------+     redirect URI
|                  Operating System / Browser                 |     containing
+-------------------------------------------------------------+     code to attacker
                                  |                           |
                                  | (2) User authenticates    |
                                  v                           |
                      +-----------------------+               |
                      | Authorization Server  |---------------+ (3) Returns Code
                      +-----------------------+
```

1. **Ứng dụng độc hại** đăng ký trùng Custom Scheme của ứng dụng thật trên thiết bị.
2. Khi Authorization Server chuyển hướng trình duyệt về Custom Scheme kèm Authorization Code, hệ điều hành có thể giao Intent cho **ứng dụng độc hại**.
3. Do Public Client không có `client_secret`, ứng dụng độc hại có thể gửi Authorization Code này lên Token Endpoint để lấy Access Token hợp lệ của người dùng.

---

## 2. Thành phần Kỹ thuật Cốt lõi của PKCE

Toàn bộ cơ chế PKCE dựa trên 2 tham số chính được sinh ra theo từng phiên xác thực:

### 2.1. Code Verifier (`code_verifier`)
- Một chuỗi ngẫu nhiên có độ mã hóa cao (Cryptographically Random String) do ứng dụng Android tự sinh ra trước mỗi request xác thực.
- **Yêu cầu sinh:**
  - Độ dài: từ **43 đến 128 ký tự**.
  - Độ entropy khuyến nghị: Tối thiểu **256-bit entropy** (sinh từ 32 octet ngẫu nhiên).
  - Bộ ký tự cho phép (Unreserved Characters per RFC 3986): `[A-Z]`, `[a-z]`, `[0-9]`, `"-"`, `"."`, `"_"`, `"~"`.

### 2.2. Code Challenge (`code_challenge`)
- Chuỗi biến đổi từ `code_verifier` để gửi lên Authorization Endpoint.
- **Phương thức biến đổi (`code_challenge_method`):**
  1. **`S256` (BẮT BUỘC triển khai trên Server):**
     $$\text{code\_challenge} = \text{BASE64URL-ENCODE}(\text{SHA256}(\text{ASCII}(\text{code\_verifier})))$$
     *Mọi client Android có khả năng tính toán SHA-256 bắt buộc phải sử dụng phương thức `S256`.*
  2. **`plain`:**
     $$\text{code\_challenge} = \text{code\_verifier}$$
     *Chỉ chấp nhận khi môi trường thiết bị hạn chế không thể tính SHA-256.*

---

## 3. Luồng Hoạt động Chi tiết của PKCE

```
+--------+                               +---------------+
|        |--(A)- Code Challenge -------->| Authorization |
|        |       & Method (S256)         |    Endpoint   |
|        |<-(B)- Authorization Code -----|               |
| Client |                               +---------------+
|        |
|        |                               +---------------+
|        |--(C)- Authorization Code ---->|     Token     |
|        |       & Code Verifier         |    Endpoint   |
|        |<-(D)- Access Token -----------|               |
+--------+                               +---------------+
```

1. **Khởi tạo phía Client:**
   - Client sinh `code_verifier` ngẫu nhiên.
   - Client tính `code_challenge = BASE64URL(SHA256(code_verifier))`.

2. **Yêu cầu Ủy quyền (Authorization Request):**
   - Client mở trình duyệt tới Authorization Endpoint kèm các tham số:
     ```http
     GET /authorize?
       response_type=code
       &client_id=CLIENT_ID
       &redirect_uri=REDIRECT_URI
       &scope=read
       &code_challenge=CODE_CHALLENGE
       &code_challenge_method=S256 HTTP/1.1
     ```

3. **Lưu trữ phía Authorization Server:**
   - Server xác thực người dùng và lưu liên kết giữa `authorization_code` được phát hành với `code_challenge` và `code_challenge_method`.
   - Trả `authorization_code` về client qua trình duyệt.

4. **Yêu cầu Token (Access Token Request):**
   - Client gửi request POST lên Token Endpoint:
     ```http
     POST /token HTTP/1.1
     Host: server.example.com
     Content-Type: application/x-www-form-urlencoded

     grant_type=authorization_code
     &client_id=CLIENT_ID
     &code=AUTHORIZATION_CODE
     &redirect_uri=REDIRECT_URI
     &code_verifier=CODE_VERIFIER
     ```

5. **Xác minh tại Token Endpoint:**
   - Server lấy `code_challenge` đã lưu tương ứng với `code`.
   - Server tính toán: `TEST_CHALLENGE = BASE64URL(SHA256(received_code_verifier))`.
   - Nếu `TEST_CHALLENGE == stored_code_challenge`, server cấp Access Token.
   - Kẻ tấn công chặn được `authorization_code` nhưng **không thể có `code_verifier`** (vì `code_verifier` chỉ được lưu ở bộ nhớ trong của app hợp lệ và gửi qua kết nối trực tiếp TLS lên Token Endpoint), do đó request đổi token của kẻ tấn công sẽ bị từ chối với lỗi `invalid_grant`.

---

## 4. Bảng Quy định Mã lỗi PKCE

| Endpoint | Tình huống lỗi | Mã lỗi phản hồi |
|---|---|---|
| **Authorization Endpoint** | Server yêu cầu PKCE nhưng request thiếu `code_challenge`. | `invalid_request` |
| **Authorization Endpoint** | Server không hỗ trợ `code_challenge_method` được gửi lên (ví dụ gửi phương thức lạ). | `invalid_request` |
| **Token Endpoint** | Giá trị `code_verifier` gửi lên không khớp với `code_challenge` đã lưu. | `invalid_grant` |
