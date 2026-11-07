# RFC 6749 – The OAuth 2.0 Authorization Framework

> **Nguồn chính thức:** IETF RFC 6749  
> **URL:** [https://www.rfc-editor.org/rfc/rfc6749](https://www.rfc-editor.org/rfc/rfc6749)  
> **Mục đích:** Đặc tả chuẩn gốc về kiến trúc ủy quyền OAuth 2.0, các luồng ủy quyền (Authorization Grants), vòng đời Access Token & Refresh Token, định dạng request/response và mô hình bảo mật.

---

## 1. Vai trò trong Khung Kiến trúc (OAuth 2.0 Roles)

RFC 6749 phân định 4 vai trò chính trong mọi luồng ủy quyền:

1. **Resource Owner (Chủ sở hữu tài nguyên):** Thường là người dùng cuối (End-User) có khả năng cấp quyền truy cập vào tài nguyên bảo mật của họ.
2. **Resource Server (Máy chủ tài nguyên):** Máy chủ lưu trữ tài nguyên bảo mật của người dùng, chấp nhận và phản hồi các yêu cầu dịch vụ khi có Access Token hợp lệ.
3. **Client (Ứng dụng khách):** Ứng dụng đưa ra các yêu cầu truy cập tài nguyên bảo mật thay mặt cho Resource Owner. Phân thành 2 loại:
   - **Confidential Client:** Các ứng dụng có khả năng giữ an toàn thông tin xác thực (`client_secret`), ví dụ: Web server backend.
   - **Public Client:** Các ứng dụng **không thể** giữ an toàn `client_secret`, ví dụ: App Android native, iOS native, Single Page Web Application (SPA).
4. **Authorization Server (Máy chủ ủy quyền):** Máy chủ thực hiện xác thực người dùng, xin sự đồng ý (Consent) và phát hành Access Token sau khi ủy quyền thành công.

---

## 2. Các Luồng Cấp quyền (Authorization Grants)

RFC 6749 định nghĩa 4 luồng cấp quyền chính:

### 2.1. Authorization Code Grant (`response_type=code`)
Luồng ủy quyền 3 chân (3-Legged OAuth) tiêu chuẩn, kết hợp giữa trình duyệt và kết nối server-to-server:
- Trình duyệt chuyển hướng người dùng tới Authorization Server để xác thực và cấp mã `code`.
- Client đổi mã `code` lấy `access_token` và `refresh_token` tại Token Endpoint.
- **Áp dụng cho Android:** Kết hợp bắt buộc với **PKCE (RFC 7636)** và **External User-Agent (RFC 8252)**.

### 2.2. Implicit Grant (`response_type=token`)
Trả về Access Token trực tiếp trên URL Fragment của trình duyệt mà không qua bước đổi `code`.
- **Trạng thái:** **NOT RECOMMENDED** (Không khuyến nghị) cho ứng dụng di động do không hỗ trợ PKCE, không bảo vệ được token và không cấp được Refresh Token.

### 2.3. Resource Owner Password Credentials Grant (`grant_type=password`)
Người dùng nhập trực tiếp username/password vào app để app gửi lên server đổi lấy Token.
- **Trạng thái:** **DEPRECATED** (Đã khai tử), chỉ dùng cho ứng dụng hệ thống cũ có độ tin cậy tuyệt đối.

### 2.4. Client Credentials Grant (`grant_type=client_credentials`)
Client tự xác thực bằng `client_id` và `client_secret` của chính mình để truy cập các tài nguyên cấp hệ thống (xác thực machine-to-machine).

---

## 3. Vòng đời Token (Access Token & Refresh Token)

```
+--------+                               +---------------+
|        |--(A)- Authorization Request ->|   Resource    |
|        |                               |     Owner     |
|        |<-(B)-- Authorization Grant ---|               |
|        |                               +---------------+
|        |
|        |                               +---------------+
|        |--(C)-- Authorization Grant -->| Authorization |
| Client |                               |    Server     |
|        |<-(D)----- Access Token -------|               |
|        |    & Optional Refresh Token   +---------------+
|        |
|        |                               +---------------+
|        |--(E)----- Access Token ------>|   Resource    |
|        |                               |    Server     |
|        |<-(F)--- Protected Resource ---|               |
+--------+                               +---------------+
```

### 3.1. Access Token
- Chuỗi ký tự biểu thị quyền truy cập được cấp cho client.
- Có thời hạn sử dụng xác định (biểu thị qua tham số `expires_in` tính bằng giây).
- Phản hồi từ Token Endpoint:
  ```json
  {
    "access_token": "2YotnFZFEjr1zCsicMWpAA",
    "token_type": "Bearer",
    "expires_in": 3600,
    "refresh_token": "tGzv3JOkF0XG5Qx2TlKWIA",
    "scope": "read write"
  }
  ```

### 3.2. Công thức tính thời điểm hết hạn (`expiresAt`)
Để lưu trữ thời hạn chính xác phía client, ứng dụng Android không lưu khoảng thời gian tương đối `expires_in` mà tính mốc thời gian tuyệt đối theo Unix timestamp:
$$\text{expiresAt} = \text{System.currentTimeMillis()} + (\text{expires\_in} \times 1000\text{L})$$

### 3.3. Refresh Token
- Chuỗi credential được cấp kèm với Access Token để client dùng đổi Access Token mới khi Access Token cũ hết hạn mà **không cần phiền người dùng đăng nhập lại**.
- Request đổi Token (`grant_type=refresh_token`):
  ```http
  POST /oauth/token HTTP/1.1
  Host: server.example.com
  Content-Type: application/x-www-form-urlencoded

  grant_type=refresh_token&refresh_token=tGzv3JOkF0XG5Qx2TlKWIA
  ```

---

## 4. Xử lý Lỗi chuẩn OAuth 2.0 (Error Responses)

Khi request gửi đến Authorization Endpoint hoặc Token Endpoint bị lỗi, máy chủ trả về các mã lỗi chuẩn:

| Mã lỗi (`error`) | Mô tả chi tiết | Xử lý khuyến nghị phía Android Client |
|---|---|---|
| `invalid_request` | Dữ liệu request bị thiếu tham số bắt buộc hoặc sai định dạng. | Kiểm tra lại logic xây dựng tham số request trong code. |
| `invalid_client` | Xác thực Client thất bại (`client_id` không tồn tại hoặc sai secret). | Kiểm tra lại cấu hình Client ID. |
| `invalid_grant` | Mã Authorization code hoặc Refresh token bị vô hiệu, hết hạn hoặc đã được sử dụng. | Chuyển trạng thái sang `TOKEN_EXPIRED`, yêu cầu người dùng Re-authenticate qua trình duyệt. |
| `unauthorized_client` | Client không được cấp phép sử dụng grant type này. | Kiểm tra lại cấu hình App trên Developer Dashboard. |
| `unsupported_grant_type` | Server không hỗ trợ `grant_type` được gửi lên. | Đổi sang grant type được hỗ trợ. |
| `invalid_scope` | Scope yêu cầu vượt quá quyền hạn được phép hoặc sai cú pháp. | Kiểm tra lại danh sách Scopes đã khai báo. |
