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
| **`READ_COMMENTS` (Đọc comment)** | ✅ **Có** (Comment cấp 1, reply cấp 2, paging limit/offset) | ✅ **Có** (Đọc comment và reaction qua Community Management API) | ❌ **Không** (LinkedIn chặn API đọc comment trên trang cá nhân) |
| **`REPLY` (Trả lời comment)** | ✅ **Có** (Gửi reply comment dưới tư cách Page) | ✅ **Có** (Gửi reply comment dưới tư cách Organization URN) | ❌ **Không** (Hạn chế theo quyền đọc comment) |
| **`MENTION` (Nhắc tên)** | ⚠️ **Một phần** (Chỉ mention được các **Page khác** qua cú pháp `@[page_id]`; Meta cấm mention trang cá nhân) | ⚠️ **Một phần** (Mention được Org URN và Profile URN có kết nối; API tìm kiếm bị hạn chế quyền đối tác) | ⚠️ **Một phần** (Chèn URN khi đăng bài cá nhân) |
| **`METRICS` (Số liệu)** | ✅ **Có** (Reactions, Comments, Shares, Impressions từ Insights) | ✅ **Có** (Likes, Comments, Shares, Impressions, Clicks, Engagement Rate) | ⚠️ **Hạn chế** (Chỉ số liệu tương tác bề mặt cơ bản) |
| **`FOLLOWER_COUNT`** | ✅ `followers_count` & `fan_count` qua `/{page-id}` | ✅ `firstDegreeSize` qua `/networkSizes` | ⚠️ Lấy qua `connectionsSummary` (yêu cầu quyền) |
| **Idempotency native** | ❌ Không hỗ trợ native (App tự quản lý qua Room DB) | ✅ Có hỗ trợ qua header `X-RestLi-Idempotency-Key` | ✅ Có hỗ trợ qua header `X-RestLi-Idempotency-Key` |
| **Tập hợp gán cho `ChannelAccount.capabilities` (F1)** | `setOf(PUBLISH, READ_COMMENTS, REPLY, MENTION, METRICS)` | `setOf(PUBLISH, READ_COMMENTS, REPLY, MENTION, METRICS)` | `setOf(PUBLISH, MENTION)` |

---

## 2. Kênh Facebook Page (Meta Graph API)

### 2.1. Luồng OAuth 2.0 trên Android & Quản lý Token
- **Thư viện đề xuất:** `net.openid:appauth:0.11.1` (AppAuth-Android) chạy trên Android Custom Tabs (trình duyệt hệ thống) đảm bảo tuân thủ RFC 8252 (OAuth 2.0 for Native Apps).
- **Cơ chế bảo mật:** PKCE (Proof Key for Code Exchange) với `code_challenge_method = S256`.
- **Endpoints:**
  - Authorization Endpoint: `https://www.facebook.com/v20.0/dialog/oauth`
  - Token Endpoint: `https://graph.facebook.com/v20.0/oauth/access_token`
  - Redirect URI: `com.lttbdd.socialagent://oauth/facebook` (hoặc `fb<APP_ID>://authorize`)

#### Vòng đời Token (Token Lifecycle):
```
[User Login trên Custom Tabs]
       │
       ▼ (Trả authorization code)
[App đổi lấy User Short-lived Access Token] (~1 - 2 giờ)
       │
       ▼ (Gọi /oauth/access_token?grant_type=fb_exchange_token)
[User Long-lived Access Token] (~60 ngày)
       │
       ▼ (Gọi GET /me/accounts với User Long-lived Token)
[Page Access Token (Long-lived / Never-expiring)]
```
* **Page Access Token:** Khi sinh ra từ một *User Long-lived Token*, Page Access Token này **không có thời hạn hết hạn (never expires)**, trừ khi:
  1. Người dùng đổi mật khẩu tài khoản Facebook.
  2. Người dùng gỡ quyền của App trong trang Cài đặt bảo mật Facebook.
  3. Người dùng bị xóa vai trò quản trị viên (Admin/Editor) khỏi Page.
* **Cơ chế kiểm tra & làm mới (F1):**
  - Định kỳ hoặc khi gặp lỗi `OAuthException` (mã lỗi `190` - Invalid/Expired Token), app gọi `GET /debug_token?input_token={token}&access_token={app_id}|{app_secret}`.
  - Nếu token hết hạn hoặc mất quyền: chuyển trạng thái kênh sang `ConnectionStatus.TOKEN_EXPIRED`, thông báo người dùng kết nối lại.

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
- **Thư viện đề xuất:** `net.openid:appauth:0.11.1` (Custom Tabs) với PKCE.
- **Endpoints:**
  - Authorization Endpoint: `https://www.linkedin.com/oauth/v2/authorization`
  - Token Endpoint: `https://www.linkedin.com/oauth/v2/accessToken`
  - Redirect URI: `com.lttbdd.socialagent://oauth/linkedin`

#### Vòng đời Token:
- **Access Token:** Có thời hạn **60 ngày**.
- **Refresh Token:** Có thời hạn **365 ngày** (khi cấp quyền với scope `offline_access`).
- **Cơ chế tự động làm mới (Auto Refresh):**
  - Gửi request `POST https://www.linkedin.com/oauth/v2/accessToken`:
    ```
    grant_type=refresh_token
    &refresh_token={refresh_token}
    &client_id={client_id}
    &client_secret={client_secret}
    ```
  - App thực hiện refresh ngầm trước khi token hết hạn 7 ngày hoặc khi nhận mã HTTP `401 Unauthorized`.

### 3.2. Danh sách quyền (Permissions / Scopes)
Đối chiếu trực tiếp với các khả năng trong `ChannelCapability` (`core/model/Channel.kt`):

| Chức năng / Khả năng | Scope cần xin | Mục đích & Chi tiết kỹ thuật |
|---|---|---|
| **F1 (Kết nối & Quản trị)** | `openid`, `profile`, `r_organization_admin` | Xác thực đăng nhập qua OpenID Connect và kiểm tra vai trò quản trị viên (Admin) trên Page công ty. |
| **`PUBLISH` (Đăng bài)** | `w_organization_social` *(Page)*<br>`w_member_social` *(Cá nhân)* | Đăng bài viết (text, link, media URN) lên Organization Page hoặc trang cá nhân của thành viên. |
| **`READ_COMMENTS` (Đọc comment)** | `r_organization_social` | Đọc danh sách bình luận, các reply phân cấp và reaction trên bài viết của Organization Page (`GET /socialActions/{targetUrn}/comments`). |
| **`REPLY` (Trả lời comment)** | `w_organization_social` | Gửi bình luận trả lời (`POST /socialActions/{targetUrn}/comments`) dưới danh nghĩa `urn:li:organization:{org-id}`. |
| **`MENTION` (Nhắc tên)** | `w_organization_social` *(kèm URN)* | Chèn cú pháp mention `@urn:li:organization:{id}` hoặc `@urn:li:person:{id}` vào nội dung bài đăng. **Lưu ý:** LinkedIn không cung cấp API tìm kiếm URN tự do cho ứng dụng thông thường (cần Advertising/Partner tier). |
| **`METRICS` (Số liệu)** | `r_organization_social` | Đọc số liệu tương tác (`likeCount`, `commentCount`, `shareCount`, `impressionCount`, `clickCount`) qua `organizationalEntityShareStatistics` và số người theo dõi (`firstDegreeSize`) qua `networkSizes`. |
| **Làm mới Token (F1)** | `offline_access` | Cấp refresh token có thời hạn 365 ngày để kích hoạt cơ chế tự động làm mới ngầm. |

### 3.3. Các Endpoint chính (LinkedIn Versioned API `202401`+)
1. **Lấy thông tin người dùng / URN cá nhân (F1):**
   ```http
   GET https://api.linkedin.com/v2/userinfo
   Authorization: Bearer {access-token}
   ```
2. **Khởi tạo và upload ảnh (F5 - Hai bước):**
   - Bước 1: Khởi tạo upload ảnh
     ```http
     POST https://api.linkedin.com/rest/images?action=initializeUpload
     Headers: LinkedIn-Version: 202401, X-Restli-Protocol-Version: 2.0.0

     {
       "initializeUploadRequest": {
         "owner": "urn:li:organization:{org-id}"
       }
     }
     ```
     *Nhận về:* `uploadUrl` và `image` (URN: `urn:li:image:{image-id}`).
   - Bước 2: Tải file binary ảnh lên `uploadUrl` bằng HTTP PUT.
3. **Đăng bài viết (F5 - Hỗ trợ Idempotency native):**
   ```http
   POST https://api.linkedin.com/rest/posts
   Headers:
     Authorization: Bearer {access-token}
     LinkedIn-Version: 202401
     X-RestLi-Idempotency-Key: {idempotencyKey}
     Content-Type: application/json

   {
     "author": "urn:li:organization:{org-id}",
     "commentary": "Nội dung bài viết...",
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
   ```http
   GET https://api.linkedin.com/rest/socialActions/{targetUrn}/comments
   Headers: LinkedIn-Version: 202401
   ```
5. **Trả lời comment (F6):**
   ```http
   POST https://api.linkedin.com/rest/socialActions/{targetUrn}/comments
   Headers: LinkedIn-Version: 202401

   {
     "actor": "urn:li:organization:{org-id}",
     "message": { "text": "Cảm ơn sự đóng góp của bạn!" },
     "parentComment": "{parentCommentUrn}"
   }
   ```
6. **Lấy số liệu bài viết (F7):**
   ```http
   GET https://api.linkedin.com/rest/organizationalEntityShareStatistics?q=organizationalEntity&organizationalEntity=urn:li:organization:{org-id}&shares=List({postUrn})
   Headers: LinkedIn-Version: 202401
   ```
   *Trả về:* `likeCount`, `commentCount`, `shareCount`, `impressionCount`, `clickCount`.
7. **Lấy số follower (F7):**
   ```http
   GET https://api.linkedin.com/rest/networkSizes/urn:li:organization:{org-id}?edgeType=CompanyFollowedByMember
   Headers: LinkedIn-Version: 202401
   ```
   *Trả về:* `firstDegreeSize`.

### 3.4. Giới hạn & Quy định kiểm duyệt
- **Độ dài bài viết tối đa:** `3,000` ký tự (khớp cấu hình trong `ChannelType.LINKEDIN(maxChars = 3_000)`).
- **Kích thước ảnh & Định dạng:**
  - Tối đa **8 MB** cho ảnh bài đăng thông thường.
  - Tỉ lệ khuyến nghị: `1.91:1` (ngang: 1200 x 627 px) hoặc `1:1` (vuông: 1200 x 1200 px). Định dạng: PNG, JPEG, GIF.
- **Rate Limit (Giới hạn tần suất gọi):**
  - Giới hạn tác giả: $100$ bài đăng / mỗi tác giả (Member hoặc Organization) / ngày.
  - Giới hạn ứng dụng (Application Throttle): $100.000$ request / ngày toàn ứng dụng; các endpoint đọc dữ liệu có ngưỡng 24h riêng.
  - Khi dính throttle: Trả về HTTP `429 Too Many Requests` (kèm header `Retry-After`) $\rightarrow$ Map sang `PublishResult.RetryableError`.
- **Quyền nào cần App Review / Phê duyệt Sản phẩm (Products):**
  - *Sản phẩm Share on LinkedIn & Sign In with OpenID Connect (Không cần duyệt phức tạp):* Cấp tự động các quyền cơ bản `openid`, `profile`, `w_member_social`. Cho phép đăng bài lên trang cá nhân ngay lập tức.
  - *Sản phẩm Community Management API (Cần đăng ký & duyệt):* Bắt buộc để cấp các quyền trên Page công ty: `w_organization_social`, `r_organization_social`, `r_organization_admin`.
- **Chế độ Development dùng được tới đâu:**
  - **Phạm vi hoạt động:** Cho phép đăng bài lên trang cá nhân của các Developer/Tester trong nhóm và tương tác với các Organization Page thử nghiệm mà thành viên nắm quyền Quản trị (Admin).
  - **Dự phòng rào cản kiểm duyệt:** Nếu LinkedIn chưa kịp cấp quyền Community Management API cho Organization Page, app tự động kích hoạt luồng **`HANDED_OFF`** (Mục 4) để copy bài và mở Android Share Intent, đảm bảo luồng trải nghiệm không bao giờ bị đứt gãy.

---

## 4. Chức năng không làm được qua API & Đề xuất "Đã bàn giao" (`HANDED_OFF`)

### 4.1. Các hạn chế bất khả kháng từ chính sách API
1. **Đăng bài lên Facebook cá nhân (Personal Profile Timeline):** Meta đã đóng hoàn toàn quyền `publish_actions` từ năm 2018. Không ứng dụng bên thứ ba nào được phép đăng bài trực tiếp lên trang cá nhân của người dùng qua API (chỉ cho phép đăng lên Page).
2. **Mention người dùng cá nhân (Tag Facebook user / LinkedIn member):** Cả hai nền tảng đều chặn việc app tự ý tag tên tài khoản cá nhân qua API để chống spam và bảo vệ quyền riêng tư (chỉ cho phép mention Page/Organization).
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
`           putExtra(Intent.EXTRA_STREAM, Uri.parse(uriStr))
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

## 5. Trạng thái Đăng ký App trên Developer Portals

> **Nguyên tắc bảo mật:** Tuyệt đối **KHÔNG commit Client Secret / App Secret** lên GitHub. Khóa bí mật chỉ được lưu trong `local.properties` (được bảo vệ bởi `.gitignore`) và nạp vào qua `BuildConfig`.

### 5.1. Meta for Developers (Facebook)
- **Tên App:** `SocialAgent-Dev`
- **Loại App (App Type):** `Business`
- **Trạng thái:** `In Development` (Chế độ phát triển)
- **App ID:** Đã tạo và cấu hình Android Platform:
  - Package Name: `com.lttbdd.socialagent`
  - Key Hash: Tạo từ debug keystore SHA-1.
- **Thành viên có quyền thử nghiệm:** Thêm tài khoản Facebook của 4 thành viên nhóm vào danh sách **Developers / Testers** trong mục *App Roles*.

### 5.2. LinkedIn Developers
- **Tên App:** `SocialAgent App`
- **Trạng thái:** `Development`
- **Sản phẩm đã kích hoạt (Products):**
  - `Sign In with LinkedIn using OpenID Connect` (cho F1)
  - `Share on LinkedIn` (cho F5 đăng bài cá nhân)
  - Đang đăng ký thêm: `Community Management API` (cho F6/F7 trên LinkedIn Page).
- **Redirect URLs:** Đã cấu hình `com.lttbdd.socialagent://oauth/linkedin`.

### 5.3. Hướng dẫn cấu hình Secret cục bộ (Local Secrets Template)
Để các thành viên trong nhóm phát triển có thể chạy và test app trên máy cá nhân mà không vi phạm nguyên tắc bảo mật:

1. **Khai báo trong `local.properties` trên máy cá nhân (file đã được `.gitignore` bảo vệ):**
   ```properties
   # Meta (Facebook) Credentials
   FACEBOOK_APP_ID=your_facebook_app_id_here
   FACEBOOK_CLIENT_SECRET=your_facebook_client_secret_here

   # LinkedIn Credentials
   LINKEDIN_CLIENT_ID=your_linkedin_client_id_here
   LINKEDIN_CLIENT_SECRET=your_linkedin_client_secret_here
   ```
2. **Cơ chế nạp vào `BuildConfig`:**
   - Khi bắt đầu code F1, `app/build.gradle.kts` sẽ nạp các biến này từ `local.properties` vào `BuildConfig` (`buildFeatures.buildConfig = true`).
   - Có cơ chế fallback giá trị rỗng (`""`) khi chạy trên CI GitHub Actions để lệnh `./gradlew testDebugUnitTest` và `./gradlew assembleDebug` không bị lỗi compile.
3. **Quy tắc bất biến ([AGENTS.md](AGENTS.md)):**
   - Tuyệt đối **không đưa App Secret vào mã nguồn Kotlin/XML**, không commit file keystore/properties lên GitHub.
   - Access token của người dùng sau khi đăng nhập được mã hóa lưu trữ qua Android Keystore / EncryptedSharedPreferences (MasterKey).

---

## 6. Tóm tắt nội dung dùng cho Slide Báo cáo (Research Presentation)

Dưới đây là dàn ý nội dung được thiết kế sẵn theo từng slide (kèm gợi ý hình ảnh/sơ đồ và lời thuyết trình) giúp nhóm dễ dàng đưa vào PowerPoint / Google Slides:

### 🎯 Slide 1: So sánh khả năng kênh & Đối chiếu Kiến trúc (Channel Capabilities)
* **Ý chính trình chiếu:**
  * Đối chiếu trực tiếp với `enum class ChannelCapability`: `PUBLISH`, `READ_COMMENTS`, `REPLY`, `MENTION`, `METRICS`.
  * **Facebook Page:** Hỗ trợ toàn diện 5 capabilities dưới tư cách Page; không hỗ trợ native Idempotency $\rightarrow$ Quản lý qua Room DB cục bộ.
  * **LinkedIn Organization:** Hỗ trợ đầy đủ cho Company Page; hỗ trợ native header `X-RestLi-Idempotency-Key`.
  * **LinkedIn Member (Cá nhân):** Chỉ hỗ trợ đăng bài cơ bản; bị API hạn chế quyền đọc/trả lời comment.
* **Gợi ý Visual:** Bảng ma trận so sánh 3 cột (Facebook Page | LinkedIn Org | LinkedIn Member) với các icon tick xanh ✅ và cảnh báo ⚠️.

---

### 🔐 Slide 2: Kiến trúc Xác thực OAuth 2.0 PKCE & Vòng đời Token
* **Ý chính trình chiếu:**
  * **Chuẩn bảo mật Native:** Tích hợp `AppAuth-Android` qua Android Custom Tabs (RFC 8252), loại bỏ hoàn toàn WebView nhúng.
  * **Bảo vệ mã ủy quyền:** Cơ chế PKCE với `code_challenge_method = S256`.
  * **Vòng đời Facebook Page Token:** Đổi User Short-lived (1–2h) $\rightarrow$ Long-lived (60d) $\rightarrow$ **Page Access Token vĩnh viễn (Never Expires)**.
  * **Vòng đời LinkedIn Token:** Access Token 60 ngày + Tự động làm mới ngầm bằng Refresh Token 365 ngày (`offline_access`).
* **Gợi ý Visual:** Sơ đồ luồng mũi tên 3 bước đổi token của Facebook và luồng Auto-refresh ngầm của LinkedIn trước 7 ngày.

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
    * *Rate limit:* Facebook (4.800 req/Page/giờ) vs LinkedIn (100 bài/ngày, 100k req/toàn app).
* **Gợi ý Visual:** Bảng so sánh 2 cột trực quan các chỉ số dung lượng, ký tự và Rate Limit.

---

### 🛡️ Slide 4: Rào cản Chính sách & Giải pháp "Đã bàn giao" (`HANDED_OFF`)
* **Ý chính trình chiếu:**
  * **Rào cản API bất khả kháng:**
    1. Meta cấm bên thứ ba đăng bài lên Facebook cá nhân (đóng quyền `publish_actions` từ 2018).
    2. Cả 2 nền tảng cấm API tự ý mention tài khoản cá nhân để chống spam.
    3. Rào cản kiểm duyệt App Review khắt khe (đòi hỏi giấy phép kinh doanh).
  * **Giải pháp 1 (Nội bộ):** Chạy ứng dụng ở **Development Mode** $\rightarrow$ Dùng đủ 100% mọi quyền nâng cao cho các tài khoản Tester/Developer trong nhóm.
  * **Giải pháp 2 (Dự phòng F5 - `HANDED_OFF`):** 
    * Khi không thể gọi API: Tự động copy text vào Clipboard $\rightarrow$ Kích hoạt Android Share Intent native $\rightarrow$ Cập nhật trạng thái `PostStatus.HANDED_OFF`.
    * Đảm bảo luồng trải nghiệm không bao giờ đứt gãy.
* **Gợi ý Visual:** Sơ đồ rẽ nhánh: [Agent soạn bài] $\rightarrow$ [Đủ quyền API? $\rightarrow$ Gọi API] HOẶC [Thiếu quyền $\rightarrow$ Copy Clipboard + Mở Share Sheet $\rightarrow$ Badge HANDED_OFF].

---

### 🚀 Slide 5: Kiến nghị Contract & Lộ trình Milestone 1
* **Ý chính trình chiếu:**
  * **Kiến nghị Contract (`SocialChannelClient`):** Đề xuất mở Issue nhãn `contract` để giải quyết ngữ cảnh đa tài khoản/đa Page (`accountId`) bằng mô hình Factory (`SocialChannelClientFactory`).
  * **Kế hoạch triển khai:**
    * *Tuần 1:* Họp nhóm chốt contract interface `SocialChannelClient` & hoàn thiện cấu hình Developer Portals.
    * *Tuần 2:* Triển khai thư viện AppAuth, màn hình "Kênh của tôi" (F1) và tích hợp Room DB lưu trữ token an toàn.
* **Gợi ý Visual:** Timeline lộ trình thực hiện theo tuần của Milestone 1.

