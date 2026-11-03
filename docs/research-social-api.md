# Nghiên cứu API Facebook Page & LinkedIn (F1, F5, F6, F7)

> **Tài liệu thuộc Issue #5**: `[Research] API Facebook Page và LinkedIn cho F1/F5/F6/F7`  
> **Chủ sở hữu:** Mảng B (Mạng xã hội — `@dhminh678`)  
> **Milestone:** Milestone 1  
> **Mục tiêu:** Nắm vững luồng xác thực OAuth 2.0 (PKCE) trên Android, quyền hạn, endpoint, giới hạn rate limit/App Review, giải pháp dự phòng và kiến trúc tích hợp cho `core/social/SocialChannelClient`.

---

## 1. Tổng quan & So sánh khả năng (Channel Capabilities)

Đối chiếu trực tiếp với enum `ChannelCapability` trong `core/model/Channel.kt`:
```kotlin
enum class ChannelCapability { PUBLISH, READ_COMMENTS, REPLY, MENTION, METRICS }
```

### Bảng ma trận khả năng của từng kênh (Capability Matrix)

| Khả năng (`ChannelCapability`) | Facebook Page (Meta Graph API v20.0+) | LinkedIn Organization (Company Page) | LinkedIn Member (Trang cá nhân) |
|---|---|---|---|
| **`PUBLISH` (Đăng bài)** | ✅ **Có** (Text, ảnh đơn/đa ảnh, link) qua Page Access Token | ✅ **Có** (Text, ảnh, article link, media URN) qua Org API | ✅ **Có** (Text, ảnh, article link) qua Share on LinkedIn |
| **`READ_COMMENTS` (Đọc comment)** | ✅ **Có** (Comment cấp 1, reply cấp 2, paging limit/offset) | ✅ **Có** (Đọc comment và reaction qua Community Management API) | ❌ **Không** (Không khả dụng với quyền hiện có của app; quyền đọc comment của member bị hạn chế) |
| **`REPLY` (Trả lời comment)** | ✅ **Có** (Gửi reply comment dưới tư cách Page) | ✅ **Có** (Gửi reply comment dưới tư cách Organization URN) | ❌ **Không** (Không khả dụng với quyền hiện có của app; phụ thuộc quyền đọc comment) |
| **`MENTION` (Nhắc tên)** | ⚠️ **Một phần** (Chỉ mention được các **Page khác** qua cú pháp `@[page_id]`; Meta cấm mention trang cá nhân) | ✅ **Có** (Hỗ trợ mention cả Organization `@[Tên Org](urn:li:organization:{id})` và Member `@[Tên](urn:li:person:{id})` khi có URN hợp lệ) | ✅ **Có** (Hỗ trợ chèn Organization và Member URN tương tự khi đăng bài cá nhân) |
| **`METRICS` (Số liệu)** | ✅ **Có** (Reactions, Comments, Shares, Impressions từ Insights) | ✅ **Có** (Likes, Comments, Shares, Impressions, Clicks, Engagement Rate) | ⚠️ **Hạn chế** (Chỉ số liệu tương tác bề mặt cơ bản) |
| **`FOLLOWER_COUNT`** | ✅ `followers_count` & `fan_count` qua `/{page-id}` | ✅ `firstDegreeSize` qua `/networkSizes` | ⚠️ Lấy qua `connectionsSummary` (yêu cầu quyền) |
| **Idempotency native** | ❌ Không hỗ trợ native (App tự quản lý qua Room DB) | ✅ Có hỗ trợ qua header `X-RestLi-Idempotency-Key` | ✅ Có hỗ trợ qua header `X-RestLi-Idempotency-Key` |
| **Tập hợp gán cho `ChannelAccount.capabilities` (F1)** | `setOf(PUBLISH, READ_COMMENTS, REPLY, MENTION, METRICS)` | `setOf(PUBLISH, READ_COMMENTS, REPLY, MENTION, METRICS)` | `setOf(PUBLISH, MENTION)` |

---

## 2. Kênh Facebook Page (Meta Graph API)

### 2.1. Luồng OAuth 2.0 trên Android & Quản lý Token (Public Client không giữ Secret)
- **Chuẩn kiến trúc:** Tuân thủ RFC 8252 (OAuth 2.0 for Native Apps) — ứng dụng Android là **Public Client**, **tuyệt đối không nhúng `client_secret` (App Secret)** vào app hay APK.
- **Thư viện đề xuất:** `net.openid:appauth:0.11.1` (AppAuth-Android) chạy trên Android Custom Tabs (trình duyệt hệ thống), bảo mật và an toàn hơn WebView nhúng.
- **Cơ chế bảo vệ:** PKCE (Proof Key for Code Exchange) với `code_challenge_method = S256` kết hợp cùng **Android Key Hash (SHA-1 fingerprint)** và Package Name đăng ký trên Meta Developer Portal để định danh app.
- **Endpoints:**
  - Authorization Endpoint: `https://www.facebook.com/v20.0/dialog/oauth`
  - Token Endpoint: `https://graph.facebook.com/v20.0/oauth/access_token`
  - Redirect URI: `com.lttbdd.socialagent://oauth/facebook` (hoặc `fb<APP_ID>://authorize`)

#### Trao đổi Token bằng PKCE (Không dùng Client Secret):
```http
GET https://graph.facebook.com/v20.0/oauth/access_token
    ?client_id={app-id}
    &redirect_uri={redirect-uri}
    &code={authorization-code}
    &code_verifier={code-verifier}
```
*Ghi chú:* Meta Graph API hỗ trợ xác thực mã ủy quyền trực tiếp qua `code_verifier` của PKCE mà không yêu cầu `client_secret` đối với các client native/mobile.

#### Vòng đời Token (Token Lifecycle):
```
[User Login trên Custom Tabs]
       │
       ▼ (Trả authorization code)
[App đổi lấy User Short-lived Access Token bằng PKCE code_verifier] (~1 - 2 giờ)
       │
       ▼ (Gọi /oauth/access_token?grant_type=fb_exchange_token&client_id={app-id}&fb_exchange_token=...)
[User Long-lived Access Token] (~60 ngày)
       │
       ▼ (Gọi GET /me/accounts với User Long-lived Token)
[Page Access Token (Long-lived / Never-expiring)]
```
* **Page Access Token:** Khi sinh ra từ một *User Long-lived Token*, Page Access Token này **không có thời hạn hết hạn (never expires)**, trừ khi:
  1. Người dùng đổi mật khẩu tài khoản Facebook.
  2. Người dùng gỡ quyền của App trong trang Cài đặt bảo mật Facebook.
  3. Người dùng bị xóa vai trò quản trị viên (Admin/Editor) khỏi Page.
* **Cơ chế kiểm tra & làm mới an toàn (F1):**
  - **Không gọi endpoint `/debug_token`** vì endpoint này yêu cầu App Secret (`app_id|app_secret`).
  - Thay vào đó, app kiểm tra token bằng cách gọi trực tiếp `GET /me?access_token={token}` hoặc `GET /me/accounts?access_token={token}`.
  - Khi token hết hạn hoặc quyền bị thu hồi, Graph API sẽ trả về lỗi HTTP 401 kèm mã lỗi `OAuthException` (mã lỗi `190` - Invalid/Expired Token).
  - Khi bắt được mã lỗi `190`: Chuyển trạng thái kênh sang `ConnectionStatus.TOKEN_EXPIRED` và hiển thị thông báo hướng dẫn người dùng kết nối lại.

### 2.2. Danh sách quyền (Permissions / Scopes)
Đối chiếu trực tiếp với các khả năng trong `ChannelCapability` (`core/model/Channel.kt`):

| Chức năng / Khả năng | Scope cần xin | Mô tả & Lưu ý kỹ thuật |
|---|---|---|
| **F1 (Kết nối kênh)** | `public_profile`, `pages_show_list` | Lấy danh sách các Page người dùng quản lý (`GET /me/accounts`) và lấy Page Access Token. |
| **`PUBLISH` (Đăng bài)** | `pages_manage_posts`, `pages_read_engagement` | Đăng bài viết (text, link), upload ảnh/video thay mặt Page lên endpoint `/{page-id}/feed` hoặc `/{page-id}/photos`. |
| **`READ_COMMENTS` (Đọc comment)** | `pages_read_user_content`, `pages_read_engagement` | Đọc danh sách bình luận của người dùng và các phản hồi trên bài viết (`GET /{post-id}/comments`). |
| **`REPLY` (Trả lời comment)** | `pages_manage_engagement` | Gửi bình luận trả lời (`POST /{comment-id}/comments`) dưới danh nghĩa Page. |
| **`MENTION` (Nhắc tên)** | `pages_manage_posts` *(kèm cú pháp)* | Meta **không cấp quyền API để mention trang cá nhân** (Personal Profile) để chống spam. Chỉ hỗ trợ mention các **Page khác** trong nội dung bài viết với cú pháp `@[page_id]`. |
| **`METRICS` (Số liệu)** | `read_insights`, `pages_read_engagement` | Đọc số lượt hiển thị (`impressions`), tương tác (`reactions`, `shares`, `comments count`) và số lượng người theo dõi (`followers_count`, `fan_count`). |

### 2.3. Các Endpoint chính
1. **Lấy danh sách Page và Page Token (F1):**
   ```http
   GET https://graph.facebook.com/v20.0/me/accounts?fields=id,name,access_token,category,tasks
   ```
2. **Đăng bài viết kèm nội dung văn bản (F5):**
   ```http
   POST https://graph.facebook.com/v20.0/{page-id}/feed
   Content-Type: application/json

   {
     "message": "Nội dung bài viết do AI soạn...",
     "access_token": "{page-access-token}"
   }
   ```
3. **Đăng bài viết kèm ảnh (F5):**
   ```http
   POST https://graph.facebook.com/v20.0/{page-id}/photos
   Content-Type: multipart/form-data

   message="Nội dung bài viết kèm ảnh..."
   source=@/path/to/image.png
   access_token={page-access-token}
   ```
   *Trả về:* `{"id": "photo_id", "post_id": "{page-id}_{post-id}"}`.
4. **Lấy danh sách comment của bài viết (F6):**
   ```http
   GET https://graph.facebook.com/v20.0/{post-id}/comments?fields=id,from,message,created_time,comment_count,parent&limit=50&access_token={page-access-token}
   ```
5. **Trả lời comment (F6):**
   ```http
   POST https://graph.facebook.com/v20.0/{comment-id}/comments
   Content-Type: application/json

   {
     "message": "Chào bạn, cảm ơn bạn đã quan tâm...",
     "access_token": "{page-access-token}"
   }
   ```
6. **Lấy số liệu bài viết (F7):**
   ```http
   GET https://graph.facebook.com/v20.0/{post-id}?fields=shares,comments.summary(true),reactions.summary(true),insights.metric(post_impressions,post_engaged_users)&access_token={page-access-token}
   ```
7. **Lấy số follower của Page (F7):**
   ```http
   GET https://graph.facebook.com/v20.0/{page-id}?fields=followers_count,fan_count&access_token={page-access-token}
   ```

### 2.4. Giới hạn & Quy định kiểm duyệt (App Review)
- **Độ dài bài viết tối đa:** `63,206` ký tự (khớp cấu hình trong `ChannelType.FACEBOOK(maxChars = 63_206)`).
- **Kích thước ảnh & Định dạng:**
  - Upload trực tiếp file binary (multipart upload): Tối đa **4 MB**.
  - Upload qua URL ảnh công khai: Tối đa **10 MB**.
  - Định dạng hỗ trợ: JPEG, PNG, GIF, WebP.
- **Rate Limit (Giới hạn tần suất gọi):** Áp dụng cơ chế **Business Use Case (BUC) Rate Limiting**:
  - $4.800$ cuộc gọi API / mỗi Page / mỗi giờ.
  - Header phản hồi: `X-Business-Use-Case-Usage` hoặc `X-App-Usage` (chứa tỉ lệ % `call_count`, `cpu_time`, `total_time`).
  - Mã lỗi khi chạm trần: Mã `4` (App rate limit) hoặc `17` (User rate limit) $\rightarrow$ Map sang `PublishResult.RetryableError` kèm thuật toán Exponential Backoff.
- **Quyền nào cần App Review (Meta App Review):**
  - *Default Access (Tự động có, không cần review):* Chỉ có `public_profile` và `email`.
  - *Advanced Access (BẮT BUỘC App Review nếu dùng công chúng):* Tất cả các quyền phục vụ tính năng chính: `pages_show_list`, `pages_manage_posts`, `pages_read_engagement`, `pages_read_user_content`, `pages_manage_engagement`, `read_insights`.
  - *Điều kiện qua App Review:* Bắt buộc phải có **Business Verification** (Xác minh giấy phép doanh nghiệp) và video screencast chứng minh luồng ứng dụng thực tế.
- **Chế độ Development dùng được tới đâu:**
  - **Phạm vi hoạt động:** Ở chế độ **Development Mode**, mọi quyền nâng cao (`pages_manage_posts`, `read_insights`, v.v.) hoạt động **100% đầy đủ và bình thường**, hoàn toàn **KHÔNG cần kiểm duyệt App Review hay xác minh doanh nghiệp**.
  - **Giới hạn người dùng:** Chỉ các tài khoản Facebook có vai trò trong ứng dụng (*Administrators*, *Developers*, hoặc *Testers* trong mục *App Roles* trên Meta Dashboard) và các Page do các tài khoản này sở hữu mới có thể đăng nhập và gọi API. Người dùng vãng lai ngoài danh sách sẽ bị Meta chặn đăng nhập.
  - **Kết luận cho đồ án:** Nhóm hoàn thành toàn bộ đồ án ở **Development Mode** bằng cách thêm tài khoản Facebook của các thành viên nhóm vào danh sách Testers/Developers.

---

## 3. Kênh LinkedIn (LinkedIn REST API)

### 3.1. Luồng OAuth 2.0 trên Android & Quản lý Token

> [!IMPORTANT]
> **Điểm cốt lõi về Native PKCE của LinkedIn:**
> LinkedIn phân tách rõ ràng giữa **giao thức Native PKCE dành riêng cho ứng dụng di động** và **luồng Standard OAuth 2.0 Web**. Nhóm cần nắm rõ điều kiện áp dụng của từng luồng để tránh lỗi phân quyền `Not enough permissions to access Native PKCE protocol` khi tích hợp.

#### Luồng A: LinkedIn Native PKCE Protocol (Trực tiếp Client — Không giữ Secret)
Đây là giao thức chính thức của LinkedIn dành cho các ứng dụng native (Android/Desktop) không có backend:
- **Authorization Endpoint:** `https://www.linkedin.com/oauth/native-pkce/authorization` *(khác với `/oauth/v2/authorization`)*
- **Token Endpoint:** `https://www.linkedin.com/oauth/v2/accessToken`
- **Redirect URI:** Bắt buộc dùng **Loopback Interface** (ví dụ: `http://127.0.0.1:{port}` hoặc `http://127.0.0.1:{port}/callback`). Ứng dụng Android khởi tạo một `ServerSocket` cục bộ tạm thời để lắng nghe callback từ Custom Tabs.
- **Điều kiện áp dụng bắt buộc:**
  - Mặc định các App tạo trên LinkedIn Developers **chưa được bật cờ Native PKCE**.
  - **Bắt buộc:** Nhóm phát triển phải gửi yêu cầu (ticket) đến **LinkedIn Developer Support** để được bật riêng quyền sử dụng giao thức *Native PKCE Protocol* cho App ID của mình.
- **Trao đổi Token bằng PKCE (Không có Client Secret):**
  ```http
  POST https://www.linkedin.com/oauth/v2/accessToken
  Content-Type: application/x-www-form-urlencoded

  grant_type=authorization_code
  &code={authorization_code}
  &redirect_uri=http://127.0.0.1:{port}/callback
  &client_id={client_id}
  &code_verifier={code_verifier}
  ```

#### Luồng B: Standard OAuth 2.0 (`/oauth/v2/...`) qua Kiến trúc BFF (Backend Proxy)
Nếu LinkedIn chưa kịp duyệt cấp cờ Native PKCE cho App ID của nhóm:
- **Authorization Endpoint:** `https://www.linkedin.com/oauth/v2/authorization`
- **Token Endpoint:** `https://www.linkedin.com/oauth/v2/accessToken`
- **Redirect URI:** Sử dụng Custom Scheme `com.lttbdd.socialagent://oauth/linkedin`.
- **Ràng buộc kỹ thuật:** Luồng standard này **bắt buộc phải có `client_secret`** khi gọi token endpoint.
- **Giải pháp bảo mật (BFF - Backend-For-Frontend):**
  - Tuyệt đối **không đưa `client_secret` vào mã nguồn Android hay APK**.
  - App nhận authorization code từ Custom Tabs, sau đó gửi `code` về một server trung gian (BFF hoặc Cloudflare Worker / Serverless function do nhóm dựng). Server này giữ `client_secret` an toàn, thực hiện đổi token với LinkedIn và trả access token an toàn về cho app.

#### Bảng so sánh hai luồng OAuth của LinkedIn

| Tiêu chí | Luồng A: Native PKCE (`/oauth/native-pkce/`) | Luồng B: Standard OAuth (`/oauth/v2/`) qua BFF |
|---|---|---|
| **Endpoint cấp mã** | `https://www.linkedin.com/oauth/native-pkce/authorization` | `https://www.linkedin.com/oauth/v2/authorization` |
| **Kiểu Redirect URI** | Loopback IP (`http://127.0.0.1:{port}/callback`) | Custom Scheme (`com.lttbdd.socialagent://oauth/linkedin`) |
| **Yêu cầu Client Secret** | ❌ **Không** (Trao đổi bằng `code_verifier`) | ✅ **Bắt buộc** (Phải ủy quyền qua Backend/BFF) |
| **Điều kiện kích hoạt** | Cần liên hệ **LinkedIn Support duyệt bật cờ Native PKCE** | Kích hoạt sẵn ngay khi tạo App trên Developer Portal |
| **Phù hợp đồ án** | Tối ưu khi đã được LinkedIn duyệt cờ Native PKCE | Dùng dự phòng nếu chưa kịp duyệt cờ PKCE từ LinkedIn |

#### Vòng đời Token & Cơ chế Tái xác thực (Token Lifecycle & Re-authentication)

> [!CAUTION]
> **Ràng buộc nền tảng về Programmatic Refresh Token của LinkedIn:**
> - Scope `offline_access` **không có sẵn cho các ứng dụng Self-service thông thường** (nếu truyền vào request sẽ bị LinkedIn từ chối với lỗi `Unknown scope`).
> - Quyền cấp **Refresh Token (thời hạn 365 ngày)** để làm mới ngầm qua API là tính năng đặc quyền, **chỉ được cấp khi App được phê duyệt vào chương trình Đối tác Doanh nghiệp chính thức** (Approved Enterprise Partners, ví dụ: Marketing Developer Platform - MDP).
> - **Nguyên tắc thiết kế F1:** Ứng dụng **tuyệt đối không phụ thuộc hoàn toàn vào Refresh Token**, mà phải lấy luồng **Re-authentication định kỳ 60 ngày** làm luồng vận hành chính thức, đảm bảo hệ thống luôn hoạt động ổn định kể cả khi không có quyền đối tác.

1. **Vòng đời Access Token Tiêu chuẩn:** Có thời hạn cố định là **60 ngày**.
2. **Phương án Xử lý khi Hết hạn Token (Re-authentication Flow — Cho ứng dụng thông thường):**
   - **Theo dõi thời hạn:** Khi đăng nhập thành công, app lưu `expires_at = System.currentTimeMillis() + (expires_in * 1000L)` vào Room Database.
   - **Kích hoạt trạng thái hết hạn:** 
     - Khi thời gian còn lại dưới 3 ngày, hoặc khi nhận mã HTTP `401 Unauthorized` từ bất kỳ request API LinkedIn nào.
     - Repository cập nhật trạng thái kênh trong Room DB thành `ConnectionStatus.TOKEN_EXPIRED`.
   - **Trải nghiệm người dùng (UX):**
     - Màn hình "Kênh của tôi" (F1) và màn hình Soạn bài/Duyệt bài (F4) hiển thị Badge cảnh báo: *"Phiên kết nối LinkedIn đã hết hạn (chu kỳ 60 ngày). Vui lòng kết nối lại"*.
     - Nút "Kết nối lại" (Re-connect) kích hoạt mở lại Custom Tabs. Do trình duyệt hệ thống thường vẫn lưu phiên đăng nhập của người dùng trên web LinkedIn, người dùng chỉ cần xác nhận 1 chạm (Single-tap Consent) để cấp mã `code` mới và nhận lại Access Token 60 ngày mới mà không phải gõ lại mật khẩu.
3. **Phương án Dự phòng khi được cấp Programmatic Refresh Token (Nếu được duyệt Partner Tier):**
   - Nếu response đổi token có trả về trường `refresh_token` (hạn tối đa 365 ngày), app lưu an toàn vào Android Keystore.
   - Định kỳ trước khi token 60 ngày hết hạn 7 ngày, app gửi request làm mới ngầm bằng `grant_type=refresh_token`:
     ```http
     POST https://www.linkedin.com/oauth/v2/accessToken
     Content-Type: application/x-www-form-urlencoded

     grant_type=refresh_token
     &refresh_token={refresh_token}
     &client_id={client_id}
     ```
   - *Lưu ý:* Việc refresh token không kéo dài thời hạn 365 ngày ban đầu của Refresh Token. Sau 365 ngày, người dùng vẫn bắt buộc phải trải qua luồng Re-authentication qua trình duyệt.

### 3.2. Danh sách quyền (Permissions / Scopes)
Đối chiếu trực tiếp với các khả năng trong `ChannelCapability` (`core/model/Channel.kt`) theo chuẩn LinkedIn REST API & Community Management API:

| Chức năng / Khả năng | Scope cần xin | Mục đích & Chi tiết kỹ thuật |
|---|---|---|
| **F1 (Kết nối & Quản trị)** | `openid`, `profile`, `r_organization_admin` | Xác thực đăng nhập qua OpenID Connect và kiểm tra vai trò quản trị viên (Admin) trên Page công ty. |
| **`PUBLISH` (Đăng bài)** | `w_organization_social` *(Page)*<br>`w_member_social` *(Cá nhân)* | Đăng bài viết (text, link, media URN) lên Organization Page hoặc trang cá nhân của thành viên. |
| **`READ_COMMENTS` (Đọc comment)** | `r_organization_social_feed` | Đọc danh sách bình luận, các reply phân cấp và reaction trên bài viết của Organization Page (`GET /rest/socialActions/{targetUrn}/comments`). **Yêu cầu:** Sản phẩm Community Management API và người dùng có vai trò Administrator/Poster trên Organization. |
| **`REPLY` (Trả lời comment)** | `w_organization_social_feed` | Gửi bình luận trả lời (`POST /rest/socialActions/{targetUrn}/comments`) dưới danh nghĩa `urn:li:organization:{org-id}`. **Yêu cầu:** Sản phẩm Community Management API. |
| **`MENTION` (Nhắc tên)** | `w_organization_social` *(kèm URN)* | Hỗ trợ mention cả Organization `@[Tên Tổ chức](urn:li:organization:{id})` và Member `@[Tên Hiển thị](urn:li:person:{id})` trong nội dung bài đăng. **Điều kiện:** Phải có Person/Org URN hợp lệ (lấy từ OIDC `sub` claim, danh sách quản trị hoặc tương tác trước đó). LinkedIn không cung cấp API tìm kiếm profile URN tự do cho app thông thường (cần tier Đối tác Quảng cáo/Tuyển dụng). |
| **`METRICS` (Số liệu)** | `rw_organization_admin`, `r_organization_social` | Đọc số liệu tương tác (`likeCount`, `commentCount`, `shareCount`, `impressionCount`, `clickCount`) qua `GET /rest/organizationalEntityShareStatistics` và số người theo dõi (`firstDegreeSize`) qua `GET /rest/networkSizes/{orgUrn}`. **Lưu ý:** Endpoint thống kê yêu cầu bắt buộc scope quản trị `rw_organization_admin` và người dùng ủy quyền phải nắm vai trò `ADMINISTRATOR` của Organization. |
| **Làm mới ngầm (F1 - Tùy chọn)** | `offline_access` *(Yêu cầu quyền Partner)* | Chỉ cấp cho app được duyệt **Programmatic Refresh (MDP)** để cấp refresh token 365 ngày. App thông thường dùng luồng Re-authentication định kỳ 60 ngày. |

### 3.3. Các Endpoint chính (LinkedIn Versioned REST API)

> [!IMPORTANT]
> **Chính sách Phiên bản & Vòng đời API của LinkedIn (Version Sunset Policy):**
> - LinkedIn áp dụng cơ chế định phiên bản theo tháng với định dạng **`YYYYMM`** thông qua header `Linkedin-Version`.
> - **Thời hạn hỗ trợ:** Mỗi phiên bản API chỉ được hỗ trợ trong vòng **12 tháng (1 năm)** kể từ ngày phát hành, sau đó sẽ chính thức **Sunset (ngừng hoạt động)**.
> - **Cảnh báo lỗi:** Việc gửi một phiên bản đã sunset (ví dụ `202401` đã bị sunset) sẽ khiến request bị từ chối ngay lập tức với lỗi `HTTP 426 Upgrade Required` hoặc `400 Bad Request`.
> - **Phiên bản áp dụng chuẩn hóa:** Tài liệu được kiểm tra và đồng bộ vào ngày **03/11/2026** với phiên bản **`202610`** (phiên bản đang được hỗ trợ chính thức đến tháng 10/2027).
> - **Quy ước triển khai:** Trong mã nguồn Kotlin của Mảng B, version này cần được định nghĩa thành hằng số tập trung (ví dụ: `private const val LINKEDIN_API_VERSION = "202610"`) để dễ bảo trì và nâng cấp định kỳ.

1. **Lấy thông tin người dùng / URN cá nhân (F1):**
   ```http
   GET https://api.linkedin.com/v2/userinfo
   Authorization: Bearer {access-token}
   ```
2. **Khởi tạo và upload ảnh (F5 - Hai bước):**
   - Bước 1: Khởi tạo upload ảnh
     ```http
     POST https://api.linkedin.com/rest/images?action=initializeUpload
     Headers:
       Authorization: Bearer {access-token}
       Linkedin-Version: 202610
       X-Restli-Protocol-Version: 2.0.0
       Content-Type: application/json

     {
       "initializeUploadRequest": {
         "owner": "urn:li:organization:{org-id}"
       }
     }
     ```
     *Nhận về:* `uploadUrl` và `image` (URN: `urn:li:image:{image-id}`).
   - Bước 2: Tải file binary ảnh lên `uploadUrl` bằng HTTP PUT (`Content-Type: image/jpeg` hoặc `image/png`).
3. **Đăng bài viết (F5 - Hỗ trợ Idempotency native & Mention cú pháp chuẩn):**
   ```http
   POST https://api.linkedin.com/rest/posts
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
     X-RestLi-Idempotency-Key: {idempotencyKey}
     Content-Type: application/json

   {
     "author": "urn:li:organization:{org-id}",
     "commentary": "Chúc mừng @[Nguyễn Văn A](urn:li:person:123456) và @[SocialAgent Team](urn:li:organization:{org-id}) đã hoàn thành xuất sắc dự án!",
     "visibility": "PUBLIC",
     "distribution": {
       "feedDistribution": "MAIN_FEED",
       "targetEntities": [],
       "thirdPartyDistributionChannels": []
     },
     "content": {
       "media": {
         "id": "urn:li:image:{image-id}",
         "altText": "Mô tả ảnh sinh bởi AI"
       }
     },
     "lifecycleState": "PUBLISHED"
   }
   ```
4. **Lấy danh sách comment (F6):**
   *(Scope yêu cầu: `r_organization_social_feed` qua Community Management API)*
   ```http
   GET https://api.linkedin.com/rest/socialActions/{targetUrn}/comments
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
   ```
5. **Trả lời comment (F6):**
   *(Scope yêu cầu: `w_organization_social_feed` qua Community Management API)*
   ```http
   POST https://api.linkedin.com/rest/socialActions/{targetUrn}/comments
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
     Content-Type: application/json

   {
     "actor": "urn:li:organization:{org-id}",
     "message": { "text": "Cảm ơn sự đóng góp của bạn!" },
     "parentComment": "{parentCommentUrn}"
   }
   ```
6. **Lấy số liệu bài viết (F7):**
   *(Scope yêu cầu: `rw_organization_admin` kèm vai trò Administrator của Organization)*
   ```http
   GET https://api.linkedin.com/rest/organizationalEntityShareStatistics?q=organizationalEntity&organizationalEntity=urn:li:organization:{org-id}&shares=List({postUrn})
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
   ```
   *Trả về:* `likeCount`, `commentCount`, `shareCount`, `impressionCount`, `clickCount`.
7. **Lấy số follower (F7):**
   *(Scope yêu cầu: `rw_organization_admin` hoặc `r_organization_social`)*
   ```http
   GET https://api.linkedin.com/rest/networkSizes/urn:li:organization:{org-id}?edgeType=CompanyFollowedByMember
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
   ```
   *Trả về:* `firstDegreeSize`.

### 3.4. Giới hạn (Rate Limits) & Quy định kiểm duyệt
- **Độ dài bài viết tối đa:** `3,000` ký tự (khớp cấu hình trong `ChannelType.LINKEDIN(maxChars = 3_000)`).
- **Kích thước ảnh & Định dạng:**
  - Tối đa **8 MB** cho ảnh bài đăng thông thường.
  - Tỉ lệ khuyến nghị: `1.91:1` (ngang: 1200 x 627 px) hoặc `1:1` (vuông: 1200 x 1200 px). Định dạng: PNG, JPEG, GIF.
- **Hạn ngạch & Giới hạn tần suất gọi (Rate Limits & Quotas theo Product / Tier):**
  - **Sản phẩm Share on LinkedIn (Đăng bài cá nhân):**
    - Hạn ngạch tác giả: Tối đa $100$ bài đăng / mỗi tác giả / ngày (chu kỳ 24h).
    - Hạn ngạch toàn ứng dụng: Tối đa $100.000$ request / ngày toàn app.
  - **Sản phẩm Community Management API (Page Công ty — F6/F7):**
    - **Development Tier (Hạn ngạch giai đoạn phát triển & thử nghiệm):**
      - **Application Throttle (Toàn ứng dụng):** Tối đa **500 API calls / app / 24 giờ (UTC)**.
      - **Member Throttle (Mỗi thành viên):** Tối đa **100 API calls / member / 24 giờ (UTC)**.
      - *Phạm vi áp dụng:* Tính gộp cho toàn bộ các endpoint thuộc sản phẩm: đọc/trả lời comment (`socialActions/comments`), truy vấn số liệu tương tác (`organizationalEntityShareStatistics`) và số follower (`networkSizes`).
      - *Chu kỳ làm mới:* Tự động reset hàng ngày vào lúc **00:00 UTC** (tức 07:00 sáng theo giờ Việt Nam).
    - **Standard Tier (Production):** Cần nộp đơn yêu cầu (Access Request Form) kèm video screencast chứng minh use-case thực tế để được nâng hạn ngạch production.
  - **Cơ chế xử lý khi vượt ngưỡng:** Trả về HTTP `429 Too Many Requests` (kèm header `Retry-After`) $\rightarrow$ Map sang `PublishResult.RetryableError` với thuật toán Exponential Backoff.
- **Hướng dẫn Kiểm tra Quota & Usage thực tế trên LinkedIn Developer Portal:**
  1. Đăng nhập [LinkedIn Developer Portal](https://developer.linkedin.com/), chọn ứng dụng `SocialAgent App`.
  2. Chọn tab **Analytics** trên thanh menu điều hướng của ứng dụng.
  3. Chọn xem theo bộ lọc **Application Quota** hoặc **Member Quota** để theo dõi số lượng call đã thực hiện và hạn ngạch còn lại trong ngày theo từng endpoint.
  4. **Lưu ý kỹ thuật ("First Call Rule"):** Tab *Analytics* chỉ hiển thị các endpoint mà app đã gửi **ít nhất 1 request hợp lệ trong ngày UTC hiện tại**. Nếu một endpoint chưa từng được gọi, nó sẽ không xuất hiện trên biểu đồ. Để kiểm tra hạn ngạch của endpoint mới (ví dụ `socialActions` hoặc `networkSizes`), app cần gửi 1 request test, sau đó refresh trang Analytics.
- **Quyền nào cần App Review / Phê duyệt Sản phẩm (Products):**
  - *Sản phẩm Share on LinkedIn & Sign In with OpenID Connect (Không cần duyệt phức tạp):* Cấp tự động các quyền cơ bản `openid`, `profile`, `w_member_social`. Cho phép đăng bài lên trang cá nhân ngay lập tức.
  - *Sản phẩm Community Management API (Cần đăng ký & duyệt):* Bắt buộc để cấp các quyền thao tác trên Page công ty: `w_organization_social`, `r_organization_social_feed`, `w_organization_social_feed`, `rw_organization_admin`, `r_organization_admin`.
- **Chế độ Development dùng được tới đâu:**
  - **Phạm vi hoạt động:** Cho phép đăng bài lên trang cá nhân của các Developer/Tester trong nhóm và tương tác với các Organization Page thử nghiệm mà thành viên nắm quyền Quản trị (Admin) trong hạn mức an toàn của Development tier (500 calls/app và 100 calls/member mỗi ngày).
  - **Dự phòng rào cản kiểm duyệt:** Nếu LinkedIn chưa kịp cấp quyền Community Management API cho Organization Page, app tự động kích hoạt luồng **`HANDED_OFF`** (Mục 4) để copy bài và mở Android Share Intent, đảm bảo luồng trải nghiệm không bao giờ bị đứt gãy.

---

## 4. Chức năng không làm được qua API & Đề xuất "Đã bàn giao" (`HANDED_OFF`)

### 4.1. Các hạn chế bất khả kháng từ chính sách API
1. **Đăng bài lên Facebook cá nhân (Personal Profile Timeline):** Meta đã đóng hoàn toàn quyền `publish_actions` từ năm 2018. Không ứng dụng bên thứ ba nào được phép đăng bài trực tiếp lên trang cá nhân của người dùng qua API (chỉ cho phép đăng lên Page).
2. **Mention người dùng cá nhân trên Facebook & Rào cản tìm kiếm URN:**
   - *Meta (Facebook):* Chặn hoàn toàn API mention tài khoản cá nhân (Personal Profile) để chống spam và bảo vệ quyền riêng tư; chỉ cho phép mention các Facebook Page khác bằng cú pháp `@[page_id]`.
   - *LinkedIn:* Posts API **hỗ trợ mention Member (người dùng cá nhân)** với cú pháp chuẩn `@[Tên Hiển Thị](urn:li:person:{id})` khi ứng dụng có Person URN hợp lệ (lấy qua OIDC `sub`, danh sách tương tác hoặc profile đã liên kết). Rào cản chỉ xảy ra khi **chưa có sẵn URN**, vì LinkedIn không mở API tìm kiếm tự do profile người dùng cho ứng dụng thông thường. Trường hợp này hệ thống sẽ kích hoạt luồng `HANDED_OFF` để người dùng gõ mention trực tiếp trên ứng dụng mạng xã hội.
3. **App chưa được cấp quyền Public / Chưa qua App Review:** Khi tài khoản người dùng cuối không thuộc danh sách Developer/Tester của app và app chưa có xác minh doanh nghiệp (Business Verification).
4. **Kênh gặp lỗi phân quyền vĩnh viễn (`PermanentError`):** Người dùng chưa cấp đủ quyền hoặc kênh chưa hỗ trợ endpoint đăng trực tiếp.

### 4.2. Cơ chế giải pháp "Đã bàn giao" (`HANDED_OFF`)
Khớp với `PostStatus.HANDED_OFF` trong `core/model/Post.kt` (yêu cầu F5 trong `requirements.md`):
```
PostStatus: DRAFT → QUEUED → PUBLISHING → HANDED_OFF
```
Thay vì xem trường hợp không gọi được API là lỗi thất bại (`PostStatus.FAILED`), hệ thống chuyển sang luồng **Bàn giao quyền đăng bài cho người dùng** thông qua Android OS native capabilities:

1. **Sao chép nội dung vào Clipboard:** Tự động sao chép toàn bộ text bài viết (kèm hashtag và link nếu có) vào `android.content.ClipboardManager`.
2. **Chia sẻ qua Android Share Intent (`Intent.ACTION_SEND`):**
   - Hỗ trợ cả text và file ảnh (thông qua `androidx.core.content.FileProvider` để cấp quyền đọc Uri tạm thời):
   ```kotlin
   val shareIntent = Intent(Intent.ACTION_SEND).apply {
       type = if (draft.imageUri != null) "image/*" else "text/plain"
       putExtra(Intent.EXTRA_TEXT, draft.text)
       draft.imageUri?.let { uriStr ->
          putExtra(Intent.EXTRA_STREAM, Uri.parse(uriStr))
       }
       addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
   }
   val chooser = Intent.createChooser(shareIntent, context.getString(R.string.share_handoff_title))
   context.startActivity(chooser)
   ```
3. **Cập nhật trạng thái & Phản hồi UX:** 
   - Đánh dấu trạng thái bài viết là `PostStatus.HANDED_OFF` trong Room Database.
   - Hiển thị Snackbar hoặc Dialog hướng dẫn: *"Nội dung bài viết đã được sao chép vào bộ nhớ tạm. Hãy dán vào ứng dụng mạng xã hội để hoàn tất đăng bài."*
   - Giữ nguyên thông tin bài đăng, cho phép người dùng xem lại lịch sử các bài đã bàn giao.

---

## 5. Trạng thái Đăng ký App trên Developer Portals & Kiến trúc Bảo mật Secret

> [!CAUTION]
> **Nguyên tắc bảo mật sống còn (RFC 8252 — OAuth 2.0 for Native Apps):**
> Ứng dụng Android là **Public Client** (khách công khai). Mã nguồn và tài nguyên sau khi đóng gói thành file APK đều có thể bị dịch ngược (decompiled bằng `jadx`, `apktool`, lệnh `strings`). Việc đưa `client_secret` từ `local.properties` nạp vào `BuildConfig` **vẫn làm lộ bí mật trong mã bytecode của file APK**. File `.gitignore` chỉ bảo vệ bí mật không bị commit lên GitHub, hoàn toàn **KHÔNG bảo vệ được bản build APK** khi phân phối hoặc khi file APK rơi vào tay kẻ xấu.
>
> **Do đó:** Tuyệt đối **KHÔNG nhúng Client Secret / App Secret vào app Android** dưới bất kỳ hình thức nào.

### 5.1. Meta for Developers (Facebook)
- **Tên App:** `SocialAgent-Dev`
- **Loại App (App Type):** `Business`
- **Trạng thái:** `In Development` (Chế độ phát triển)
- **App ID (Public Identifier):** Đã tạo và cấu hình Android Platform:
  - Package Name: `com.lttbdd.socialagent`
  - Key Hash: Tạo từ debug keystore SHA-1 fingerprint.
- **Cơ chế xác thực không dùng App Secret:**
  - Meta xác thực ứng dụng Android thông qua sự kết hợp giữa **App ID + Package Name + Android Key Hash** (chữ ký số của ứng dụng).
  - Trao đổi token bằng PKCE (`code_verifier`), không dùng `app_secret`.
  - App chỉ gọi các endpoint công khai và kiểm tra token qua `GET /me` (không gọi các endpoint quản trị `/debug_token` đòi hỏi secret).
- **Thành viên có quyền thử nghiệm:** Thêm tài khoản Facebook của 4 thành viên nhóm vào danh sách **Developers / Testers** trong mục *App Roles*.

### 5.2. LinkedIn Developers
- **Tên App:** `SocialAgent App`
- **Trạng thái:** `Development`
- **Sản phẩm đã kích hoạt (Products):**
  - `Sign In with LinkedIn using OpenID Connect` (cho F1)
  - `Share on LinkedIn` (cho F5 đăng bài cá nhân)
  - Đang đăng ký thêm: `Community Management API` (cho F6/F7 trên LinkedIn Page).
- **Client ID (Public Identifier):** Cấu hình Client ID công khai.
- **Trạng thái kích hoạt giao thức Native PKCE:**
  - Nhóm gửi ticket đến LinkedIn Developer Support để yêu cầu bật cờ giao thức **Native PKCE Protocol** cho App ID.
  - Redirect URLs đã đăng ký trên Portal:
    - Cho Luồng Native PKCE Loopback: `http://127.0.0.1:{port}/callback` (hoặc port cố định trong dải dev).
    - Cho Luồng Standard qua BFF: `com.lttbdd.socialagent://oauth/linkedin`.

### 5.3. Hướng dẫn Cấu hình Định danh Cục bộ (Public Client IDs Configuration)
Thay vì lưu `client_secret`, ứng dụng chỉ quản lý các **Public Client IDs**:

1. **Khai báo trong `local.properties` (hoặc cấu hình qua biến môi trường / Gradle properties):**
   ```properties
   # Public Client Identifiers (Không phải bí mật, nhưng tách ra để linh hoạt môi trường dev/staging)
   FACEBOOK_APP_ID=your_facebook_app_id_here
   LINKEDIN_CLIENT_ID=your_linkedin_client_id_here
   ```
2. **Cơ chế nạp vào `BuildConfig`:**
   - Khi triển khai F1, `app/build.gradle.kts` nạp `FACEBOOK_APP_ID` và `LINKEDIN_CLIENT_ID` vào `BuildConfig` (`buildFeatures.buildConfig = true`).
   - Có cơ chế fallback giá trị mặc định khi chạy trên CI GitHub Actions để lệnh `./gradlew testDebugUnitTest` và `./gradlew assembleDebug` không bị lỗi compile.
3. **Bảo mật Token phía Client (Bảo vệ dữ liệu người dùng):**
   - Access token của người dùng sau khi xác thực thành công chỉ được lưu trữ trên bộ nhớ máy bằng `EncryptedSharedPreferences` được mã hóa bởi phần cứng thông qua Android Keystore (`MasterKey.DEFAULT_AES256_GCM_SPEC`).
   - Không ghi log token ra Logcat, không đính kèm token trong thông báo lỗi hoặc crash report.
   - Khi người dùng bấm "Ngắt kết nối" (Disconnect) ở F1: Xóa sạch token khỏi Keystore/Room DB.

---

## 6. Tóm tắt nội dung dùng cho Slide Báo cáo (Research Presentation)

Dưới đây là dàn ý nội dung được thiết kế sẵn theo từng slide (kèm gợi ý hình ảnh/sơ đồ và lời thuyết trình) giúp nhóm dễ dàng đưa vào PowerPoint / Google Slides:

### 🎯 Slide 1: So sánh khả năng kênh & Đối chiếu Kiến trúc (Channel Capabilities)
* **Ý chính trình chiếu:**
  * Đối chiếu trực tiếp với `enum class ChannelCapability`: `PUBLISH`, `READ_COMMENTS`, `REPLY`, `MENTION`, `METRICS`.
  * **Facebook Page:** Hỗ trợ toàn diện 5 capabilities dưới tư cách Page; không hỗ trợ native Idempotency $\rightarrow$ Quản lý qua Room DB cục bộ.
  * **LinkedIn Organization:** Hỗ trợ đầy đủ cho Company Page; hỗ trợ native header `X-RestLi-Idempotency-Key`.
  * **LinkedIn Member (Cá nhân):** Chỉ hỗ trợ đăng bài cơ bản; quyền đọc/trả lời comment không khả dụng với quyền hiện có của app.
* **Gợi ý Visual:** Bảng ma trận so sánh 3 cột (Facebook Page | LinkedIn Org | LinkedIn Member) với các icon tick xanh ✅ và cảnh báo ⚠️.

---

### 🔐 Slide 2: Kiến trúc Xác thực An toàn — Public Client Không Giữ Secret
* **Ý chính trình chiếu:**
  * **Chuẩn bảo mật Native (RFC 8252):** Ứng dụng Android là Public Client $\rightarrow$ Loại bỏ hoàn toàn `client_secret` khỏi APK và mã nguồn (tránh nguy cơ dịch ngược APK).
  * **Cơ chế bảo vệ mã ủy quyền:** Tích hợp `AppAuth-Android` qua Android Custom Tabs với PKCE S256 (`code_verifier` + `code_challenge`).
  * **Định danh ứng dụng an toàn:**
    * *Facebook:* App ID công khai + Xác thực Android Key Hash (SHA-1) trên Meta Portal.
    * *LinkedIn:* Client ID công khai + luồng Native PKCE (`/oauth/native-pkce/`) qua Loopback Redirect không giữ secret (hoặc qua BFF).
  * **Vòng đời Token:** 
    * *Facebook:* Đổi User Token lấy Page Access Token vĩnh viễn (Never Expires).
    * *LinkedIn:* Access Token 60 ngày $\rightarrow$ Luồng Re-authentication 1 chạm khi hết hạn (kèm cơ chế Auto-refresh 365 ngày nếu được duyệt quyền Programmatic Refresh).
* **Gợi ý Visual:** Sơ đồ luồng OAuth 2.0 PKCE không `client_secret` đối chiếu giữa Custom Tabs và Token Endpoint.

---

### ⚙️ Slide 3: Endpoints Cốt lõi & Giới hạn Kỹ thuật (Technical Specs)
* **Ý chính trình chiếu:**
  * **Đăng bài có ảnh (F5):**
    * *Facebook:* 1 bước qua `POST /{page-id}/photos` (`multipart/form-data`).
    * *LinkedIn:* 2 bước qua `initializeUpload` $\rightarrow$ PUT binary $\rightarrow$ Đăng bài với Image URN.
  * **Tương tác & Số liệu (F6, F7):** Đọc comment phân cấp, reply theo ID/URN, lấy insights và follower count.
  * **Giới hạn kiểm soát:**
    * *Độ dài bài:* Facebook (63.206 ký tự) vs LinkedIn (3.000 ký tự) $\rightarrow$ Đã cấu hình cứng trong `ChannelType`.
    * *Kích thước ảnh:* Facebook (4 MB multipart) vs LinkedIn (8 MB).
    * *Rate limit:* Facebook (4.800 req/Page/giờ) vs LinkedIn Community Management Dev Tier (500 req/app, 100 req/member/ngày UTC).
* **Gợi ý Visual:** Bảng so sánh 2 cột trực quan các chỉ số dung lượng, ký tự và Rate Limit.

---

### 🛡️ Slide 4: Rào cản Chính sách & Giải pháp "Đã bàn giao" (`HANDED_OFF`)
* **Ý chính trình chiếu:**
  * **Rào cản API bất khả kháng:**
    1. Meta cấm bên thứ ba đăng bài lên Facebook cá nhân (đóng quyền `publish_actions` từ 2018).
    2. Meta cấm mention profile cá nhân (LinkedIn hỗ trợ mention member khi có URN hợp lệ: `@[Tên](urn:li:person:{id})`).
    3. Rào cản kiểm duyệt App Review khắt khe (đòi hỏi giấy phép kinh doanh).
  * **Giải pháp 1 (Nội bộ):** Chạy ứng dụng ở **Development Mode** $\rightarrow$ Dùng đủ 100% mọi quyền nâng cao cho các tài khoản Tester/Developer trong nhóm.
  * **Giải pháp 2 (Dự phòng F5 - `HANDED_OFF`):** 
    * Khi không thể gọi API: Tự động copy text vào Clipboard $\rightarrow$ Kích hoạt Android Share Intent native $\rightarrow$ Cập nhật trạng thái `PostStatus.HANDED_OFF`.
    * Đảm bảo luồng trải nghiệm không bao giờ đứt gãy.
* **Gợi ý Visual:** Sơ đồ rẽ nhánh: [Agent soạn bài] $\rightarrow$ [Đủ quyền API? $\rightarrow$ Gọi API] HOẶC [Thiếu quyền $\rightarrow$ Copy Clipboard + Mở Share Sheet $\rightarrow$ Badge HANDED_OFF].

---

### 🚀 Slide 5: Kiến nghị Contract & Lộ trình Milestone 1
* **Ý chính trình chiếu:**
  * **Kiến nghị Contract (`SocialChannelClient`):** Đề xuất mở Issue nhãn `contract` để bổ sung `SocialChannelClientFactory` giải quyết ngữ cảnh đa tài khoản/đa Page (`accountId`).
  * **Kế hoạch triển khai:**
    * *Tuần 1:* Họp nhóm chốt contract interface `SocialChannelClientFactory` & hoàn thiện cấu hình Developer Portals.
    * *Tuần 2:* Triển khai thư viện AppAuth, màn hình "Kênh của tôi" (F1) và tích hợp Room DB lưu trữ token an toàn (Keystore).
* **Gợi ý Visual:** Timeline lộ trình thực hiện theo tuần của Milestone 1.
