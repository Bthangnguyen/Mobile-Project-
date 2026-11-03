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

> *Ghi chú về định nghĩa:* `ChannelCapability` bao gồm 5 giá trị: `PUBLISH`, `READ_COMMENTS`, `REPLY`, `MENTION`, `METRICS`. Số lượng người theo dõi (`followersCount`) là trường dữ liệu được gán kèm trong thông tin tài khoản hoặc truy vấn qua `METRICS`, không phải một hằng số enum riêng biệt.

| Khả năng (`ChannelCapability`) | Facebook Page (Meta Graph API v26.0) | LinkedIn Organization (Company Page) | LinkedIn Member (Trang cá nhân) |
|---|---|---|---|
| **`PUBLISH` (Đăng bài)** | ✅ **Có** (Text, link, ảnh đơn qua `/{page-id}/photos`, đa ảnh qua `attached_media`) | ✅ **Có** (Text, link, ảnh đơn & đa ảnh `multiImage` [tối thiểu 2 ảnh, tối đa 20 ảnh] qua Posts API) | ✅ **Có** (Text, link, ảnh đơn qua Share on LinkedIn) |
| **`READ_COMMENTS` (Đọc comment)** | ✅ **Có** (Comment cấp 1, reply cấp 2, paging limit/offset) | ✅ **Có** (Đọc comment và reaction qua Community Management API) | ❌ **Không** (Không khả dụng với quyền hiện có của app; quyền đọc comment của member bị hạn chế) |
| **`REPLY` (Trả lời comment)** | ✅ **Có** (Gửi reply comment dưới tư cách Page) | ✅ **Có** (Gửi reply comment dưới tư cách Organization URN) | ❌ **Không** (Không khả dụng với quyền hiện có của app; phụ thuộc quyền đọc comment) |
| **`MENTION` (Nhắc tên)** | ⚠️ **Một phần (Chưa xác minh)** (Meta cấm mention cá nhân; cú pháp `@[page_id]` cho Page khác chưa được Meta xác nhận chính thức, cần kiểm thử thực tế) | ✅ **Có** (Hỗ trợ mention cả Organization `@[Tên Org](urn:li:organization:{id})` và Member `@[Tên](urn:li:person:{id})` khi có URN hợp lệ) | ✅ **Có** (Hỗ trợ chèn Organization và Member URN tương tự khi đăng bài cá nhân) |
| **`METRICS` (Số liệu)** | ✅ **Có** (Reactions, Comments, Shares, Impressions từ Insights) | ✅ **Có** (Likes, Comments, Shares, Impressions, Clicks, Engagement Rate) | ⚠️ **Hạn chế** (Chỉ số liệu tương tác bề mặt cơ bản) |
| **Số follower (`followersCount`)** *(Dữ liệu kèm `METRICS`)* | ✅ `followers_count` (chính thức) & `fan_count` (⚠️ nguy cơ legacy field bị deprecate trên v26.0) qua `/{page-id}` | ✅ `firstDegreeSize` qua `/networkSizes` | ❌ **Không hỗ trợ** (LinkedIn không mở API công khai đọc follower cá nhân; số kết nối không tương đương follower và API kết nối bị khóa cho Compliance) |
| **Idempotency native** | ❌ Không hỗ trợ native (App tự quản lý qua Room DB) | ✅ Có hỗ trợ qua header `X-RestLi-Idempotency-Key` | ✅ Có hỗ trợ qua header `X-RestLi-Idempotency-Key` |
| **Tập hợp gán cho `ChannelAccount.capabilities` (F1)** | `setOf(PUBLISH, READ_COMMENTS, REPLY, MENTION, METRICS)` | `setOf(PUBLISH, READ_COMMENTS, REPLY, MENTION, METRICS)` | `setOf(PUBLISH, MENTION)` |

---

## 2. Kênh Facebook Page (Meta Graph API)

> [!IMPORTANT]
> **Chính sách Phiên bản & Vòng đời Meta Graph API (Version Sunset Policy):**
> - Meta áp dụng chu kỳ phát hành phiên bản Graph API định kỳ khoảng 6 tháng một lần, mỗi phiên bản có thời gian hỗ trợ chính thức tối đa khoảng **2 năm (24 tháng)** trước khi bị ngừng hoạt động (sunset).
> - **Tình trạng v20.0:** Graph API `v20.0` (phát hành ngày 21/05/2024) đã chính thức **hết hạn (sunset) vào ngày 24/09/2026**. Sau ngày này, các request gửi tới `/v20.0/` sẽ bị Meta từ chối hoặc cưỡng chế nâng lên phiên bản mặc định còn hiệu lực của App.
> - **Phiên bản áp dụng chuẩn hóa:** Toàn bộ tài liệu được kiểm tra và đồng bộ vào ngày **03/11/2026** với phiên bản mới nhất đang Active là **`v26.0`** (phát hành ngày 29/07/2026).
> - **Thay đổi kỹ thuật quan trọng:** Các chỉ số insights cũ như `post_engaged_users` và `post_impressions` đã bị Meta **gỡ bỏ hoàn toàn trên mọi phiên bản Graph API** (theo Pages API Changelog, thay đổi này áp dụng toàn cục cho tất cả version, gây lỗi HTTP 400 Bad Request nếu cố gọi); hệ thống chuyển sang sử dụng metric thay thế chính thức **`post_media_view`** và các summary connection trực tiếp (`reactions.summary`, `comments.summary`, `shares`).
> - **Quy ước triển khai:** Trong mã nguồn Kotlin của Mảng B, version này cần được định nghĩa thành hằng số tập trung (ví dụ: `private const val FACEBOOK_GRAPH_API_VERSION = "v26.0"`) để dễ bảo trì và nâng cấp định kỳ.

### 2.1. Luồng OAuth 2.0 trên Android & Quản lý Token (Kiến trúc Public Client)

- **Chuẩn kiến trúc:** Tuân thủ RFC 8252 (OAuth 2.0 for Native Apps) — ứng dụng Android là **Public Client**, **tuyệt đối không nhúng `client_secret` (App Secret)** vào app hay APK.
- **Trạng thái xác minh của luồng Custom Tabs + PKCE thủ công:**
  > [!WARNING]
  > **Trạng thái: Chưa xác minh chính thức từ tài liệu Meta Graph API (Cần kiểm thử thực tế)**
  > - Theo tài liệu chính thức của Meta về [Manual Login Flow](https://developers.facebook.com/documentation/facebook-login/guides/advanced/manual-flow), endpoint đổi token `GET /oauth/access_token` tiêu chuẩn yêu cầu bắt buộc phải có `client_secret` bên cạnh `code`.
  > - Mặc dù thư viện `AppAuth-Android` hỗ trợ PKCE ở phía client, tài liệu Graph API hiện hành chưa xác nhận chính thức việc chấp nhận bỏ qua `client_secret` chỉ bằng `code_verifier` cho ứng dụng di động thông thường mà không có backend hỗ trợ.
  > - Do đó, nhóm phân tích rõ hai phương án triển khai khả thi dưới đây:

#### Phương án 1 (Khuyến nghị chuẩn của Meta): Sử dụng Meta Android SDK (`com.facebook.android:facebook-login`)
Đây là phương án được Meta hỗ trợ chính thức và an toàn nhất cho nền tảng Android:
- **Cơ chế xác thực không Secret:** Meta SDK không sử dụng `client_secret`. Thay vào đó, Meta xác thực danh tính ứng dụng thông qua **App ID + Package Name (`com.lttbdd.socialagent`) + Android Key Hash (SHA-1 fingerprint)** đã đăng ký trên Meta App Dashboard.
- **Trải nghiệm đăng nhập (UX):** Tự động kích hoạt App Switch (mở trực tiếp app Facebook đã cài sẵn trên máy) hoặc mở Chrome Custom Tabs nếu máy chưa cài app, giúp người dùng đăng nhập 1 chạm.
- **Tự động quản lý vòng đời Token (Đã xác minh từ tài liệu Meta):**
  - Theo tài liệu chính thức của Meta về [Authentication Versus Data Access](https://developers.facebook.com/documentation/facebook-login/auth-vs-data), khi ứng dụng sử dụng Facebook SDK cho Android, User Access Token mặc định có thời hạn sống **khoảng 60 ngày** và được SDK tự động làm mới ngầm (rolling refresh) mỗi khi người dùng mở ứng dụng.
  - Do trang tài liệu này được Meta cập nhật từ năm 2022, nhóm phát triển vẫn nên kiểm thử thực tế để kiểm chứng hành vi của phiên bản SDK hiện hành.

#### Phương án 2: Tự xây dựng luồng Web OAuth (AppAuth / Custom Tabs) kèm BFF

Nếu nhóm lựa chọn không nhúng Facebook SDK để giữ app nhẹ và dùng chung kiến trúc AppAuth với LinkedIn:

> [!WARNING]
> **Trạng thái xác minh của Redirect URI đối với Meta Web OAuth:**
> - **Luồng HTTPS Callback qua BFF (Khuyến nghị chuẩn):** Tuân thủ tài liệu chính thức về [Manual Login Flow](https://developers.facebook.com/documentation/facebook-login/guides/advanced/manual-flow), `redirect_uri` phải nằm trong danh sách *Valid OAuth Redirect URIs* (sử dụng giao thức chuẩn `https://`). Meta chuyển hướng về endpoint HTTPS của máy chủ backend/BFF, sau đó BFF chuyển hướng an toàn về Android App Links.
> - **Luồng Custom Scheme trực tiếp (`com.lttbdd.socialagent://...`):** ⚠️ **Theo quan sát thực tế, chưa xác minh chính thức từ tài liệu Meta**. Tài liệu Meta chưa xác nhận việc hỗ trợ đăng ký Custom Scheme trong danh sách *Valid OAuth Redirect URIs* cho Web Dialog (theo quan sát trên Dashboard, mục này yêu cầu URL HTTPS; custom scheme `fb<APP_ID>://authorize` chỉ được Meta Android SDK sử dụng trong cơ chế App Switch nội bộ).

##### Quy trình triển khai chuẩn qua BFF (Khuyến nghị):

1. **Khởi tạo ủy quyền & Đăng ký phiên với BFF (App-to-BFF PKCE):**
   - **Tạo bí mật chống chặn bắt Deep Link:** Trước khi mở trình duyệt, Android app sinh chuỗi ngẫu nhiên bảo mật `state` và cặp khóa PKCE nội bộ: `app_code_verifier` (chuỗi ngẫu nhiên bí mật có độ entropy cao, chỉ lưu trong bộ nhớ RAM của app) và `app_code_challenge = BASE64URL(SHA256(app_code_verifier))`.
   - **Khởi tạo phiên trên BFF qua endpoint `POST /auth/facebook/start`:**
     - Để server BFF biết lưu trữ và theo dõi `state` trước khi nhận callback từ Meta, app gửi request khởi tạo phiên:
     ```http
     POST https://api.yourdomain.com/auth/facebook/start
     Content-Type: application/json

     {
       "state": "{secure-random-state}",
       "code_challenge": "{app_code_challenge}"
     }
     ```
     - Server BFF lưu trữ cặp `(state, app_code_challenge)` vào bộ nhớ cache tạm thời (ví dụ Redis / memory store với TTL ngắn ~5–10 phút) để đối chiếu khi nhận callback.
   - **Mở Custom Tabs ủy quyền:** Sau khi phiên được khởi tạo trên BFF, App mở Android Custom Tabs điều hướng tới Meta Dialog OAuth, sử dụng **HTTPS Redirect URI** trỏ về máy chủ BFF:

   *Trường hợp A — Facebook Login cổ điển (Truyền danh sách `scope`):*
   ```http
   GET https://www.facebook.com/v26.0/dialog/oauth?
       client_id={app-id}
       &redirect_uri=https://api.yourdomain.com/auth/facebook/callback
       &response_type=code
       &scope=public_profile,pages_show_list,pages_manage_posts,pages_read_engagement,pages_manage_engagement,pages_read_user_content,read_insights
       &state={secure-random-state}
   ```

   *Trường hợp B — Facebook Login for Business (Truyền tham số `config_id`):*
   Theo tài liệu chính thức về [Facebook Login for Business](https://developers.facebook.com/docs/facebook-login/facebook-login-for-business/), đối với App loại `Business`, các quyền được cấu hình sẵn trong mục *Configurations* trên Dashboard tạo thành một Configuration ID (`config_id`). Khi đó, request thay thế toàn bộ tham số `scope` bằng `config_id`:
   ```http
   GET https://www.facebook.com/v26.0/dialog/oauth?
       client_id={app-id}
       &redirect_uri=https://api.yourdomain.com/auth/facebook/callback
       &response_type=code
       &config_id={facebook-config-id}
       &state={secure-random-state}
   ```

   > ⚠️ **Lưu ý kiểm tra Dashboard:** Nhóm cần kiểm tra trực tiếp trên Meta App Dashboard xem sản phẩm kích hoạt là **Facebook Login cổ điển** hay **Facebook Login for Business**:
   > - Ví dụ `scope=...` chỉ có hiệu lực với Facebook Login cổ điển.
   > - Nếu Dashboard yêu cầu dùng Facebook Login for Business, bắt buộc phải tạo Configuration và truyền tham số `config_id` thay cho danh sách `scope` (đối với Meta Android SDK, truyền Configuration ID theo cơ chế cấu hình của SDK; chi tiết tên hàm cụ thể cần đối chiếu Javadoc thực tế khi cài đặt thư viện).

2. **Xử lý Callback trên BFF & Chuyển hướng an toàn qua Android App Links:**
   - Meta chuyển hướng trình duyệt về endpoint BFF: `https://api.yourdomain.com/auth/facebook/callback?code={authorization-code}&state={returned-state}`.
   - Server BFF đối chiếu `returned-state` với dữ liệu trong cache (đã lưu ở bước `POST /auth/facebook/start`). Nếu `state` không tồn tại hoặc đã hết hạn, BFF từ chối request ngay lập tức để chống CSRF.
   - Server BFF giữ `client_secret` an toàn trong biến môi trường, lập tức gọi Meta Graph API để đổi `code` lấy User Access Token và nâng cấp thành Long-lived Token qua `grant_type=fb_exchange_token`.
   - **Lưu trữ tạm thời trong cache và sinh mã trao đổi (In-Memory Cache with Short TTL):**
     - Sau khi nhận Long-lived Token từ Meta, BFF lưu trữ tạm thời token này trong bộ nhớ cache tốc độ cao (in-memory store / Redis) với thời gian sống cực ngắn (TTL $\le 60$ giây, khớp với thời hạn của `exchange_code`), gắn liền với `app_code_challenge` của phiên để chờ app gọi lấy.
     - BFF tạo một **mã trao đổi dùng một lần** (**One-Time Exchange Code** / `exchange_code`, thời hạn hiệu lực $\le 60$ giây) gắn liền với bản ghi cache đó.
   - **Chống lộ Token qua URL & Phòng vệ chặn bắt Deep Link (Interception Defense):**
     - BFF **tuyệt đối không truyền access token trong URL**.
     - **Ưu tiên hàng đầu (Lựa chọn chính):** BFF chuyển hướng trình duyệt về ứng dụng Android thông qua **Android App Links (HTTPS)**:
       ```
       https://socialagent.app/oauth/facebook/callback?exchange_code={one-time-exchange-code}&state={secure-random-state}
       ```
       *(Android App Links sử dụng file chứng thực Digital Asset Links `/.well-known/assetlinks.json` khớp với chữ ký số APK để hệ điều hành ưu tiên mở thẳng app chính chủ, giúp **giảm đáng kể rủi ro** bị ứng dụng khác nghe lén URL; tuy nhiên hành vi này vẫn phụ thuộc vào phiên bản Android và trạng thái xác minh domain thành công trên thiết bị).*
     - **Lựa chọn dự phòng (Fallback):** Deep Link Custom Scheme `com.lttbdd.socialagent://oauth/facebook/callback?...` (khi domain chưa kịp xác minh hoặc trên môi trường dev/thiết bị chưa hỗ trợ đầy đủ App Links).
     - **Lớp bảo mật bắt buộc ở cả hai lựa chọn (App-to-BFF PKCE):** Cơ chế **PKCE nội bộ (`app_code_verifier` / `app_code_challenge`) là lớp phòng thủ cốt lõi bắt buộc ở CẢ HAI lựa chọn chuyển hướng** (chứ không chỉ là phương án dự phòng). Dù callback đi qua App Links hay Custom Scheme, nếu URL bị can thiệp hay lộ `exchange_code`, kẻ tấn công vẫn hoàn toàn không thể đổi lấy token vì thiếu `app_code_verifier` (chỉ lưu trong RAM của app thật).

3. **App đổi mã nhận Token qua kênh Back-Channel kèm `code_verifier` (Proof-of-Possession):**
   - Ứng dụng Android bắt App Links callback, kiểm tra đối chiếu `state`.
   - App gửi request HTTPS trực tiếp (`POST`) từ repository tới BFF, đính kèm `code_verifier` nội bộ đã lưu trong RAM:
     ```http
     POST https://api.yourdomain.com/auth/facebook/token
     Content-Type: application/json

     {
       "exchange_code": "{one-time-exchange-code}",
       "state": "{secure-random-state}",
       "code_verifier": "{app_code_verifier}"
     }
     ```
   - **Xác thực kép tại BFF:** Server BFF xác thực `exchange_code`, đồng thời băm `SHA256(code_verifier)` và so sánh với `app_code_challenge`. Nếu không khớp (kẻ xấu bắt trộm `exchange_code` trên deep link nhưng không có `code_verifier` trong RAM), BFF từ chối yêu cầu ngay lập tức!
   - Nếu hợp lệ: Server hủy `exchange_code` ngay lập tức (one-time use), **xóa sạch ngay lập tức Long-lived Token khỏi bộ nhớ cache tạm thời**, sinh token phiên nội bộ `bff_session_token` (định danh phiên thiết bị an toàn giữa App và BFF), và trả về payload trong JSON response body qua kênh HTTPS:
     ```json
     {
       "access_token": "{user-long-lived-token}",
       "token_type": "bearer",
       "expires_in": 5184000,
       "bff_session_token": "{bff_session_token}"
     }
     ```
   - **Cơ chế cấp & vai trò của `bff_session_token` phía BFF (BFF không lưu token Facebook trong database):**
     - **Nguyên tắc lưu trữ trên BFF (Stateless OAuth Proxy):** Khác với LinkedIn (cần lưu `refresh_token` trong DB server để chạy job làm mới ngầm), Facebook không có cơ chế refresh token kiểu LinkedIn, và Page Access Token sinh ra từ User Long-lived Token không có thời hạn hết hạn riêng (không có TTL độc lập / `never expires`), do đó BFF không cần duy trì token sau khi hoàn tất phiên ủy quyền. Trong luồng đăng nhập, **BFF chỉ lưu trữ tạm thời token trong bộ nhớ cache tốc độ cao với TTL cực ngắn ($\le 60$ giây, cùng thời hạn với `exchange_code`) gắn liền với `app_code_challenge` để chờ app client gọi đổi mã, và xóa sạch ngay lập tức sau khi đổi thành công**. **Máy chủ BFF tuyệt đối KHÔNG lưu giữ bất kỳ token Facebook nào trong cơ sở dữ liệu lâu dài (persistent database) của server**. Việc quản lý và lưu trữ token thuộc toàn quyền của app client trong `EncryptedSharedPreferences`.
     - **Cách cấp:** BFF sinh mã phiên ngẫu nhiên độ entropy cao (hoặc JWT ký bởi secret server) và gửi trực tiếp cho App trong response body của endpoint `POST /auth/facebook/token` (kênh HTTPS back-channel bảo mật, tuyệt đối không gửi qua URL/Deep Link).
     - **Vai trò:** Định danh phiên kết nối giữa App và BFF. Dùng làm token xác thực (qua header `Authorization: Bearer {bff_session_token}`) để **quản lý và thu hồi phiên (Session Revocation / Logout)**: khi người dùng bấm "Ngắt kết nối" (Disconnect) trên ứng dụng, app gửi request `POST /auth/facebook/logout` kèm `bff_session_token` để BFF vô hiệu hóa phiên làm việc trên server và dọn sạch session cache. Ngoài ra, token này giúp xác thực định danh app khi gọi các dịch vụ backend nội bộ khác của dự án.
   - **Lưu trữ an toàn phía Client (Bao gồm Page Access Token):**
     - **Chỉ lưu Token trong `EncryptedSharedPreferences` (Keystore):**
       - User Long-lived Token (`access_token`) và mã phiên `bff_session_token`.
       - **Page Access Token của từng Facebook Page:** Toàn bộ tác vụ cốt lõi F5 (đăng bài), F6 (đọc/trả lời comment) và F7 (lấy số liệu insights) đều thực hiện dưới danh nghĩa Page và bắt buộc sử dụng **Page Access Token** lấy từ `GET /v26.0/me/accounts`. Các Page Access Token này cũng là token nhạy cảm, **chỉ được lưu trữ trong `EncryptedSharedPreferences`** và được tra cứu theo `page_id` (ví dụ key: `fb_page_token_{page_id}`).
     - **Room Database tuyệt đối KHÔNG lưu plaintext token:** Room chỉ lưu trữ metadata phi nhạy cảm của tài khoản và từng Page (`account_id` / `page_id`, `name`, `avatar_url`, `category`, `tasks`, `expires_at`, `connection_status`, `capabilities`). Khi Repository thực hiện đăng bài hoặc đọc số liệu cho một Page, app sẽ đọc `account_id` từ Room DB rồi truy xuất Page Access Token tương ứng từ `EncryptedSharedPreferences`.
     - **Xử lý khi "Ngắt kết nối" (Disconnect) ở F1:** Khi người dùng bấm ngắt kết nối một Page hoặc đăng xuất tài khoản Facebook, ứng dụng phải **xóa sạch Page Access Token tương ứng** (hoặc toàn bộ User Token và các Page Token liên quan) khỏi `EncryptedSharedPreferences`, đồng thời cập nhật/xóa metadata trong Room DB.

##### Luồng PKCE trực tiếp với Custom Scheme (⚠️ Thử nghiệm — Theo quan sát, chưa xác minh):
Nếu Meta hỗ trợ cấu hình custom scheme và bỏ qua secret qua `code_verifier`:
- Request: `redirect_uri=com.lttbdd.socialagent://oauth/facebook` kèm `code_challenge`.
- Trao đổi: `GET https://graph.facebook.com/v26.0/oauth/access_token` kèm `code_verifier`.
*(Lưu ý: Luồng này không khuyến nghị cho bản release vì thiếu tài liệu xác nhận chính thức từ Meta và theo quan sát thực tế mục Valid OAuth Redirect URIs trên Dashboard yêu cầu URL HTTPS).*

#### So sánh Vòng đời Token giữa 2 Phương án (Token Lifecycle):

**1. Đối với Phương án 1 (Meta Android SDK — Tự động hoàn toàn phía Client):**
```
[User Login qua Meta SDK (App Switch / Custom Tabs)]
       │
       ▼ (SDK tự xử lý xác thực qua App ID + Key Hash, trả AccessToken object)
[User Access Token (~60 ngày)] (Quản lý ngầm bởi SDK Tracker, tự động rolling refresh khi mở app)
       │
       ▼ (Gọi GET /v26.0/me/accounts trực tiếp từ App với User Access Token)
[Page Access Token (Kỳ vọng vĩnh viễn — Cần chạy debug_token kiểm thử)]
```
> *Ghi chú:* Theo tài liệu chính thức về [Authentication Versus Data Access](https://developers.facebook.com/documentation/facebook-login/auth-vs-data), khi sử dụng Meta SDK, cơ chế rolling refresh diễn ra ngầm phía client mà không cần gọi endpoint `fb_exchange_token` (nhóm vẫn nên kiểm thử thực tế trên phiên bản SDK hiện hành).

**2. Đối với Phương án 2 (Web OAuth thủ công qua Custom Tabs + BFF):**
```
[User Login trên Chrome Custom Tabs]
       │
       ▼ (Meta chuyển hướng HTTPS về BFF Server với authorization_code)
[BFF Server nhận code] (BFF đổi code lấy Short-lived Token & gọi fb_exchange_token lấy Long-lived Token)
       │
       ▼ (BFF chuyển hướng về App qua Android App Links với exchange_code một lần & state)
[App nhận exchange_code] (App gọi POST /auth/facebook/token qua HTTPS back-channel để nhận Long-lived Token)
       │
       ▼ (Gọi GET /v26.0/me/accounts với User Long-lived Token)
[Page Access Token (Long-lived / Never-expiring)]
```
> *Ghi chú:* Sơ đồ có bước gọi `fb_exchange_token` **chỉ áp dụng cho Phương án 2**. Do tham số `grant_type=fb_exchange_token` yêu cầu bắt buộc phải có `client_secret`, các bước từ đổi code đến nâng cấp token phải được thực hiện trên máy chủ BFF để đảm bảo bí mật không bị lộ ra file APK.

* **Đặc tính của Page Access Token:** 
  - **Bản chất thời hạn của Page Access Token:** 
    - Tài liệu chính thức về endpoint `GET /v26.0/me/accounts` không nêu trường `expires_in` cho Page Access Token (chỉ trả về `access_token`, `id`, `name`, `category`, `tasks`); nhóm cần dùng công cụ `debug_token` để kiểm chứng thời hạn thực tế.
    - Theo tài liệu Meta, Page Access Token chỉ **không có thời hạn hết hạn (Never Expires / vĩnh viễn)** khi và chỉ khi nó được sinh ra từ một **User Long-lived Token** hợp lệ. Bản thân Page Access Token này **không có thời hạn sống riêng (không có TTL độc lập)**.
    - Tuy không có ngày hết hạn cố định, Page Access Token vẫn sẽ ngay lập tức bị vô hiệu hóa khi:
      1. Người dùng đổi mật khẩu tài khoản Facebook cá nhân.
      2. Người dùng chủ động đăng xuất hoặc gỡ quyền của App trong Facebook Settings.
      3. Người dùng bị xóa vai trò quản trị viên hoặc mất quyền (Page Tasks) trên chính Facebook Page đó.
  - **Phương pháp kiểm thử thời hạn Page Token trong giai đoạn Dev (Chỉ dùng khi kiểm thử, không đưa vào App):**
    - **Cách thức kiểm chứng:** Trong quá trình phát triển và kiểm thử, nhóm sử dụng công cụ **Access Token Debugger** trên Meta Developer Portal (hoặc gọi thủ công endpoint `GET /debug_token?input_token={page_access_token}&access_token={app_access_token}` qua Postman hoặc Graph API Explorer).
    - **Kết quả cần tìm:**
      - Trên giao diện web Access Token Debugger: hiển thị dòng **`Expires: Never`**.
      - Trong payload JSON từ `/debug_token`: trường **`data.expires_at: 0`** (xác nhận token không có thời hạn hết hạn).
    - **Phân định phạm vi kiến trúc (Không mâu thuẫn):**
      - Việc kiểm tra qua Access Token Debugger / `GET /debug_token` **chỉ áp dụng độc lập trong giai đoạn kiểm thử nội bộ**, tuyệt đối **không nhúng vào mã nguồn ứng dụng Android**.
      - Khi ứng dụng chạy thực tế (Runtime), app tuân thủ nghiêm ngặt nguyên tắc ở mục dưới đây: **không gọi `/debug_token`**, mà kiểm tra trạng thái token trực tiếp bằng cách thực hiện gọi dữ liệu thật tới `GET /v26.0/me` hoặc `GET /v26.0/me/accounts`.
* **Cơ chế kiểm tra token an toàn & Xử lý lỗi trong App Runtime (F1):**
  - **Lý do ứng dụng client không gọi endpoint `/debug_token` khi chạy thực tế:**
    - Theo tài liệu Meta về [Debugging Access Tokens](https://developers.facebook.com/docs/facebook-login/guides/access-tokens/debugging), endpoint `GET /debug_token?input_token={input-token}&access_token={access-token}` đòi hỏi một "valid access token" (thường là App Access Token phía server hoặc một token hợp lệ cùng App ID) để kiểm tra metadata.
    - Việc gọi `/debug_token` trên app di động làm phát sinh thêm một network round-trip thừa và đòi hỏi token kiểm tra phụ không cần thiết cho client.
    - Do đó, ứng dụng kiểm tra trạng thái token trực tiếp bằng cách thực hiện gọi dữ liệu thật tới `GET /v26.0/me` hoặc `GET /v26.0/me/accounts` với chính token đang dùng.
  - **Xử lý mã lỗi `OAuthException` (Code `190`) & Phân loại theo `error_subcode`:**
    - Theo tài liệu [Meta Graph API Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling), Meta **không cam kết cố định HTTP status** (lỗi có thể trả về HTTP 400 Bad Request hoặc 401 Unauthorized tùy ngữ cảnh). Ứng dụng **phải parse và phân loại lỗi dựa trên body JSON** (`error.code` và `error.error_subcode`), không phụ thuộc đơn thuần vào HTTP status code:
      ```json
      {
        "error": {
          "message": "Error validating access token: ...",
          "type": "OAuthException",
          "code": 190,
          "error_subcode": 463,
          "fbtrace_id": "..."
        }
      }
      ```
    - **Bảng ánh xạ `error_subcode` của mã lỗi 190 sang trạng thái ứng dụng:**
      | `error_subcode` | Ý nghĩa kỹ thuật từ Meta | Ánh xạ & Hành vi xử lý trong App |
      |---|---|---|
      | **`458`** | *App Not Installed / Permissions Revoked*: Người dùng đã gỡ cài đặt app hoặc thu hồi quyền truy cập trong Facebook Settings. | Chuyển trạng thái sang `ConnectionStatus.TOKEN_EXPIRED` $\rightarrow$ Hướng dẫn người dùng đăng nhập và ủy quyền lại từ đầu. |
      | **`459`** | *Checkpoint / Security Check Required*: Người dùng chưa đăng nhập Facebook gần đây hoặc tài khoản gặp checkpoint bảo mật; Meta yêu cầu giải quyết trên web. | Chuyển trạng thái `ConnectionStatus.TOKEN_EXPIRED` kèm thông báo hành động: *"Tài khoản Facebook cần xác minh bảo mật. Vui lòng mở facebook.com trên trình duyệt để giải quyết trước khi kết nối lại."* |
      | **`460`** | *Password Changed / Logged Out*: Người dùng đổi mật khẩu tài khoản Facebook hoặc chủ động đăng xuất khỏi Facebook $\rightarrow$ Toàn bộ session token bị vô hiệu hóa. | Chuyển trạng thái sang `ConnectionStatus.TOKEN_EXPIRED` $\rightarrow$ Thông báo người dùng đã đăng xuất hoặc đổi mật khẩu, cần đăng nhập lại. |
      | **`463`** | *Token Expired*: Token đã hết hạn tự nhiên sau chu kỳ sống (TTL). | Chuyển trạng thái sang `ConnectionStatus.TOKEN_EXPIRED` $\rightarrow$ Thực hiện refresh ngầm (nếu dùng SDK) hoặc yêu cầu kết nối lại. |
      | **`464`** | *Action Required on Web*: Người dùng cần đăng nhập vào facebook.com để xác nhận tài khoản/thao tác bảo mật trước khi token có thể hoạt động. | Chuyển trạng thái `ConnectionStatus.TOKEN_EXPIRED` kèm thông báo: *"Cần đăng nhập và xác nhận tài khoản trên facebook.com trước khi kết nối lại."* |
      | **`467`** | *Invalid Access Token*: Token không hợp lệ hoặc đã bị đăng xuất/thu hồi chủ động. | Chuyển trạng thái sang `ConnectionStatus.TOKEN_EXPIRED` $\rightarrow$ Hướng dẫn người dùng kết nối lại. |
      | **`492`** | *Role Removed / Inadequate Permissions*: Người dùng không còn vai trò phù hợp trên Page (ví dụ: bị gỡ khỏi Admin/Editor Page). | **Lỗi phân quyền Page (không phải hết hạn token)** $\rightarrow$ Giữ trạng thái tài khoản, hiển thị cảnh báo người dùng không còn quyền quản trị trên Page đó. |
      | **Khác / Không có** | Lỗi token chung (`OAuthException 190`). | Mặc định xem như token không còn hiệu lực $\rightarrow$ Gán `ConnectionStatus.TOKEN_EXPIRED`. |

  - **Xử lý lỗi phân quyền API (Mã lỗi `10` và `200–299`) $\rightarrow$ Kích hoạt `HANDED_OFF`:**
    - **Ý nghĩa kỹ thuật từ Meta:**
      - **Mã lỗi `10`** (*Permission Denied*): Ứng dụng hoặc Access Token không có quyền thực hiện thao tác này.
      - **Dải mã lỗi `200–299`** (*Permissions-related errors*): Theo tài liệu Meta, toàn bộ dải mã từ `200` đến `299` quy định chung cho các lỗi phân quyền (Permissions-related errors: ví dụ lỗi `200` *Permissions disallow edit to field*; các mã lỗi cụ thể khác trong dải như `283`, `294` là ví dụ tham khảo từ cộng đồng dev, cần đối chiếu bảng lỗi thực tế của Meta khi kiểm thử).
    - **Ánh xạ kiến trúc & Cơ chế xử lý:**
      - Đây là lỗi phân quyền vĩnh viễn (**PermanentError**), việc thử lại (retry) cuộc gọi API hay refresh token sẽ tiếp tục thất bại nếu App Review chưa duyệt quyền hoặc tài khoản chưa được cấp vai trò.
      - Khi gặp mã lỗi `10` hoặc `200–299` trong quá trình đăng bài (F5): Client **tuyệt đối không retry vô hạn**, mà lập tức kích hoạt cơ chế dự phòng bàn giao (xem chi tiết tại §4):
        1. Đánh dấu bài viết sang trạng thái **`PostStatus.HANDED_OFF`** trong Room Database.
        2. Tự động sao chép nội dung bài viết và đường dẫn hình ảnh vào bộ nhớ tạm (Clipboard).
        3. Kích hoạt Android Share Intent native (`Intent.ACTION_SEND` cho bài kèm 1 ảnh hoặc `Intent.ACTION_SEND_MULTIPLE` cho bài đa ảnh) để người dùng tự đăng tay trực tiếp lên ứng dụng Facebook mà không bị mất dữ liệu bài viết đã soạn.

### 2.2. Danh sách quyền (Permissions / Scopes)
Đối chiếu trực tiếp với các khả năng trong `ChannelCapability` (`core/model/Channel.kt`):

| Chức năng / Khả năng | Scope cần xin | Mô tả & Lưu ý kỹ thuật |
|---|---|---|
| **F1 (Kết nối kênh)** | `public_profile`, `pages_show_list` | Lấy danh sách các Page người dùng quản lý (`GET /me/accounts`) và lấy Page Access Token. |
| **`PUBLISH` (Đăng bài)** | `pages_manage_posts`, `pages_read_engagement` | Đăng bài viết (text, link), upload ảnh/video thay mặt Page lên endpoint `/{page-id}/feed` hoặc `/{page-id}/photos`. |
| **`READ_COMMENTS` (Đọc comment)** | `pages_read_user_content`, `pages_read_engagement` | Đọc danh sách bình luận của người dùng và các phản hồi trên bài viết (`GET /{post-id}/comments`). |
| **`REPLY` (Trả lời comment)** | `pages_manage_engagement` | Gửi bình luận trả lời (`POST /{comment-id}/comments`) dưới danh nghĩa Page. |
| **`MENTION` (Nhắc tên)** | `pages_manage_posts` *(kèm cú pháp)* | Meta **không cấp quyền API để mention trang cá nhân** (Personal Profile) để chống spam. Đối với việc mention các **Page khác**, cú pháp `@[page_id]` **⚠️ Chưa được Meta xác nhận chính thức trong tài liệu Graph API hiện hành** (cần kiểm thử thực tế; nếu Meta chỉ hiển thị text thô, tự động kích hoạt luồng `HANDED_OFF` để người dùng tag tay trên app Facebook). |
| **`METRICS` (Số liệu)** | `read_insights`, `pages_read_engagement` | Đọc số lượt hiển thị (`post_media_view`), tương tác (`reactions`, `shares`, `comments count`) và số lượng người theo dõi (`followers_count`, cùng `fan_count` — *cần kiểm chứng trạng thái deprecate trên v26.0*). |

### 2.3. Các Endpoint chính
1. **Lấy danh sách Page và Page Token (F1):**
   ```http
   GET https://graph.facebook.com/v26.0/me/accounts?fields=id,name,access_token,category,tasks
   ```
2. **Đăng bài viết kèm nội dung văn bản (F5):**
   ```http
   POST https://graph.facebook.com/v26.0/{page-id}/feed
   Content-Type: application/json

   {
     "message": "Nội dung bài viết do AI soạn...",
     "access_token": "{page-access-token}"
   }
   ```
3. **Đăng bài viết kèm ảnh đơn (F5):**
   ```http
   POST https://graph.facebook.com/v26.0/{page-id}/photos
   Content-Type: multipart/form-data

   message="Nội dung bài viết kèm ảnh..."
   source=@/path/to/image.png
   access_token={page-access-token}
   ```
   *Trả về:* `{"id": "photo_id", "post_id": "{page-id}_{post-id}"}`.

4. **Đăng bài viết đa ảnh (Multi-photo Post — F5):**
   - **Bước 1: Tải từng ảnh ở chế độ chưa công khai (`published=false`):**
     ```http
     POST https://graph.facebook.com/v26.0/{page-id}/photos
     Content-Type: multipart/form-data

     published=false
     source=@/path/to/image1.png
     access_token={page-access-token}
     ```
     *Nhận về:* `{"id": "{photo_id_1}"}`. Lặp lại cho từng ảnh để thu thập danh sách `[photo_id_1, photo_id_2, ...]`.
   - **Bước 2: Tạo bài post trên Feed đính kèm mảng `attached_media`:**
     ```http
     POST https://graph.facebook.com/v26.0/{page-id}/feed
     Content-Type: application/json

     {
       "message": "Nội dung bài viết kèm album ảnh...",
       "attached_media": [
         {"media_fbid": "{photo_id_1}"},
         {"media_fbid": "{photo_id_2}"}
       ],
       "access_token": "{page-access-token}"
     }
     ```
     *Nhận về:* `{"id": "{page-id}_{post-id}"}`.
   - *Lưu ý kỹ thuật & Trạng thái xác minh:*
     - **Số lượng ảnh tối đa:** Thường ghi nhận thực tế tối đa khoảng ~30 ảnh trong một feed post (⚠️ *Trạng thái: Chưa xác minh từ tài liệu chính thức của Meta; Meta không công bố hạn mức cố định chính thức trong tài liệu Pages API, cần kiểm thử thực tế*).
     - **Dung lượng file:** Thường khuyến nghị dưới 4 MB cho upload binary (⚠️ *Trạng thái: Chưa xác minh con số trần cố định trong tài liệu Meta*).
     - Trong Milestone 1, model `PostDraft` ưu tiên hỗ trợ `imageUri` (ảnh đơn), quy trình đa ảnh được chuẩn hóa sẵn sàng cho giai đoạn mở rộng tiếp theo.

   > [!WARNING]
   > **F5 — Idempotency phía app (Facebook không có header native) và lỗ hổng bản giả**
   >
   > Meta Graph API **không** có `X-RestLi-Idempotency-Key`. App phải tự chống đăng trùng: cùng `idempotencyKey` thì `SocialChannelClient.publish` trả kết quả lần đăng trước, không tạo bài mới (Room DB khi có client thật; `publishedByKey` khi còn `FakeSocialChannelClient`).
   >
   > `FakeSocialChannelClient.publish` hiện **chưa an toàn khi hai coroutine gọi song song**:
   > 1. Cả hai miss cache, cùng `delay`, rồi mới ghi map.
   > 2. `remoteId` lấy từ `publishedByKey.size + 1` **trước** khi insert → hai key khác nhau có thể ra cùng `remotePostId` (F6 comment / F7 metrics đụng id).
   > 3. Cùng một key: cả hai vẫn đi qua `delay` như thể đăng hai lần; `getOrPut` chỉ giữ một entry nên unit test gọi **nối tiếp** vẫn xanh.
   >
   > Khi sửa: `Mutex` + double-check key trong lock; cấp `remoteId` bằng bộ đếm nguyên tử (không dùng `size + 1`); thêm test `async` cùng key và khác key với `latencyMillis > 0`.

5. **Lấy danh sách comment của bài viết (F6):**
   ```http
   GET https://graph.facebook.com/v26.0/{post-id}/comments?fields=id,from,message,created_time,comment_count,parent&limit=50&access_token={page-access-token}
   ```
6. **Trả lời comment (F6):**
   ```http
   POST https://graph.facebook.com/v26.0/{comment-id}/comments
   Content-Type: application/json

   {
     "message": "Chào bạn, cảm ơn bạn đã quan tâm...",
     "access_token": "{page-access-token}"
   }
   ```
7. **Lấy số liệu bài viết (F7):**
   ```http
   GET https://graph.facebook.com/v26.0/{post-id}?fields=shares,comments.summary(total_count),reactions.summary(total_count),insights.metric(post_media_view)&access_token={page-access-token}
   ```
   *Lưu ý thay đổi trường dữ liệu quan trọng:*
   - Các chỉ số insights cũ như `post_engaged_users` và `post_impressions` đã chính thức bị **deprecated và gỡ bỏ hoàn toàn trên mọi phiên bản Graph API** (theo Pages API Changelog, thay đổi này áp dụng toàn cục cho tất cả các version). Nếu gửi request chứa các metric cũ này, Meta sẽ trả về lỗi HTTP 400 Bad Request: `(#100) The value must be a valid insights metric` làm thất bại toàn bộ cuộc gọi.
   - Để lấy số lượt hiển thị/xem (impressions/views), sử dụng metric thay thế chuẩn chính thức của Meta là **`post_media_view`**.
   - Các tương tác like/reaction, comment, share được lấy trực tiếp và chính xác qua summary connection: `reactions.summary(total_count)`, `comments.summary(total_count)` và `shares`.
8. **Lấy số follower của Page (F7):**
   ```http
   GET https://graph.facebook.com/v26.0/{page-id}?fields=followers_count,fan_count&access_token={page-access-token}
   ```
   *Lưu ý về các trường số liệu trên Graph API v26.0 (Legacy Page Fields):*
   - **`followers_count`:** Trường dữ liệu chuẩn, chính thức và được Meta khuyến nghị sử dụng để lấy tổng số người theo dõi Page dưới giao diện New Pages Experience (NPE).
   - **`fan_count` (Số lượt Like Page):** ⚠️ **Trạng thái: Chưa xác minh trên v26.0 (nguy cơ thuộc nhóm legacy Page fields bị deprecate)**. Dưới giao diện NPE, Meta đã gỡ bỏ nút Like trang và chuyển hướng quản lý sang người theo dõi (`followers_count`), đồng thời lên lộ trình deprecate dần các trường legacy liên quan đến fans/likes. Trong mã nguồn Mảng B, client cần ưu tiên đọc `followers_count` làm số liệu chính và xử lý an toàn (nullable) với `fan_count` để tránh lỗi khi Meta ngừng trả về trường này trên v26.0.

### 2.4. Giới hạn & Quy định kiểm duyệt (App Review)
- **Độ dài bài viết tối đa:** `63,206` ký tự (khớp cấu hình trong `ChannelType.FACEBOOK(maxChars = 63_206)`).
- **Kích thước ảnh & Định dạng:**
  - Định dạng hỗ trợ chính thức: JPEG, PNG, GIF, WebP.
  - **Giới hạn dung lượng (⚠️ Trạng thái: Chưa xác minh từ tài liệu chính thức của Meta — Số liệu thực nghiệm/quan sát cộng đồng dev):**
    - Upload trực tiếp file binary (multipart upload): Thường khuyến nghị tối đa **4 MB**.
    - Upload qua URL ảnh công khai: Thường ghi nhận tối đa **10 MB**.
    *(Meta chỉ kiểm tra định dạng và kích thước pixel hợp lệ, không công bố trần dung lượng MB cố định công khai trong tài liệu Photos API; cần kiểm chứng thực tế khi tích hợp).*
- **Rate Limit (Giới hạn tần suất gọi):** Áp dụng cơ chế **Business Use Case (BUC) Rate Limiting** (và Platform Rate Limiting theo token):
  - **Bản chất giới hạn động (Dynamic Threshold):** Theo tài liệu chính thức của Meta về [Graph API Rate Limiting](https://developers.facebook.com/docs/graph-api/overview/rate-limiting/), không có hạn ngạch cuộc gọi cố định cho tất cả các Page. Ngưỡng giới hạn được tính toán linh hoạt theo cửa sổ trượt (rolling window) dựa trên số lượng người dùng tương tác với Page (Page Engaged Users) và loại use-case cụ thể.
  - **Cơ chế theo dõi tài nguyên:** Ứng dụng theo dõi trực tiếp mức tiêu thụ tài nguyên qua header HTTP trả về:
    - `X-Business-Use-Case-Usage`: Chứa đối tượng JSON biểu thị tỉ lệ % đã sử dụng (`call_count`, `cpu_time`, `total_time` tính trên ngưỡng 100%) và thời gian chờ phục hồi (`estimated_time_to_regain_access`).
    - `X-App-Usage`: Biểu diễn % tài nguyên cấp độ ứng dụng.
  - **Mã lỗi khi chạm trần:** Trả về HTTP 403 / 400 kèm mã lỗi `4` (App rate limit), `17` (User/Page rate limit) hoặc mã `32` $\rightarrow$ Map sang `PublishResult.RetryableError` kèm thuật toán Exponential Backoff và thời gian chờ theo header phản hồi.
- **Chính sách hết hạn quyền truy cập dữ liệu (90-Day Data Access Expiration):**
  - **Bản chất chính sách bảo mật nền tảng (Platform Policy):** Theo tài liệu chính thức về [Authentication Versus Data Access](https://developers.facebook.com/documentation/facebook-login/auth-vs-data), Meta áp dụng chính sách thời hạn truy cập dữ liệu người dùng là **90 ngày tính từ lần hoạt động cuối cùng của người dùng** trên ứng dụng.
  - **Phân biệt giữa Xác thực (Authentication) và Quyền truy cập dữ liệu (Data Access):**
    - Khi hết hạn 90 ngày, phiên đăng nhập của người dùng **vẫn tồn tại** (người dùng vẫn ở trạng thái authenticated, access token không tự động mất hiệu lực xác thực danh tính), nhưng ứng dụng **bị chặn hoàn toàn quyền đọc/truy cập dữ liệu người dùng** cho đến khi được người dùng cấp lại quyền.
    - *Lưu ý về mã lỗi:* Tài liệu chính thức của Meta không nêu cụ thể mã lỗi HTTP hay subcode (⚠️ *Trạng thái mã lỗi: Chưa xác minh trong tài liệu chính thức; không khẳng định cố định lỗi OAuthException code 190*).
  - **Cơ chế phát hiện chính thức (Official Detection):**
    - **Phía Android SDK (Client-side):** Kiểm tra trực tiếp trên đối tượng `AccessToken` (`AccessToken.getCurrentAccessToken()`):
      - Thuộc tính `dataAccessExpirationTime`: Thời điểm hết hạn truy cập dữ liệu (`Date`).
      - Hàm `isDataAccessExpired`: Trả về `true` nếu quyền truy cập dữ liệu đã hết hạn sau 90 ngày không hoạt động.
    - **Phía Server / Dev inspection:** Đọc trường `data_access_expires_at` (Unix timestamp) từ endpoint `GET /debug_token?input_token={token}&access_token={app-token}`.
  - **Cơ chế cấp lại quyền chính thức (Re-authorization):**
    - Khi `isDataAccessExpired` là `true` hoặc cuộc gọi truy cập dữ liệu bị từ chối, ứng dụng kích hoạt luồng cấp lại quyền thông qua hàm chính thức của Meta SDK:
      ```kotlin
      LoginManager.getInstance().reauthorizeDataAccess(activity)
      ```
    - Người dùng chỉ cần bấm xác nhận đồng ý trên dialog cấp lại quyền của Meta (không cần phải nhập lại email/mật khẩu).
  - **Lưu ý quan trọng về Hết hạn quyền cấp độ ứng dụng (App-level Inactivity Per Permission):**
    - Theo chính sách của Meta, nếu ứng dụng **không sử dụng một quyền cụ thể nào trong vòng 90 ngày liên tục**, Meta có thể tự động vô hiệu hóa quyền đó của ứng dụng, **kể cả khi quyền đó đã được phê duyệt qua Meta App Review**.
    - *Tác động đến đồ án:* Cần đặc biệt chú ý quyền **`read_insights`** (phục vụ tính năng xem số liệu F7). Nếu người dùng hoặc nhóm thử nghiệm ít khi mở màn hình xem số liệu Insights trong chu kỳ 90 ngày, quyền này có nguy cơ bị thu hồi ngầm, đòi hỏi phải kích hoạt cấp lại quyền hoặc kiểm tra lại cấu hình trên App Dashboard.
- **Quyền nào cần App Review (Meta App Review):**
  - *Default Access (Tự động có, không cần review):* Chỉ có `public_profile` và `email`.
  - *Advanced Access (BẮT BUỘC App Review nếu dùng công chúng):* Tất cả các quyền phục vụ tính năng chính: `pages_show_list`, `pages_manage_posts`, `pages_read_engagement`, `pages_read_user_content`, `pages_manage_engagement`, `read_insights`.
  - *Điều kiện qua App Review:* Bắt buộc phải có **Business Verification** (Xác minh giấy phép doanh nghiệp) và video screencast chứng minh luồng ứng dụng thực tế.
- **Chế độ Development dùng được tới đâu:**
  - **Phạm vi và Điều kiện hoạt động:** Ở chế độ **Development Mode**, các quyền nâng cao (`pages_manage_posts`, `read_insights`, v.v.) chỉ hoạt động **trong phạm vi tài khoản nội bộ và có điều kiện ràng buộc**, cụ thể:
    1. **Ràng buộc tài khoản (App Roles):** Chỉ các tài khoản Facebook có vai trò chính thức trong mục *App Roles* trên Meta Dashboard (*Administrators*, *Developers*, hoặc *Testers*) mới có thể đăng nhập và cấp quyền. Người dùng vãng lai bên ngoài sẽ bị Meta từ chối.
    2. **Ràng buộc vai trò trên Page (Page Tasks):** Tài khoản thử nghiệm phải nắm vai trò quản trị hoặc nhiệm vụ phân quyền cụ thể (*Page Tasks*: ví dụ `CREATE_CONTENT`, `MANAGE`) trên chính Facebook Page được thử nghiệm.
    3. **Ràng buộc kích hoạt tính năng:** Sản phẩm/tính năng tương ứng (ví dụ: *Pages API* hoặc *Facebook Login for Business*) phải được cấu hình trong App Dashboard.
    4. **Giới hạn cần lưu ý:** Một số quyền nhạy cảm cao (như Live Video hoặc dữ liệu cá nhân chi tiết) vẫn có thể đòi hỏi xác minh doanh nghiệp ngay trong Development Mode. Với phạm vi đồ án (F1, F5, F6, F7 trên Page thử nghiệm của nhóm), chế độ Development Mode đáp ứng đủ yêu cầu phát triển mà chưa cần mở công chúng.

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
- **Redirect URI đăng ký với LinkedIn:** Bắt buộc là **URL HTTPS hợp lệ trỏ trực tiếp về máy chủ backend/BFF** (ví dụ: `https://api.yourdomain.com/auth/linkedin/callback` hoặc endpoint Cloudflare Worker).
  > [!IMPORTANT]
  > **Yêu cầu Callback HTTPS của Standard OAuth:**
  > Theo tài liệu chính thức của LinkedIn OAuth 2.0, Developer Portal **chỉ chấp nhận callback giao thức HTTPS** làm Redirect URI cho luồng Standard Web OAuth (hoàn toàn không cho phép đăng ký trực tiếp Custom Scheme như `com.lttbdd.socialagent://...` làm callback trên LinkedIn Developer Portal).
- **Ràng buộc kỹ thuật:** Luồng standard này **bắt buộc phải có `client_secret`** khi gọi token endpoint.
- **Quy trình hoạt động hoàn chỉnh của Kiến trúc BFF (Backend-For-Frontend) kèm Bảo mật Kép:**
  1. **Khởi tạo ủy quyền & Đăng ký phiên với BFF (App-to-BFF PKCE):**
     - **Tạo bằng chứng sở hữu (Proof-of-Possession):** Trước khi mở browser, Android app sinh chuỗi ngẫu nhiên bảo mật `state` và cặp khóa PKCE nội bộ: `app_code_verifier` (chuỗi ngẫu nhiên bí mật độ entropy cao, lưu trong RAM) và `app_code_challenge = BASE64URL(SHA256(app_code_verifier))`.
     - **Khởi tạo phiên trên BFF qua endpoint `POST /auth/linkedin/start`:**
       - Để server BFF biết lưu trữ và theo dõi `state` trước khi nhận callback từ LinkedIn, app gửi request khởi tạo phiên:
       ```http
       POST https://api.yourdomain.com/auth/linkedin/start
       Content-Type: application/json

       {
         "state": "{state}",
         "code_challenge": "{app_code_challenge}"
       }
       ```
       - Server BFF lưu trữ cặp `(state, app_code_challenge)` vào bộ nhớ cache tạm thời (ví dụ Redis / memory store với TTL ngắn ~5–10 phút) để đối chiếu khi nhận callback.
     - **Mở Custom Tabs ủy quyền:** Sau khi phiên được khởi tạo trên BFF, Android app mở Custom Tabs điều hướng tới `https://www.linkedin.com/oauth/v2/authorization` với `redirect_uri=https://api.yourdomain.com/auth/linkedin/callback` (HTTPS URL tới BFF) và tham số `state`.
  2. **Callback HTTPS từ LinkedIn về BFF:** Người dùng đăng nhập và xác nhận cấp quyền trên trình duyệt. Máy chủ xác thực LinkedIn chuyển hướng (redirect) trình duyệt trực tiếp về endpoint HTTPS của BFF kèm mã:
     `https://api.yourdomain.com/auth/linkedin/callback?code={authorization_code}&state={state}`
     - Server BFF nhận callback, kiểm tra đối chiếu tham số `state` nhận được với cache đã lưu ở bước `POST /auth/linkedin/start`. Nếu `state` không tồn tại hoặc đã hết hạn, BFF từ chối request ngay lập tức để chống CSRF.
  3. **BFF đổi Token với LinkedIn (Server-to-Server):** Server BFF (nơi lưu giữ `LINKEDIN_CLIENT_SECRET` an toàn trong biến môi trường server, tuyệt đối không đưa vào client/APK) gửi request POST trực tiếp lên máy chủ LinkedIn:
     ```http
     POST https://www.linkedin.com/oauth/v2/accessToken
     Content-Type: application/x-www-form-urlencoded

     grant_type=authorization_code
     &code={authorization_code}
     &redirect_uri=https://api.yourdomain.com/auth/linkedin/callback
     &client_id={client_id}
     &client_secret={client_secret}
     ```
  4. **BFF chuyển hướng về App bằng Android App Links & Mã Trao đổi Dùng Một Lần:**
     > [!CAUTION]
     > **Nguyên tắc phòng thủ chặn bắt Deep Link (Interception Attack Prevention):**
     > - Nếu chỉ dùng Custom Scheme (`com.lttbdd.socialagent://...`), một ứng dụng độc hại cài trên máy có thể đăng ký trùng scheme để nhận được cả `exchange_code` lẫn `state`. Việc app thật kiểm tra `state` chỉ chống được tấn công CSRF web, chứ **không ngăn được app độc hại lấy cắp `exchange_code` rồi gửi request đổi token trước app thật**.
     > - Do đó, hệ thống áp dụng **3 điểm phòng vệ then chốt**:
     >   1. **Lựa chọn chính thức (Primary):** Chuyển hướng bằng **Android App Links (HTTPS)**:
     >      ```
     >      https://socialagent.app/oauth/linkedin/callback?exchange_code={one_time_code}&state={state}
     >      ```
     >      *(Android App Links sử dụng file chứng thực số `/.well-known/assetlinks.json` trên domain để OS xác minh chữ ký SHA-256 của file APK, giúp **giảm đáng kể rủi ro** bị ứng dụng khác chặn bắt URL; tuy nhiên hành vi này vẫn phụ thuộc vào phiên bản Android và trạng thái xác minh domain thành công trên thiết bị).*
     >   2. **Lựa chọn dự phòng (Fallback):** Custom Scheme `com.lttbdd.socialagent://oauth/linkedin/callback?...` (khi domain chưa kịp xác minh hoặc trên thiết bị chưa hỗ trợ đầy đủ App Links).
     >   3. **Lớp bảo vệ bắt buộc ở cả hai lựa chọn (App-to-BFF PKCE):**
     >      Cơ chế **PKCE nội bộ (`app_code_verifier` và `app_code_challenge`) là lớp phòng thủ cốt lõi bắt buộc ở CẢ HAI lựa chọn chuyển hướng** (không chỉ riêng cho fallback). Ngay cả khi URL callback bị can thiệp hoặc lộ `exchange_code`, kẻ tấn công vẫn **hoàn toàn không thể đổi mã lấy token** vì thiếu `app_code_verifier` (chỉ tồn tại trong RAM của app thật ở bước 5).
     
     - Sau khi nhận và lưu trữ tạm thời phiên token từ LinkedIn, máy chủ BFF sinh một mã dùng một lần (**One-Time Exchange Code** / `exchange_code`), có thời gian sống cực ngắn (TTL $\le 60$ giây) gắn liền với `app_code_challenge` của phiên.
     - Server BFF phản hồi HTTP 302 Redirect chuyển hướng trình duyệt về ứng dụng Android qua Android App Links.
  5. **Đổi Token an toàn qua Kênh Bảo mật Back-Channel kèm `code_verifier` & Phân định Lưu trữ:**
     - Trình duyệt Custom Tabs nhận redirect và đóng lại; hệ điều hành Android kích hoạt Activity xử lý callback của app.
     - App kiểm tra đối chiếu tham số `state` nhận được với `state` đã lưu cục bộ để chống CSRF.
     - App gửi ngay request `POST` qua kết nối bảo mật HTTPS trực tiếp tới BFF, đính kèm `code_verifier` nội bộ:
       ```http
       POST https://api.yourdomain.com/auth/linkedin/token
       Content-Type: application/json

       {
         "exchange_code": "{one_time_code}",
         "state": "{state}",
         "code_verifier": "{app_code_verifier}"
       }
       ```
     - **Xác thực kép tại BFF:** Server BFF kiểm tra `exchange_code`, đồng thời băm `SHA256(code_verifier)` so khớp với `app_code_challenge`. Kẻ tấn công nếu có chặn bắt được `exchange_code` trên deep link cũng **hoàn toàn không thể đổi mã** vì không có `app_code_verifier` (chỉ lưu trong RAM của app thật).
     - BFF hủy mã `exchange_code` ngay lập tức (one-time use), tạo mã phiên `bff_session_token` độ entropy cao (hoặc JWT ký số bởi server), và trả về payload JSON chứa cả access token và session token qua HTTPS response body an toàn:
       ```json
       {
         "access_token": "AQXN...",
         "token_type": "bearer",
         "expires_in": 5184000,
         "bff_session_token": "{bff_session_token}"
       }
       ```
     - **Cơ chế cấp & vai trò của `bff_session_token` phía BFF:**
       - **Cách cấp:** BFF sinh mã phiên ngẫu nhiên độ entropy cao (hoặc JWT ký bởi secret server) và gửi trực tiếp cho App trong response body của endpoint `POST https://api.yourdomain.com/auth/linkedin/token` (kênh HTTPS back-channel bảo mật, tuyệt đối không gửi qua URL/Deep Link).
       - **Vai trò:** `bff_session_token` là khóa định danh phiên bí mật giữa App và BFF. Token này đảm nhận hai vai trò an ninh then chốt:
         1. **Làm mới token tự động:** Được app đính kèm trong header `Authorization: Bearer {bff_session_token}` khi gọi `POST /auth/linkedin/refresh` (mục 3 dưới đây), giúp BFF chứng thực chính xác app thật trước khi dùng `refresh_token` lưu trên server để đổi lấy token mới với LinkedIn.
         2. **Thu hồi phiên & Hủy `refresh_token` khi Ngắt kết nối (`POST /auth/linkedin/logout`):** Khi người dùng bấm "Ngắt kết nối" (F1), app gửi `bff_session_token` lên endpoint `POST /auth/linkedin/logout` để yêu cầu BFF xóa vĩnh viễn `refresh_token` trong DB máy chủ và vô hiệu hóa phiên, ngăn ngừa nguy cơ tồn đọng credential mồ côi trên backend sau khi app đã hủy kết nối.
     - **Quy tắc phân định lưu trữ an toàn phía Client:**
       - **Chỉ lưu Token trong `EncryptedSharedPreferences`:** Toàn bộ token bí mật gồm `access_token`, `refresh_token` (nếu có) và `bff_session_token` được lưu trữ an toàn trong `EncryptedSharedPreferences` bằng Android Keystore (`MasterKey.DEFAULT_AES256_GCM_SPEC`).
       - **Room Database tuyệt đối KHÔNG lưu token:** Room chỉ lưu trữ metadata phi nhạy cảm của tài khoản (`account_id`, `name`, `avatar_url`, `expires_at`, `connection_status`, `capabilities`). Khi gọi API, Repository sẽ lấy metadata từ Room và truy xuất token từ `EncryptedSharedPreferences`.

#### Bảng so sánh hai luồng OAuth của LinkedIn

| Tiêu chí | Luồng A: Native PKCE (`/oauth/native-pkce/`) | Luồng B: Standard OAuth (`/oauth/v2/`) qua BFF |
|---|---|---|
| **Endpoint cấp mã** | `https://www.linkedin.com/oauth/native-pkce/authorization` | `https://www.linkedin.com/oauth/v2/authorization` |
| **Kiểu Redirect URI** | Loopback IP (`http://127.0.0.1:{port}/callback`) | URL HTTPS về server BFF (`https://api.yourdomain.com/.../callback`) *(App nhận mã `exchange_code` tạm thời qua Android App Links rồi gọi POST HTTPS lấy token)* |
| **Yêu cầu Client Secret** | ❌ **Không** (Trao đổi bằng `code_verifier`) | ✅ **Bắt buộc** (Phải ủy quyền và giữ secret tại Backend/BFF) |
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
   > [!IMPORTANT]
   > **Yêu cầu bắt buộc `client_secret` khi Refresh Token:**
   > Theo tài liệu chính thức của LinkedIn OAuth 2.0 (`POST /oauth/v2/accessToken` với `grant_type=refresh_token`), request làm mới token **bắt buộc phải truyền cả `client_id` và `client_secret`**.
   > Vì ứng dụng Android là Public Client không được giữ `client_secret`, **request refresh token này BẮT BUỘC phải do máy chủ BFF thực hiện** (nơi lưu giữ `client_secret` an toàn phía server), tuyệt đối không gọi trực tiếp từ Android client.

   - **Thực thi hoàn toàn phía Server/BFF:**
     - **Cơ chế xác thực giữa App và BFF (BFF Session Authentication):**
       - Khi hoàn tất đăng nhập ban đầu, máy chủ BFF lưu trữ `refresh_token` của LinkedIn an toàn trong cơ sở dữ liệu server (được mã hóa at-rest).
       - BFF cấp cho app Android một token phiên nội bộ (**BFF Session Token**, chuỗi ngẫu nhiên độ entropy cao hoặc JWT ký bởi server). App lưu Session Token này riêng biệt trong `EncryptedSharedPreferences`.
       - Khi token sắp hết hạn (ví dụ trước 7 ngày, phát hiện qua trường `expiresAt` trong Room DB), app Android gửi yêu cầu làm mới ngầm đến server BFF qua kết nối HTTPS:
         ```http
         POST https://api.yourdomain.com/auth/linkedin/refresh
         Authorization: Bearer {bff_session_token}
         Content-Type: application/json

         {
           "account_id": "urn:li:organization:{org-id}"
         }
         ```
       - **BFF xác thực phiên:** Server BFF xác minh tính hợp lệ và chữ ký của `bff_session_token`, kiểm tra xem token phiên có quyền sở hữu hợp pháp đối với `account_id` yêu cầu hay không. Nếu phiên không hợp lệ hoặc đã bị thu hồi, BFF từ chối request ngay lập tức với mã HTTP 401.
     - **BFF làm mới token với LinkedIn:**
       - Sau khi xác thực đúng phiên app, server BFF lấy `refresh_token` tương ứng trong DB cùng `client_secret` (lưu trong biến môi trường server) để gọi LinkedIn:
         ```http
         POST https://www.linkedin.com/oauth/v2/accessToken
         Content-Type: application/x-www-form-urlencoded

         grant_type=refresh_token
         &refresh_token={refresh_token}
         &client_id={client_id}
         &client_secret={client_secret}
         ```
     - **Phản hồi và Lưu trữ chuẩn phía Client:**
       - Server BFF nhận Access Token mới (thời hạn 60 ngày tiếp theo) và trả về an toàn trong JSON response body cho app Android qua kênh HTTPS.
       - **Lưu trữ chuẩn:** App cập nhật Access Token mới trực tiếp vào **`EncryptedSharedPreferences`**. Room DB **chỉ cập nhật metadata** `expiresAt = now + 60 days` và `ConnectionStatus.CONNECTED` (tuyệt đối không ghi token vào Room).
   - **Quy trình Thu hồi Phiên & Hủy `refresh_token` trên BFF khi Ngắt kết nối (`POST /auth/linkedin/logout`):**
     - **Rủi ro tồn đọng credential mồ côi (Orphaned Refresh Token Risk):** Khi app được duyệt quyền Partner Tier, máy chủ BFF lưu giữ `refresh_token` (thời hạn 365 ngày) trong cơ sở dữ liệu server để làm mới ngầm. Nếu khi người dùng bấm "Ngắt kết nối" (Disconnect) trên ứng dụng, app chỉ xóa token cục bộ trong `EncryptedSharedPreferences` mà không gửi lệnh hủy lên BFF, thì `refresh_token` vẫn tồn tại hợp lệ trên DB server, gây nguy cơ rò rỉ bảo mật và xung đột trạng thái nếu người dùng kết nối lại sau này.
     - **Cơ chế thu hồi phiên bắt buộc:** Khi người dùng bấm "Ngắt kết nối" (F1) tài khoản LinkedIn, ứng dụng Android gửi request thu hồi phiên lên BFF:
       ```http
       POST https://api.yourdomain.com/auth/linkedin/logout
       Authorization: Bearer {bff_session_token}
       Content-Type: application/json

       {
         "account_id": "urn:li:organization:{org-id}"
       }
       ```
     - **Xử lý tại Máy chủ BFF:**
       1. **Xác thực phiên:** BFF xác minh tính hợp lệ và quyền sở hữu của `bff_session_token` đối với `account_id`.
       2. **Xóa sạch Refresh Token:** BFF xóa ngay lập tức bản ghi `refresh_token` và access token cache tương ứng của tài khoản khỏi cơ sở dữ liệu server.
       3. **Vô hiệu hóa phiên:** Đưa `bff_session_token` vào danh sách thu hồi (revoked sessions store / blacklist), ngăn chặn mọi yêu cầu gọi `POST /auth/linkedin/refresh` về sau sử dụng phiên này.
       4. Trả về HTTP 200 OK (hoặc 204 No Content).
     - **Xử lý phía App Client:** Sau khi gửi request logout (theo cơ chế best-effort nếu mất kết nối mạng), app tiến hành xóa sạch `access_token`, `refresh_token` (nếu có), và `bff_session_token` trong `EncryptedSharedPreferences`, đồng thời cập nhật/xóa metadata trong Room DB (chi tiết tại §5.3).
   - **Lưu ý với Client Native thuần túy (không có BFF):** Nếu chạy Luồng A (Native PKCE trực tiếp) mà không có backend server, tính năng programmatic refresh sẽ không thể thực thi do thiếu `client_secret`. Khi đó, hệ thống bắt buộc sử dụng **Phương án 2 (Re-authentication định kỳ 60 ngày)** qua Custom Tabs (người dùng chỉ cần xác nhận 1 chạm).
   - *Thời hạn tối đa:* Việc refresh chỉ cấp lại access token 60 ngày mới, **không gia hạn** mốc 365 ngày ban đầu của refresh token. Sau 365 ngày, người dùng vẫn bắt buộc phải trải qua luồng Re-authentication qua trình duyệt.

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
| **Làm mới ngầm (F1 - Tùy chọn)** | `offline_access` *(Yêu cầu quyền Partner)* | Chỉ cấp cho app được duyệt **Programmatic Refresh (MDP)** để cấp refresh token 365 ngày (request refresh bắt buộc thực thi qua BFF server kèm `client_secret`). App thông thường dùng luồng Re-authentication định kỳ 60 ngày. |

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
3. **Đăng bài viết kèm ảnh đơn (F5 - Hỗ trợ Idempotency native & Mention cú pháp chuẩn):**
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

4. **Đăng bài viết đa ảnh (Multi-image Post — F5):**
   - **Quy trình tải lên:** Khởi tạo `initializeUpload` và tải dữ liệu nhị phân lên `uploadUrl` cho từng ảnh (tương tự quy trình ảnh đơn) để thu thập danh sách Media URN: `[urn:li:image:{id1}, urn:li:image:{id2}, ...]`.
   - **Đăng bài bằng thuộc tính `multiImage`:**
     ```http
     POST https://api.linkedin.com/rest/posts
     Headers:
       Authorization: Bearer {access-token}
       Linkedin-Version: 202610
       X-RestLi-Idempotency-Key: {idempotencyKey}
       Content-Type: application/json

     {
       "author": "urn:li:organization:{org-id}",
       "commentary": "Nội dung bài viết kèm nhiều hình ảnh...",
       "visibility": "PUBLIC",
       "distribution": {
         "feedDistribution": "MAIN_FEED",
         "targetEntities": [],
         "thirdPartyDistributionChannels": []
       },
       "content": {
         "multiImage": {
           "images": [
             { "id": "urn:li:image:{image-id-1}", "altText": "Mô tả ảnh 1" },
             { "id": "urn:li:image:{image-id-2}", "altText": "Mô tả ảnh 2" }
           ]
         }
       },
       "lifecycleState": "PUBLISHED"
     }
     ```
   - *Giới hạn kỹ thuật & Ràng buộc (theo tài liệu chính thức [Multi-image Post API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api)):*
     - **Số lượng ảnh bắt buộc:** Bắt buộc **tối thiểu 2 ảnh và tối đa 20 ảnh** (theo tài liệu Microsoft LinkedIn hiện hành; con số cũ "tối đa 9 ảnh" chỉ áp dụng cho tài liệu phiên bản 2023 và đã hoàn toàn lỗi thời).
     - **Độ phân giải & Định dạng:** Tài liệu Images API quy định tổng độ phân giải **dưới 36.152.320 pixel** (chiều rộng $\times$ chiều cao); định dạng hỗ trợ gồm JPG, GIF, PNG.
     - **Dung lượng file:** Con số "8 MB/ảnh" không có trong tài liệu chính thức của Images API (trạng thái: *Chưa xác minh trong tài liệu*; khuyến nghị thực tế nên giữ file dưới 8 MB - 10 MB để đảm bảo hiệu năng tải lên từ thiết bị di động).
     - *Phạm vi Milestone 1:* Model `PostDraft` hiện ưu tiên hỗ trợ `imageUri` (ảnh đơn), cấu trúc `multiImage` được chuẩn hóa sẵn sàng cho giai đoạn mở rộng tiếp theo.

5. **Lấy danh sách comment (F6):**
   *(Scope yêu cầu: `r_organization_social_feed` qua Community Management API)*
   ```http
   GET https://api.linkedin.com/rest/socialActions/{targetUrn}/comments
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
   ```
6. **Trả lời comment (F6):**
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
7. **Lấy số liệu bài viết (F7):**
   *(Scope yêu cầu: `rw_organization_admin` kèm vai trò Administrator của Organization)*
   
   Theo tài liệu chính thức về [Share Statistics API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics), endpoint này cung cấp hai tham số lọc riêng biệt tùy theo định dạng URN của bài viết:
   - **Trường hợp URN dạng Share (`urn:li:share:{id}`):** Dùng tham số `shares=List(...)`:
     ```http
     GET https://api.linkedin.com/rest/organizationalEntityShareStatistics?q=organizationalEntity&organizationalEntity=urn:li:organization:{org-id}&shares=List({shareUrn})
     Headers:
       Authorization: Bearer {access-token}
       Linkedin-Version: 202610
     ```
   - **Trường hợp URN dạng UGC Post (`urn:li:ugcPost:{id}`):** Dùng tham số `ugcPosts=List(...)`:
     ```http
     GET https://api.linkedin.com/rest/organizationalEntityShareStatistics?q=organizationalEntity&organizationalEntity=urn:li:organization:{org-id}&ugcPosts=List({ugcPostUrn})
     Headers:
       Authorization: Bearer {access-token}
       Linkedin-Version: 202610
     ```

   *Quy tắc triển khai & Lưu ý kiểm chứng thực tế:*
   - Bài viết tạo qua endpoint `POST /rest/posts` thường trả về header định danh `x-restli-id` dưới một trong hai dạng URN chính thức: `urn:li:share:...` (Share URN) hoặc `urn:li:ugcPost:...` (UGC Post URN). *(Lưu ý: Tiền tố `urn:li:post:...` không có trong tài liệu Share Statistics chính thức, do đó nhóm chỉ ánh xạ theo hai định dạng chuẩn `share` và `ugcPost` của Microsoft).*
   - Mã nguồn Mảng B cần phân tích tiền tố của URN bài viết lưu trong Room DB để tự động ánh xạ sang tham số tương ứng (`shares` hay `ugcPosts`).
   - ⚠️ *Cần kiểm chứng thực tế:* Do `organizationalEntityShareStatistics` là endpoint thống kê kế thừa, nhóm cần gọi thử nghiệm trực tiếp trên môi trường dev với ID bài đăng vừa tạo từ `POST /rest/posts` để xác nhận định dạng URN tương thích chính xác trên phiên bản `202610`.
   - *Dữ liệu trả về:* `likeCount`, `commentCount`, `shareCount`, `impressionCount`, `clickCount`, `engagement`.
8. **Lấy số follower (F7):**
   *(Scope yêu cầu: `rw_organization_admin` hoặc `r_organization_social`)*
   ```http
   GET https://api.linkedin.com/rest/networkSizes/urn:li:organization:{org-id}?edgeType=COMPANY_FOLLOWED_BY_MEMBER
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
   ```
   *Trả về:* `firstDegreeSize`.
   
   *Lưu ý về tham số `edgeType`:*
   - Kể từ phiên bản API `202305` trở đi (bao gồm bản `202610`), LinkedIn chuyển đổi định dạng enum `edgeType` sang chuẩn **`UPPER_SNAKE_CASE`**: bắt buộc dùng **`COMPANY_FOLLOWED_BY_MEMBER`**.
   - Giá trị dạng camelCase cũ (`CompanyFollowedByMember`) chỉ áp dụng cho các phiên bản cũ $\le 202304$ và sẽ bị từ chối trên các versioned REST API hiện hành.

### 3.4. Giới hạn (Rate Limits) & Quy định kiểm duyệt
- **Độ dài bài viết tối đa:** `3,000` ký tự (khớp cấu hình trong `ChannelType.LINKEDIN(maxChars = 3_000)`).
- **Kích thước ảnh & Định dạng:**
  - **Giới hạn pixel (Chính thức theo Images API):** Tổng độ phân giải mỗi ảnh phải **dưới 36.152.320 pixel** (chiều rộng $\times$ chiều cao). Định dạng hỗ trợ: JPG/JPEG, GIF, PNG.
  - **Dung lượng file:** Trạng thái: *Chưa xác minh mức trần cụ thể trong tài liệu Images API* (con số 8 MB là khuyến nghị thực tế để tránh timeout khi tải lên di động).
  - Tỉ lệ khuyến nghị: `1.91:1` (ngang: 1200 x 627 px) hoặc `1:1` (vuông: 1200 x 1200 px).
- **Hạn ngạch & Giới hạn tần suất gọi (Rate Limits & Quotas theo Product / Tier):**
  - **Sản phẩm Share on LinkedIn (Đăng bài cá nhân):**
    - **Hạn ngạch thành viên (Member Throttle):** Tối đa **150 requests / member / ngày** (chu kỳ 24 giờ).
      - *Trạng thái:* Đã xác minh theo tài liệu chính thức của LinkedIn.
      - *Nguồn chính thức:* Microsoft Learn — [Share on LinkedIn: Rate Limits](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/share-on-linkedin#rate-limits) & [LinkedIn API Rate Limits Overview](https://learn.microsoft.com/en-us/linkedin/shared/api-guide/concepts/rate-limits).
    - **Hạn ngạch toàn ứng dụng (Application Throttle):** Tối đa **100.000 requests / app / ngày**.
    - **Phân biệt rõ giữa "Request API" và "Số bài đăng":**
      - Hạn mức của LinkedIn tính theo **số lần gọi API (API requests)**, KHÔNG đồng nhất với số bài đăng thực tế.
      - *Bài đăng văn bản thuần (Text-only) hoặc chia sẻ URL:* Mỗi bài tốn đúng **1 request** (`POST /rest/posts`) $\rightarrow$ Thành viên có thể đăng tối đa đến 150 bài text/ngày nếu không gọi endpoint nào khác.
      - *Bài đăng kèm hình ảnh (Media Image):* Quy trình chuẩn của LinkedIn Versioned REST API tốn từ **2 - 3 requests** (1 request `initializeUpload` để đăng ký upload, 1 request PUT binary lên uploadUrl, và 1 request `POST /rest/posts` chứa image URN). Do đó, hạn mức 150 requests/ngày thực tế tương ứng với khoảng **50 - 75 bài đăng kèm ảnh / member / ngày**.
  - **Sản phẩm Community Management API (Page Công ty — F6/F7):**
    - **Development Tier (Hạn ngạch giai đoạn phát triển & thử nghiệm):**
      - *Trạng thái:* Đã xác minh qua thực tế đo lường trên tab Analytics của LinkedIn Developer Portal.
      - **Application Throttle (Toàn ứng dụng):** Tối đa **500 API calls / app / 24 giờ (UTC)**.
      - **Member Throttle (Mỗi thành viên):** Tối đa **100 API calls / member / 24 giờ (UTC)**.
      - *Phạm vi áp dụng:* Tính gộp cho toàn bộ các endpoint thuộc sản phẩm: đọc/trả lời comment (`socialActions/comments`), truy vấn số liệu tương tác (`organizationalEntityShareStatistics`) và số follower (`networkSizes`).
      - *Chu kỳ làm mới:* Tự động reset hàng ngày vào lúc **00:00 UTC** (tức 07:00 sáng theo giờ Việt Nam).
    - **Standard Tier (Production):** 
      - *Trạng thái:* Chưa xác minh cụ thể (chờ cấp phép).
      - Hạn ngạch Production được cấp riêng dựa trên phê duyệt use-case thực tế sau khi nộp đơn (Access Request Form) và video demo luồng hoạt động; thông số áp dụng theo thỏa thuận SLA của từng ứng dụng.
  - **Cơ chế xử lý khi vượt ngưỡng:** Trả về HTTP `429 Too Many Requests` (kèm header `Retry-After`) $\rightarrow$ Map sang `PublishResult.RetryableError` với thuật toán Exponential Backoff.
- **Theo dõi Quota & Usage thực tế theo từng endpoint trên LinkedIn Developer Portal:**
  - Theo tài liệu chính thức từ LinkedIn về [API Rate Limits](https://learn.microsoft.com/en-us/linkedin/shared/api-guide/concepts/rate-limits), LinkedIn **không công bố một hạn mức chuẩn chung phổ quát** cho tất cả các endpoint, mà rate limit được thiết lập riêng biệt theo từng API Product, từng endpoint và cấp độ ứng dụng.
  - Do đó, **tài liệu ưu tiên phương pháp đối chiếu và kiểm tra trực tiếp hạn ngạch theo từng endpoint trên tab Analytics của Developer Portal**:
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
   - *Meta (Facebook):* Chặn hoàn toàn API mention tài khoản cá nhân (Personal Profile) để chống spam và bảo vệ quyền riêng tư. Đối với việc mention các Facebook Page khác bằng cú pháp `@[page_id]`, tính năng này hiện ở trạng thái **⚠️ Chưa xác minh chính thức từ tài liệu Meta (cần kiểm thử thực tế)**; nếu Graph API chỉ render text thô, app tự động kích hoạt luồng `HANDED_OFF` để người dùng tag trực tiếp trên app Facebook.
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
2. **Chia sẻ qua Android Share Intent (`Intent.ACTION_SEND` / `Intent.ACTION_SEND_MULTIPLE`):**
   - Hỗ trợ cả text và file ảnh (thông qua `androidx.core.content.FileProvider` để cấp quyền đọc Uri tạm thời):
   - **Bài viết text hoặc kèm 1 ảnh đơn (`Intent.ACTION_SEND` — chuẩn `Draft` Milestone 1):**
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
   - **Bài viết kèm nhiều ảnh (`Intent.ACTION_SEND_MULTIPLE` — khi mở rộng danh sách ảnh trong tương lai):**
     ```kotlin
     val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
         type = "image/*"
         putExtra(Intent.EXTRA_TEXT, draft.text)
         val imageUris = arrayListOf<Uri>(/* danh sách Uri các ảnh đã cấp FileProvider */)
         putParcelableArrayListExtra(Intent.EXTRA_STREAM, imageUris)
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
- **Kiểm tra loại hình đăng nhập trên Dashboard (Facebook Login vs Login for Business):**
  - Do app thuộc loại `Business`, Meta có thể yêu cầu sử dụng **Facebook Login for Business** thay cho Facebook Login cổ điển.
  - **Cần kiểm tra trên Dashboard:**
    - Nếu app kích hoạt *Facebook Login*: Luồng OAuth sử dụng tham số `scope=...` như thông thường.
    - Nếu app kích hoạt *Facebook Login for Business*: Nhóm cần vào mục *Facebook Login for Business $\rightarrow$ Configurations*, tạo Configuration chứa các quyền cần xin và lấy **`config_id`** để nạp vào app (URL OAuth truyền `config_id` thay vì `scope`).
- **App ID (Public Identifier):** Đã tạo và cấu hình Android Platform:
  - Package Name: `com.lttbdd.socialagent`
  - Key Hash: Tạo từ debug keystore SHA-1 fingerprint (chỉ áp dụng cho Phương án 1 — Meta Android SDK).
- **Cấu hình Valid OAuth Redirect URIs (trong Facebook Login Settings):**
  - **Phương án 1 (SDK):** Không cần đăng ký Redirect URI cho web dialog vì SDK dùng cơ chế App Switch / Key Hash nội bộ.
  - **Phương án 2 (Web OAuth qua BFF):** Bắt buộc đăng ký URL HTTPS của máy chủ backend (ví dụ: `https://api.yourdomain.com/auth/facebook/callback`). Theo quan sát thực tế trên Dashboard, mục *Valid OAuth Redirect URIs* yêu cầu URL dạng `https://` và chưa có tài liệu chính thức nào của Meta xác nhận cho phép lưu custom scheme (`com.lttbdd.socialagent://...`) cho luồng Web OAuth.
- **Cơ chế xác thực không dùng App Secret (Đối chiếu kiến trúc §2.1):**
  - **Phương án 1 (Meta Android SDK — Khuyến nghị):** Meta xác thực danh tính ứng dụng thông qua bộ ba **App ID + Package Name + Android Key Hash**. Cơ chế này do SDK đảm nhiệm, không cần `app_secret` trong ứng dụng.
  - **Phương án 2 (Web OAuth qua AppAuth / Custom Tabs):**
    - ⚠️ **Trạng thái: Theo quan sát thực tế, chưa xác minh chính thức từ tài liệu Meta (thử nghiệm)**. Việc dùng Custom Scheme trực tiếp làm `redirect_uri` hoặc trao đổi token bằng PKCE (`code_verifier`) không secret chưa có căn cứ chính thức từ tài liệu Graph API cho ứng dụng di động thông thường.
    - Android Key Hash đã cấu hình trên Portal **chỉ áp dụng cho Phương án 1 (SDK), không áp dụng cho Phương án 2 (Web OAuth)**.
    - Nếu sử dụng Phương án 2, luồng triển khai chuẩn là Meta redirect HTTPS về máy chủ BFF; server BFF giữ bí mật `app_secret` để đổi mã ủy quyền lấy Long-lived Token và cấp mã trao đổi dùng một lần (`exchange_code`) về app Android qua back-channel; file APK tuyệt đối không nhúng `app_secret`.
  - **Kiểm tra Token an toàn:** App kiểm tra token trực tiếp qua endpoint dữ liệu `GET /v26.0/me`, tránh phát sinh thêm network round-trip và token phụ khi gọi endpoint quản trị `/debug_token`.
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
    - Cho Luồng Standard qua BFF: Bắt buộc dùng URL HTTPS trỏ về máy chủ backend/BFF (ví dụ: `https://api.yourdomain.com/auth/linkedin/callback` hoặc Cloudflare Worker endpoint). LinkedIn yêu cầu redirect URL chuẩn HTTPS đối với Standard OAuth, không cho phép đăng ký trực tiếp custom scheme.

### 5.3. Hướng dẫn Cấu hình Định danh Cục bộ (Public Client IDs Configuration)
Thay vì lưu `client_secret`, ứng dụng chỉ quản lý các **Public Client IDs**:

1. **Khai báo trong `local.properties` (hoặc cấu hình qua biến môi trường / Gradle properties):**
   ```properties
   # Public Client Identifiers (Không phải bí mật, nhưng tách ra để linh hoạt môi trường dev/staging)
   FACEBOOK_APP_ID=your_facebook_app_id_here
   FACEBOOK_CONFIG_ID=your_facebook_config_id_here  # Bổ sung nếu app yêu cầu Facebook Login for Business
   LINKEDIN_CLIENT_ID=your_linkedin_client_id_here
   ```
2. **Cơ chế nạp vào `BuildConfig`:**
   - Khi triển khai F1, `app/build.gradle.kts` nạp `FACEBOOK_APP_ID`, `FACEBOOK_CONFIG_ID` (tùy chọn) và `LINKEDIN_CLIENT_ID` vào `BuildConfig` (`buildFeatures.buildConfig = true`).
   - Có cơ chế fallback giá trị mặc định khi chạy trên CI GitHub Actions để lệnh `./gradlew testDebugUnitTest` và `./gradlew assembleDebug` không bị lỗi compile.
3. **Bảo mật Token phía Client (Bảo vệ dữ liệu người dùng):**
   - **Tách biệt Token và Metadata (Principle of Least Privilege):**
     - **Chỉ lưu Token trong `EncryptedSharedPreferences`:** Toàn bộ token nhạy cảm gồm:
       - User Access Token (`access_token`), Refresh Token (`refresh_token` nếu có), và mã phiên `bff_session_token` (cho các luồng qua BFF).
       - **Page Access Token của từng Facebook Page:** Toàn bộ các tác vụ F5 (đăng bài), F6 (đọc/trả lời comment) và F7 (lấy số liệu insights) của Facebook đều thực hiện dưới danh nghĩa Page và bắt buộc sử dụng Page Access Token. Từng token này được lưu an toàn trong `EncryptedSharedPreferences` và tra cứu theo `page_id` / `accountId` (ví dụ key: `fb_page_token_{page_id}`).
       - Tất cả token đều được mã hóa bằng Android Keystore (`MasterKey.DEFAULT_AES256_GCM_SPEC`).
     - **Room Database tuyệt đối KHÔNG lưu plaintext token:** Room chỉ lưu trữ các metadata phi nhạy cảm của tài khoản và kênh/Page: `accountId`, `channelType`, `accountName`, `avatarUrl`, `expiresAt`, `connectionStatus`, `capabilities`. Khi gọi API, Repository sẽ lấy metadata từ Room và truy xuất token (hoặc Page token tương ứng theo `page_id`) từ `EncryptedSharedPreferences`.
   - Không ghi log token ra Logcat, không đính kèm token trong thông báo lỗi hoặc crash report.
   - **Quy trình bảo mật khi "Ngắt kết nối" (Disconnect Flow — F1):**
     Khi người dùng bấm ngắt kết nối một tài khoản hoặc kênh trên ứng dụng:
     - **Đối với Kênh Facebook:**
       - *Nếu dùng luồng Web OAuth qua BFF:* App gửi request `POST /auth/facebook/logout` kèm header `Authorization: Bearer {bff_session_token}` để BFF vô hiệu hóa phiên làm việc và dọn dẹp session cache phía server.
       - *Nếu dùng Meta SDK:* Gọi `LoginManager.getInstance().logOut()` để SDK xóa bỏ phiên người dùng trong bộ nhớ cache của SDK.
       - *Dọn dẹp phía Client:* Xóa sạch toàn bộ User Access Token, mã phiên `bff_session_token`, và **toàn bộ Page Access Token** (`fb_page_token_{page_id}`) của các Page thuộc tài khoản đó khỏi `EncryptedSharedPreferences`; cập nhật hoặc xóa metadata kênh trong Room DB.
     - **Đối với Kênh LinkedIn:**
       - *Bắt buộc thu hồi phía BFF (`POST /auth/linkedin/logout`):* Đối với các phiên kết nối qua BFF (đặc biệt khi BFF lưu trữ `refresh_token` 365 ngày trên server DB phục vụ làm mới ngầm khi được duyệt Partner Tier), app **bắt buộc gửi request `POST /auth/linkedin/logout`** kèm header `Authorization: Bearer {bff_session_token}` và `account_id`. Máy chủ BFF sẽ lập tức **xóa bỏ hoàn toàn `refresh_token` khỏi database server** và vô hiệu hóa `bff_session_token`. Thao tác này ngăn chặn triệt để tình trạng `refresh_token` vẫn tồn đọng mồ côi trên máy chủ sau khi người dùng đã ngắt kết nối trên thiết bị di động.
       - *Dọn dẹp phía Client:* Xóa sạch `access_token`, `refresh_token` (nếu có), và `bff_session_token` khỏi `EncryptedSharedPreferences`; cập nhật trạng thái kênh trong Room DB thành `ConnectionStatus.DISCONNECTED` (hoặc xóa tài khoản/kênh khỏi Room DB).

---

## 6. Tóm tắt nội dung dùng cho Slide Báo cáo (Research Presentation)

Dưới đây là dàn ý nội dung được thiết kế sẵn theo từng slide (kèm gợi ý hình ảnh/sơ đồ và lời thuyết trình) giúp nhóm dễ dàng đưa vào PowerPoint / Google Slides:

### 🎯 Slide 1: So sánh khả năng kênh & Đối chiếu Kiến trúc (Channel Capabilities)
* **Ý chính trình chiếu:**
  * Đối chiếu trực tiếp với `enum class ChannelCapability`: `PUBLISH`, `READ_COMMENTS`, `REPLY`, `MENTION`, `METRICS`.
  * **Facebook Page:** Hỗ trợ toàn diện 5 capabilities dưới tư cách Page (Graph API v26.0); không hỗ trợ native Idempotency $\rightarrow$ Quản lý qua Room DB cục bộ.
  * **LinkedIn Organization:** Hỗ trợ đầy đủ cho Company Page; hỗ trợ native header `X-RestLi-Idempotency-Key`.
  * **LinkedIn Member (Cá nhân):** Chỉ hỗ trợ đăng bài cơ bản; quyền đọc/trả lời comment không khả dụng với quyền hiện có của app.
* **Gợi ý Visual:** Bảng ma trận so sánh 3 cột (Facebook Page | LinkedIn Org | LinkedIn Member) với các icon tick xanh ✅ và cảnh báo ⚠️.

---

### 🔐 Slide 2: Kiến trúc Xác thực An toàn — Public Client Không Giữ Secret
* **Ý chính trình chiếu:**
  * **Chuẩn bảo mật Native (RFC 8252 & RFC 6750):** Ứng dụng Android là Public Client $\rightarrow$ Loại bỏ hoàn toàn `client_secret` khỏi APK và mã nguồn. Tuyệt đối không truyền `access_token` trong URL/Deep Link.
  * **Cơ chế bảo vệ mã ủy quyền:** Tích hợp `AppAuth-Android` qua Android Custom Tabs với PKCE S256 (`code_verifier` + `code_challenge`) và xác thực `state` chống CSRF.
  * **Định danh ứng dụng an toàn:**
    * *Facebook:* Meta Android SDK (App ID + Package Name + SHA-1 Key Hash) hoặc Web OAuth qua BFF (Android App Links + PKCE-for-BFF).
    * *LinkedIn:* Client ID công khai + luồng Native PKCE (`/oauth/native-pkce/`) qua Loopback Redirect không giữ secret (hoặc qua BFF với Android App Links HTTPS, cấp mã exchange dùng 1 lần và đổi token qua POST back-channel kèm `code_verifier`).
  * **Vòng đời & Lưu trữ Token an toàn:** 
    * *Facebook:* Với Meta SDK, User Token (~60 ngày) tự động làm mới ngầm khi mở app (Page Token kiểm thử qua `debug_token`); với Web OAuth, đổi qua BFF lấy Page Access Token vĩnh viễn (Never Expires).
    * *LinkedIn:* Access Token 60 ngày $\rightarrow$ Luồng Re-authentication 1 chạm khi hết hạn (kèm cơ chế Auto-refresh 365 ngày qua BFF nếu được duyệt quyền Programmatic Refresh).
    * *Lưu trữ & Ngắt kết nối:* Mọi token nhạy cảm (`access_token`, `refresh_token`, `bff_session_token`, cùng Page Access Token tra cứu theo `page_id`) chỉ lưu trong `EncryptedSharedPreferences` (Keystore); Room DB tuyệt đối không lưu token mà chỉ quản lý metadata phi nhạy cảm; ngắt kết nối kích hoạt `POST logout` lên BFF để hủy phiên (Facebook) và xóa sạch `refresh_token` trên database server (LinkedIn), đồng thời dọn sạch mọi token client (kể cả Page tokens).
* **Gợi ý Visual:** Sơ đồ luồng OAuth 2.0 PKCE không `client_secret` đối chiếu giữa Custom Tabs và Token Endpoint / BFF Back-Channel.

---

### ⚙️ Slide 3: Endpoints Cốt lõi & Giới hạn Kỹ thuật (Technical Specs)
* **Ý chính trình chiếu:**
  * **Đăng bài có ảnh (F5):**
    * *Facebook:* 1 bước qua `POST /{page-id}/photos` (ảnh đơn) hoặc 2 bước qua `attached_media` (đa ảnh).
    * *LinkedIn:* 2 bước qua `initializeUpload` $\rightarrow$ PUT binary $\rightarrow$ Đăng bài với Image URN (ảnh đơn) hoặc `multiImage` (tối thiểu 2 ảnh, tối đa 20 ảnh — đã cập nhật từ trần cũ 9 ảnh; < 36.152.320 px/ảnh).
  * **Tương tác & Số liệu (F6, F7):** Đọc comment phân cấp, reply theo ID/URN, lấy insights (sử dụng metric `post_media_view` chuẩn trên Graph API v26.0) và follower count.
  * **Giới hạn kiểm soát:**
    * *Độ dài bài:* Facebook (63.206 ký tự) vs LinkedIn (3.000 ký tự) $\rightarrow$ Đã cấu hình cứng trong `ChannelType`.
    * *Kích thước ảnh:* Facebook (4 MB multipart — thực nghiệm chưa xác minh) vs LinkedIn (dưới 36.152.320 pixel/ảnh).
    * *Rate limit:* Facebook (giới hạn động theo BUC / Page Engaged Users) vs LinkedIn Community Management Dev Tier (500 req/app, 100 req/member/ngày UTC) & Share on LinkedIn (150 req/member/ngày; ưu tiên theo dõi hạn ngạch thực tế theo endpoint trên Portal Analytics).
* **Gợi ý Visual:** Bảng so sánh 2 cột trực quan các chỉ số dung lượng, ký tự và Rate Limit.

---

### 🛡️ Slide 4: Rào cản Chính sách & Giải pháp "Đã bàn giao" (`HANDED_OFF`)
* **Ý chính trình chiếu:**
  * **Rào cản API bất khả kháng:**
    1. Meta cấm bên thứ ba đăng bài lên Facebook cá nhân (đóng quyền `publish_actions` từ 2018).
    2. Meta cấm mention profile cá nhân (LinkedIn hỗ trợ mention member khi có URN hợp lệ: `@[Tên](urn:li:person:{id})`).
    3. Rào cản kiểm duyệt App Review khắt khe (đòi hỏi giấy phép kinh doanh).
  * **Giải pháp 1 (Nội bộ):** Chạy ứng dụng ở **Development Mode** $\rightarrow$ Dành cho tài khoản được gán vai trò Tester/Developer trong App Roles và có Page Tasks quản trị trên Facebook Page thử nghiệm.
  * **Giải pháp 2 (Dự phòng F5 - `HANDED_OFF`):** 
    * Khi không thể gọi API (hoặc gặp lỗi phân quyền vĩnh viễn mã 10, 200–299): Tự động copy text vào Clipboard $\rightarrow$ Kích hoạt Android Share Intent native $\rightarrow$ Cập nhật trạng thái `PostStatus.HANDED_OFF`.
    * Đảm bảo luồng trải nghiệm không bao giờ đứt gãy.
* **Gợi ý Visual:** Sơ đồ rẽ nhánh: [Agent soạn bài] $\rightarrow$ [Đủ quyền API? $\rightarrow$ Gọi API] HOẶC [Thiếu quyền $\rightarrow$ Copy Clipboard + Mở Share Sheet $\rightarrow$ Badge HANDED_OFF].

---

### 🚀 Slide 5: Kiến nghị Contract & Lộ trình Milestone 1
* **Ý chính trình chiếu:**
  * **Kiến nghị Contract (`SocialChannelClient`):** Đề xuất mở Issue nhãn `contract` để bổ sung `SocialChannelClientFactory` giải quyết ngữ cảnh đa tài khoản/đa Page (`accountId`).
  * **Kế hoạch triển khai:**
    * *Tuần 1:* Họp nhóm chốt contract interface `SocialChannelClientFactory` & hoàn thiện cấu hình Developer Portals.
    * *Tuần 2:* Triển khai thư viện AppAuth, màn hình "Kênh của tôi" (F1), tích hợp EncryptedSharedPreferences lưu token an toàn và Room DB quản lý metadata.
* **Gợi ý Visual:** Timeline lộ trình thực hiện theo tuần của Milestone 1.
