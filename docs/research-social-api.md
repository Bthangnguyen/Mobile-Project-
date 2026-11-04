# Nghiên cứu API Facebook Page & LinkedIn (F1, F5, F6, F7)

> **Tài liệu thuộc Issue #5**: `[Research] API Facebook Page và LinkedIn cho F1/F5/F6/F7`  
> **Chủ sở hữu:** Mảng B (Mạng xã hội — `@dhminh678`)  
> **Milestone:** Milestone 1  
> **Mục tiêu:** Nắm vững luồng xác thực OAuth 2.0 (PKCE) trên Android, quyền hạn, endpoint, giới hạn rate limit/App Review, giải pháp dự phòng và kiến trúc tích hợp cho `core/social/SocialChannelClient`.

## 0. Phạm vi và trạng thái quyết định

Tài liệu này là kết quả nghiên cứu, không phải cam kết rằng mọi endpoint hoặc quyền trong bảng dưới đây
đã được Meta/LinkedIn cấp cho ứng dụng `SocialAgent`. Các nội dung có nhãn **Chưa xác minh**, **Cần kiểm
thử thực tế** hoặc **Đề xuất** không được dùng làm căn cứ duy nhất để triển khai production.

### Quyết định áp dụng cho Milestone 1

1. **Không nhúng `client_secret` hoặc `app_secret` vào APK.** Client Android chỉ giữ các định danh công
   khai và token trong secure storage theo quy định của `AGENTS.md`.
2. **Facebook:** Pages API hiện ưu tiên Facebook Login for Business cho app quản lý Page; Meta Android
   SDK có thể được dùng cho trải nghiệm đăng nhập native nếu cấu hình sản phẩm/Dashboard phù hợp.
   Web OAuth qua BFF chỉ là phương án dự phòng và chưa thuộc phạm vi Android app hiện tại; không tự
   giả định rằng PKCE thủ công có thể đổi token mà không cần secret.
3. **LinkedIn:** chỉ dùng Native PKCE nếu Developer Portal xác nhận protocol và redirect tương ứng cho App
   ID của nhóm. Nếu chưa được cấp, đánh dấu tính năng kết nối LinkedIn là blocked thay vì tự triển khai
   Standard OAuth bằng custom scheme.
4. **BFF không thuộc phạm vi của repository Android này.** Các endpoint `/auth/*`, việc lưu
   `refresh_token` phía server và chính sách logout server phải được thiết kế trong một issue/ repository
   backend riêng trước khi đưa vào mã Android.
5. **Không thay đổi chữ ký `core/social/SocialChannelClient` chỉ để phục vụ LinkedIn.** Repository/client
   phải tra cứu `Comment.remotePostId` để lấy URN bài viết gốc khi trả lời comment; implementation thật
   dự kiến nằm ở `data/social/FacebookClient.kt` và `data/social/LinkedInClient.kt`, mọi thay đổi contract
   cần issue `contract` theo `docs/architecture.md`.

### Phân loại độ tin cậy

| Nhãn | Cách sử dụng |
|---|---|
| **Đã đối chiếu tài liệu chính thức** | Có thể dùng làm cơ sở thiết kế, nhưng vẫn cần kiểm thử integration trước khi phát hành. |
| **Cần kiểm thử thực tế** | Chỉ dùng để lập kế hoạch test; không hard-code hành vi thành công. |
| **Đề xuất/quan sát** | Không xem là giới hạn hoặc capability chính thức cho đến khi có nguồn xác nhận. |

---

## 1. Tổng quan & So sánh khả năng (Channel Capabilities)

Đối chiếu trực tiếp với enum `ChannelCapability` trong `core/model/Channel.kt`:
```kotlin
enum class ChannelCapability { PUBLISH, READ_COMMENTS, REPLY, MENTION, METRICS }
```

### Bảng ma trận khả năng của từng kênh (Capability Matrix)

> *Ghi chú về định nghĩa:* `ChannelCapability` bao gồm 5 giá trị: `PUBLISH`, `READ_COMMENTS`, `REPLY`, `MENTION`, `METRICS`. Số lượng người theo dõi (`followersCount`) là trường dữ liệu được gán kèm trong thông tin tài khoản hoặc truy vấn qua `METRICS`, không phải một hằng số enum riêng biệt.

| Khả năng (`ChannelCapability`) | Facebook Page (Meta Graph API, ví dụ `v26.0`) | LinkedIn Organization (Company Page) | LinkedIn Member (Trang cá nhân) |
|---|---|---|---|
| **`PUBLISH` (Đăng bài)** | ✅ **Có** (Text, link, ảnh đơn qua `/{page-id}/photos`, đa ảnh qua `attached_media`) | ✅ **Có điều kiện** (Text, link, ảnh đơn; `multiImage` chỉ dùng khi product/quyền của app hỗ trợ, giới hạn phải đối chiếu tài liệu version hiện hành) | ✅ **Có điều kiện** (Text, link, ảnh đơn khi app được cấp scope/product đăng bài phù hợp; không coi việc bật sản phẩm là tự động cấp quyền cho mọi app) |
| **`READ_COMMENTS` (Đọc comment)** | ✅ **Có** (Comment cấp 1, reply cấp 2, paging limit/offset) | ✅ **Có điều kiện** (Đọc comment và reaction khi product, scope và role của app được cấp) | ❌ **Không khả dụng** (Tài liệu Social Metadata API quy định `r_member_social` là Closed Permission — quyền đóng, hiện không nhận đơn truy cập; không hỗ trợ đọc comment Member qua API, kích hoạt `HANDED_OFF`) |
| **`REPLY` (Trả lời comment)** | ✅ **Có** (Gửi reply comment dưới tư cách Page) | ✅ **Có điều kiện** (Gửi reply khi product, scope và role của app được cấp) | ⚠️ **Bị hạn chế / Cần kiểm tra Portal** (Từ 06/2023, tài liệu Social Actions liệt kê scope mới `w_member_social_feed` thay cho `w_member_social`; cần kiểm tra trên Developer Portal xem app có được cấp không, nếu không thì kích hoạt `HANDED_OFF`) |
| **`MENTION` (Nhắc tên)** | ⚠️ **Chưa coi là capability mặc định** (Chỉ bật sau khi tài liệu của endpoint/version và Page task xác nhận cú pháp, định danh Page-scoped ID và quyền tương ứng; không suy ra có API mention tự do mọi hồ sơ cá nhân.) | ⚠️ **Cần kiểm thử theo endpoint/product** (Có thể dùng Organization hoặc Member URN hợp lệ nếu Posts API và quyền của app hỗ trợ; không coi cú pháp mention là capability được bảo đảm chung.) | ⚠️ **Cần kiểm thử theo endpoint/product** (Có thể dùng Organization hoặc Member URN hợp lệ nếu Posts API và quyền của app hỗ trợ; không coi cú pháp mention là capability được bảo đảm chung.) |
| **`METRICS` (Số liệu)** | ✅ **Có** (Reactions, Comments, Shares, và metric Insights được version hiện hành hỗ trợ) | ✅ **Có điều kiện** (Organization Share Statistics và follower statistics khi product, scope và role của app được cấp) | ❌ **Không khả dụng** (Do `r_member_social` là Closed Permission, API không hỗ trợ đọc thống kê bài viết Member; kích hoạt `HANDED_OFF`) |
| **Số follower (`followersCount`)** *(Dữ liệu kèm `METRICS`)* | ⚠️ Dùng field `followers_count` nếu field và quyền được version hiện hành cho phép; không dùng metric Insights `page_fans` đã deprecated | ⚠️ `firstDegreeSize` qua `networkSizes` chỉ khi endpoint, scope, role và product hiện hành cho phép | ❌ **Không hỗ trợ mặc định** (Không suy ra follower cá nhân từ số kết nối; chỉ dùng endpoint/member permission nếu LinkedIn cấp rõ ràng) |
| **Idempotency native** | ❌ Không hỗ trợ native (App tự quản lý qua Room DB) | ❌ **Không hỗ trợ native** (Tài liệu Posts API không công bố header idempotency chung; app tự quản lý chống trùng lặp qua Room DB) | ❌ **Không hỗ trợ native** (Tài liệu Posts API không công bố header idempotency chung; app tự quản lý chống trùng lặp qua Room DB) |
| **Tập hợp gán cho `ChannelAccount.capabilities` (F1)** | `setOf(PUBLISH, READ_COMMENTS, REPLY, METRICS)`; chỉ thêm `MENTION` sau khi Page feature/task và endpoint được xác nhận | `setOf(PUBLISH, READ_COMMENTS, REPLY, METRICS)`; chỉ thêm `MENTION` sau khi product/version xác nhận | Chỉ thêm `PUBLISH` sau khi token có scope/product đăng bài thực tế; không thêm `READ_COMMENTS`, `REPLY`, `METRICS` hoặc `MENTION` nếu chưa được cấp và kiểm thử |

---

## 2. Kênh Facebook Page (Meta Graph API)

> [!IMPORTANT]
> **Chính sách Phiên bản & Vòng đời Meta Graph API (Version Sunset Policy):**
> - **Chu kỳ phát hành & Vòng đời:** Meta phát hành phiên bản Graph API định kỳ khoảng **4–5 tháng một lần**, mỗi phiên bản có thời gian hỗ trợ chính thức **tối thiểu 2 năm** (thực tế kéo dài khoảng 2 năm 4 tháng đến 2 năm 5 tháng theo bảng versions của Meta, ví dụ: v24.0 ra mắt 08/10/2025 hết hạn 18/02/2028 ~ 28 tháng; v25.0 ra mắt 18/02/2026 hết hạn 29/07/2028 ~ 29 tháng) trước khi bị ngừng hoạt động (sunset).
> - **Tình trạng version cũ:** Không hard-code ngày sunset của một version trong tài liệu triển khai; trước khi gọi API phải kiểm tra changelog/versioning chính thức của Meta vì version cũ có thể bị từ chối hoặc không còn được hỗ trợ.
> - **Phiên bản áp dụng chuẩn hóa:** Tài liệu dùng `v26.0` (phát hành ngày 29/07/2026) làm ví dụ kiểm thử tại thời điểm cập nhật. Trước khi triển khai, phải xác nhận version đang Active và ngày sunset trên changelog/versioning chính thức của Meta.
> - **Thay đổi kỹ thuật quan trọng & Metric Deprecation:**
>   - **Gỡ bỏ metric hàng loạt:** Meta đã deprecated nhiều metric Insights cho mọi Graph API version. Theo tài liệu chính thức:
>     - Các mốc deprecate trước đó: `page_impressions_unique` và `post_impressions_unique` từ **15/06/2025**, nhóm `page_fans` và nhiều metric impressions từ **15/11/2025**. *(Lưu ý về mâu thuẫn tài liệu Meta: Trang Page Insights Reference ghi "By June 15, 2026", trong khi trang Deprecated Metrics List ghi mốc "June 15, 2025"; tài liệu Meta tự mâu thuẫn giữa 2 trang này. Đồ án tuân theo danh sách có ngày cụ thể đã phát sinh hiệu lực năm 2025 để đảm bảo an toàn).*
>     - **Deprecate bổ sung trên v26.0 (theo Changelog v25.0):** Meta công bố khi `v26.0` phát hành, các metric sau bị **gỡ bỏ hoàn toàn cho mọi version**: **`page_reach`**, **`page_post_reach`**, **`video_impressions`** và **`story_impressions`**.
>   - **Danh sách metric TUYỆT ĐỐI TRÁNH DÙNG:** `post_impressions`, `post_impressions_unique`, `page_impressions`, `page_impressions_unique`, `page_fans`, `page_reach`, `page_post_reach`, `video_impressions`, `story_impressions`.
>   - **Metric thay thế chính thức (Alternatives):**
>     - Lượt xem/hiển thị bài viết: dùng **`post_media_view`** (hoặc `post_total_media_view_unique` cho lượt xem duy nhất).
>     - Lượt xem Page: `page_media_view` (hoặc `page_total_media_view_unique`).
>     - Số lượng follower/fan: dùng field `followers_count` của Page node hoặc metric `page_follows`.
>     - Các tương tác phản hồi: lấy trực tiếp qua summary connection (`reactions.summary(total_count)`, `comments.summary(total_count)`, `shares`).
> - **Quy ước triển khai:** Trong mã nguồn Kotlin của Mảng B, version này cần được định nghĩa thành hằng số tập trung (ví dụ: `private const val FACEBOOK_GRAPH_API_VERSION = "v26.0"`) để dễ bảo trì và nâng cấp định kỳ.

### 2.1. Luồng OAuth 2.0 trên Android & Quản lý Token (Kiến trúc Public Client)

- **Chuẩn kiến trúc:** Tuân thủ RFC 8252 (OAuth 2.0 for Native Apps) — ứng dụng Android là **Public Client**, **tuyệt đối không nhúng `client_secret` (App Secret)** vào app hay APK.
- **Trạng thái xác minh của luồng Custom Tabs + PKCE thủ công:**
  > [!WARNING]
  > **Trạng thái: Chưa xác minh chính thức từ tài liệu Meta Graph API (Cần kiểm thử thực tế)**
  > - Theo tài liệu chính thức của Meta về [Manual Login Flow](https://developers.facebook.com/documentation/facebook-login/guides/advanced/manual-flow), endpoint đổi token `GET /oauth/access_token` tiêu chuẩn yêu cầu bắt buộc phải có `client_secret` bên cạnh `code`.
  > - Mặc dù thư viện `AppAuth-Android` hỗ trợ PKCE ở phía client, tài liệu Graph API hiện hành chưa xác nhận chính thức việc chấp nhận bỏ qua `client_secret` chỉ bằng `code_verifier` cho ứng dụng di động thông thường mà không có backend hỗ trợ.
  > - Do đó, nhóm phân tích rõ hai phương án triển khai khả thi dưới đây:

#### Phương án 1 (Native SDK; phải khớp cấu hình sản phẩm): Sử dụng Meta Android SDK (`com.facebook.android:facebook-login`)
Đây là lựa chọn triển khai native của app. Tuy nhiên, tài liệu Pages API hiện ưu tiên
Facebook Login for Business cho app quản lý Page; việc SDK có thể dùng được hay không vẫn phụ thuộc
product, configuration, permission và version mà Dashboard cấp, không tự suy ra chỉ từ việc thêm SDK:
- **Cơ chế xác thực không Secret:** Meta SDK không sử dụng `client_secret`. Thay vào đó, Meta xác thực danh tính ứng dụng thông qua **App ID + Package Name (`com.lttbdd.socialagent`) + Android Key Hash (SHA-1 fingerprint)** đã đăng ký trên Meta App Dashboard.
- **Trải nghiệm đăng nhập (UX):** Tự động kích hoạt App Switch (mở trực tiếp app Facebook đã cài sẵn trên máy) hoặc mở Chrome Custom Tabs nếu máy chưa cài app, giúp người dùng đăng nhập 1 chạm.
- **Tự động quản lý vòng đời Token (Cần kiểm thử theo phiên bản SDK):**
- Theo tài liệu [Access Tokens](https://developers.facebook.com/documentation/facebook-login/guides/access-tokens), long-lived User Access Token thường có thời hạn khoảng **60 ngày**, nhưng lifetime thực tế có thể thay đổi hoặc hết hạn sớm. Facebook SDK quản lý một phần vòng đời token; app vẫn phải đọc trạng thái thực tế, xử lý token bị thu hồi/hết hạn và yêu cầu người dùng đăng nhập/cấp quyền lại khi cần.

#### Phương án 2 — Tài liệu thiết kế tham khảo cho Backend/BFF (Ngoài phạm vi Milestone 1)

> Phần này chỉ mô tả phương án dự phòng cho một repository backend/BFF tương lai.
> Không triển khai phương án này trong Milestone 1 của ứng dụng Android.

##### Luồng Web OAuth (AppAuth / Custom Tabs) kèm BFF

Nếu sau Milestone 1 nhóm xây dựng một backend/BFF riêng và không nhúng Facebook SDK:

> [!WARNING]
> **Trạng thái xác minh của Redirect URI đối với Meta Web OAuth:**
> - **Luồng HTTPS Callback qua BFF (thiết kế tham khảo):** Tuân thủ tài liệu chính thức về [Manual Login Flow](https://developers.facebook.com/documentation/facebook-login/guides/advanced/manual-flow), `redirect_uri` phải nằm trong danh sách *Valid OAuth Redirect URIs* (sử dụng giao thức chuẩn `https://`). Meta chuyển hướng về endpoint HTTPS của máy chủ backend/BFF, sau đó BFF chuyển hướng an toàn về Android App Links.
> - **Luồng Custom Scheme trực tiếp (`com.lttbdd.socialagent://...`):** ⚠️ **Theo quan sát thực tế, chưa xác minh chính thức từ tài liệu Meta**. Tài liệu Meta chưa xác nhận việc hỗ trợ đăng ký Custom Scheme trong danh sách *Valid OAuth Redirect URIs* cho Web Dialog (theo quan sát trên Dashboard, mục này yêu cầu URL HTTPS; custom scheme `fb<APP_ID>://authorize` chỉ được Meta Android SDK sử dụng trong cơ chế App Switch nội bộ).

##### Quy trình tham khảo cho một backend/BFF tương lai:

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
       &scope=public_profile,pages_show_list,pages_manage_posts,pages_read_engagement,pages_read_user_engagement,pages_manage_engagement
       &state={secure-random-state}
   ```
   `read_insights` chỉ được thêm nếu App Dashboard và endpoint/version Insights thực tế của ứng dụng
   yêu cầu quyền này; không chép nguyên một bộ scope vào production khi chưa kiểm tra trạng thái cấp quyền.

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
       "expires_in": 5183944,
       "bff_session_token": "{bff_session_token}"
     }
     ```
     > *Ghi chú về `expires_in`:* Giá trị `5183944` (~60 ngày) chỉ là số minh họa trong response, không phải giá trị cố định được Meta bảo đảm. Máy chủ BFF bắt buộc phải **chuyển tiếp nguyên vẹn trường `expires_in` thực tế** nhận được từ Meta về cho App, và App client lưu `expiresAt = System.currentTimeMillis() + (response.expires_in * 1000L)` (tuyệt đối không gán cứng hằng số 60 ngày).
   - **Cơ chế cấp & vai trò của `bff_session_token` phía BFF (BFF không lưu token Facebook trong database):**
     - **Nguyên tắc lưu trữ trên BFF (Stateless OAuth Proxy):** Khác với LinkedIn (có thể cần lưu `refresh_token` trong DB server khi product/tier cho phép), Facebook không có cơ chế refresh token giống LinkedIn. Tuy nhiên, Page Access Token vẫn có thể mất hiệu lực khi User Token bị thu hồi, quyền bị gỡ hoặc trạng thái tài khoản/Page thay đổi; không được coi là vĩnh viễn. Trong luồng đăng nhập, **BFF chỉ lưu trữ tạm thời token trong bộ nhớ cache tốc độ cao với TTL cực ngắn ($\le 60$ giây, cùng thời hạn với `exchange_code`) gắn liền với `app_code_challenge` để chờ app client gọi đổi mã, và xóa sạch ngay lập tức sau khi đổi thành công**. **Máy chủ BFF tuyệt đối KHÔNG lưu giữ bất kỳ token Facebook nào trong cơ sở dữ liệu lâu dài (persistent database) của server**. Việc quản lý và lưu trữ token thuộc toàn quyền của app client trong `EncryptedSharedPreferences`.
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

**1. Đối với Phương án 1 (Meta Android SDK — SDK hỗ trợ một phần vòng đời phía Client):**
```
[User Login qua Meta SDK (App Switch / Custom Tabs)]
       │
       ▼ (SDK tự xử lý xác thực qua App ID + Key Hash, trả AccessToken object)
[User Access Token (~60 ngày)] (SDK có thể quản lý một phần vòng đời; ứng dụng vẫn phải kiểm tra token và xử lý hết hạn/thu hồi)
       │
       ▼ (Gọi GET /v26.0/me/accounts trực tiếp từ App với User Access Token)
[Page Access Token (Không có expires_at cố định nếu sinh từ User Long-lived Token)]
```
> *Ghi chú:* Meta SDK quản lý một phần vòng đời User Access Token ở phía client, nhưng ứng dụng không
> được giả định SDK luôn gia hạn token hoặc token luôn có TTL cố định. Phải đọc trạng thái/metadata
> thực tế, xử lý hết hạn/thu hồi và kích hoạt cấp quyền lại khi cần. `fb_exchange_token` chỉ dùng
> trong luồng server được tài liệu hỗ trợ.

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
[Page Access Token (Từ User Long-lived Token; không có expires_at cố định)]
```
> *Ghi chú:* Sơ đồ có bước gọi `fb_exchange_token` **chỉ áp dụng cho Phương án 2**. Do tham số `grant_type=fb_exchange_token` yêu cầu bắt buộc phải có `client_secret`, các bước từ đổi code đến nâng cấp token phải được thực hiện trên máy chủ BFF để đảm bảo bí mật không bị lộ ra file APK.

* **Đặc tính của Page Access Token:** 
  - **Bản chất thời hạn của Page Access Token:** 
    - Tài liệu chính thức về endpoint `GET /v26.0/me/accounts` không nêu trường `expires_in` cho Page Access Token (chỉ trả về `access_token`, `id`, `name`, `category`, `tasks`).
    - Theo tài liệu chính thức của Meta ([Get a Long-Lived Page Access Token](https://developers.facebook.com/docs/facebook-login/guides/access-tokens/get-long-lived)), **Page Access Token sinh từ User Long-lived Token không có ngày hết hạn cố định** (`data.expires_at: 0`, giao diện Debugger hiển thị `Expires: Never`). Ngược lại, nếu sinh từ User Short-lived Token thì Page Token sẽ có thời hạn ngắn tương ứng (~1–2 giờ).
    - Tuy không có ngày hết hạn cố định khi sinh từ Long-lived Token, Page Access Token **vẫn bị vô hiệu hóa ngay lập tức** trong các điều kiện sau:
      1. Người dùng đổi mật khẩu tài khoản Facebook cá nhân (lỗi mã 190, subcode 460).
      2. Người dùng chủ động đăng xuất hoặc gỡ quyền của App trong Facebook Settings (subcode 458).
      3. Người dùng bị xóa vai trò quản trị viên hoặc mất nhiệm vụ quản lý (Page Tasks: `MODERATE`, `CREATE_CONTENT`, `MANAGE`) trên chính Page đó (subcode 492).
    - **Tác động thiết kế F1:** Page Access Token **không bị đối xử như hết hạn theo User Token (60 ngày)**; ứng dụng không gán TTL cố định 60 ngày cho Page Token trong Room DB. Thay vào đó, Room DB chỉ quản lý metadata kết nối, và ứng dụng theo dõi trạng thái token bằng cách xử lý mã lỗi thực tế khi gọi API (chuyển sang `ConnectionStatus.TOKEN_EXPIRED` khi gặp subcode 458, 460 hoặc lỗi phân quyền khi gặp 492).
  - **Phương pháp kiểm thử thời hạn Page Token trong giai đoạn Dev (Chỉ dùng khi kiểm thử, không đưa vào App):**
    - **Cách thức kiểm chứng:** Trong quá trình phát triển và kiểm thử, nhóm sử dụng công cụ [Access Token Debugger](https://developers.facebook.com/tools/debug/accesstoken/) trên Meta Developer Portal (hoặc gọi thủ công endpoint `GET /debug_token?input_token={page_access_token}&access_token={app_access_token}` qua Postman hoặc Graph API Explorer).
    - **Kết quả cần tìm:** Đọc các trường metadata thực tế do Access Token Debugger hoặc `/debug_token` trả về, đặc biệt trường `data.expires_at` (giá trị `0` xác nhận token không có ngày hết hạn) và `data.data_access_expires_at` (Unix timestamp chỉ mốc hết hạn truy cập dữ liệu sau 90 ngày nếu người dùng không hoạt động). *(Lưu ý: `data_access_expires_at` là tên trường trong response của `/debug_token`, tương ứng với thuộc tính `dataAccessExpirationTime` của đối tượng `AccessToken` trên Android SDK).*
    - **Phân định phạm vi kiến trúc (Không mâu thuẫn):**
      - Việc kiểm tra qua Access Token Debugger / `GET /debug_token` **chỉ áp dụng độc lập trong giai đoạn kiểm thử nội bộ**, tuyệt đối **không nhúng vào mã nguồn ứng dụng Android**.
      - Khi ứng dụng chạy thực tế (Runtime), app tuân thủ nghiêm ngặt nguyên tắc ở mục dưới đây: **không gọi `/debug_token`**. Với User Access Token, có thể kiểm tra sơ bộ bằng `GET /v26.0/me`; `GET /v26.0/me/accounts` chỉ dùng với User Access Token để lấy Page Access Token, không dùng Page Access Token để suy ra trạng thái User Token.
* **Cơ chế kiểm tra token an toàn & Xử lý lỗi trong App Runtime (F1):**
  - **Lý do ứng dụng client không gọi endpoint `/debug_token` khi chạy thực tế:**
    - Theo tài liệu chính thức của Meta về [Access Tokens Guide](https://developers.facebook.com/docs/facebook-login/guides/access-tokens) (và công cụ [Access Token Debugger](https://developers.facebook.com/tools/debug/accesstoken/)), endpoint `GET /debug_token?input_token={input-token}&access_token={access-token}` đòi hỏi một "valid access token" (thường là App Access Token phía server hoặc một token hợp lệ cùng App ID) để kiểm tra metadata.
    - Việc gọi `/debug_token` trên app di động làm phát sinh thêm một network round-trip thừa và đòi hỏi token kiểm tra phụ không cần thiết cho client.
    - Do đó, ứng dụng có thể kiểm tra sơ bộ User Access Token bằng `GET /v26.0/me` với chính token đang dùng; `GET /v26.0/me/accounts` là request lấy danh sách Page và Page Access Token, chỉ thực hiện với User Access Token. Đây không thay thế việc xử lý `401/403`, thiếu scope hoặc token bị thu hồi khi gọi endpoint nghiệp vụ thực tế.
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
    - **Phân loại `error_subcode`:** Không hard-code bảng ánh xạ các subcode vào hành vi ứng dụng. Tài liệu lỗi Meta không bảo đảm mọi subcode có cùng ý nghĩa qua endpoint/version; client phải xem `error.code`, `error_subcode`, message, token loại nào (User/Page), endpoint và quyền/Page task. Chỉ chuyển sang `TOKEN_EXPIRED` khi response cho thấy token hết hạn/không hợp lệ; nếu lỗi là thiếu quyền hoặc mất Page task thì giữ phân loại lỗi phân quyền và hướng dẫn người dùng xử lý quyền.

  - **Xử lý lỗi phân quyền API & Mã lỗi đặc thù trên Comment API (Meta):**
    - **Ý nghĩa kỹ thuật từ tài liệu chính thức của Meta:**
      - **Mã lỗi `10`** (*Permission Denied*): Ứng dụng hoặc Access Token không có quyền thực hiện thao tác này.
      - **Dải mã lỗi phân quyền `200–299`** (*Permissions-related errors*):
        - Mã `200` (*Permissions disallow edit to field*): Không được phép sửa/thao tác trên trường dữ liệu.
        - Mã `283` (*Requires pages_read_engagement or pages_read_user_content*): **Mã lỗi chính thức từ tài liệu tham chiếu Comment API của Meta**, xảy ra khi thao tác đọc/trả lời comment thiếu quyền tương tác cần thiết $\rightarrow$ Xử lý như lỗi phân quyền (`PermanentError`), không retry vô hạn và kích hoạt cơ chế `HANDED_OFF`.
      - **Mã lỗi giới hạn tần suất & tạm thời trên Comment API:**
        - Mã `1705` (*Temporary comment issue / rate limit*): Mã lỗi chính thức của Meta chỉ sự cố tạm thời hoặc vượt tần suất thao tác comment $\rightarrow$ Xử lý như `TransientError`, áp dụng exponential backoff retry.
        - Mã `80001` (*Page rate limit on comments*): Giới hạn tốc độ thao tác của chính Page trên hệ thống bình luận $\rightarrow$ Xử lý như `TransientError`, tạm dừng hàng đợi và retry sau thời gian chờ.
    - **Ánh xạ kiến trúc & Cơ chế xử lý:**
      - Đây có thể là lỗi phân quyền không thể tự khắc phục bằng retry hoặc refresh token, nhưng client phải đọc `error.code`, `error_subcode`, message, endpoint, Page task và trạng thái quyền trước khi phân loại. Không tự động coi mọi mã trong dải `200–299` là lỗi vĩnh viễn.
      - Khi xác nhận lỗi là thiếu quyền/Page task hoặc App Review không thể khắc phục ở runtime trong quá trình đăng bài (F5), Client **không retry vô hạn** và kích hoạt cơ chế dự phòng bàn giao (xem chi tiết tại §4):
        1. Đánh dấu bài viết sang trạng thái **`PostStatus.HANDED_OFF`** trong Room Database.
        2. Tự động sao chép nội dung bài viết và đường dẫn hình ảnh vào bộ nhớ tạm (Clipboard).
        3. Kích hoạt Android Share Intent native (`Intent.ACTION_SEND` cho bài kèm 1 ảnh hoặc `Intent.ACTION_SEND_MULTIPLE` cho bài đa ảnh) để người dùng tự đăng tay trực tiếp lên ứng dụng Facebook mà không bị mất dữ liệu bài viết đã soạn.

### 2.2. Danh sách quyền (Permissions / Scopes)
Đối chiếu trực tiếp với các khả năng trong `ChannelCapability` (`core/model/Channel.kt`):

| Chức năng / Khả năng | Scope cần xin | Mô tả & Lưu ý kỹ thuật |
|---|---|---|
| **F1 (Kết nối kênh)** | `public_profile`, `pages_show_list` | Lấy danh sách các Page người dùng quản lý (`GET /me/accounts`) và lấy Page Access Token. |
| **`PUBLISH` (Đăng bài)** | `pages_manage_posts`, `pages_read_engagement` | Đăng bài viết (text, link), upload ảnh/video thay mặt Page lên endpoint `/{page-id}/feed` hoặc `/{page-id}/photos`. |
| **`READ_COMMENTS` (Đọc comment)** | `pages_read_user_engagement`, `pages_read_engagement` | Đọc danh sách bình luận của người dùng và các phản hồi trên bài viết (`GET /{post-id}/comments`). **Yêu cầu Page task:** `MODERATE` hoặc `MANAGE`. `pages_read_user_content` chỉ bổ sung khi dùng endpoint user-generated content tương ứng, chẳng hạn `/tagged`. |
| **`REPLY` (Trả lời comment)** | `pages_manage_engagement`, `pages_read_engagement` | Gửi bình luận trả lời (`POST /{comment-id}/comments`) dưới danh nghĩa Page. Theo tài liệu chính thức của Meta Pages API về Comments & Mentions, thao tác này yêu cầu đồng thời cả `pages_manage_engagement` và `pages_read_engagement`. **Yêu cầu Page task:** `MODERATE` hoặc `MANAGE` trên cả Page và comment ID mục tiêu. |
| **`MENTION` (Nhắc tên)** | `pages_manage_posts` khi đăng bài và `pages_manage_engagement` khi thao tác comment/reply, nếu endpoint/version xác nhận hỗ trợ mention; `pages_read_user_content` chỉ khi đọc endpoint user-generated content tương ứng như `/tagged` *(kèm Page feature và Page task phù hợp)* | Không coi mention là capability mặc định. Chỉ thử với Page-scoped ID hoặc định danh được endpoint tài liệu hỗ trợ, sau khi xác nhận Page task, product và quyền thực tế; nếu không đáp ứng thì fallback về `HANDED_OFF`. Đây không phải cơ chế tìm kiếm/mention tự do mọi hồ sơ cá nhân. |
| **`METRICS` (Số liệu)** | `pages_read_engagement`, `read_insights` *(đối chiếu thêm quyền và metric được yêu cầu bởi endpoint/version cụ thể)* | Đọc số lượt hiển thị/xem (`post_media_view` hoặc metric hiện hành tương ứng), tương tác (`reactions`, `shares`, `comments count`) và số lượng người theo dõi (`followers_count`). Đọc Page Insights yêu cầu **Page task:** `ANALYZE` hoặc `MANAGE`. Không dùng metric Insights `page_fans` đã deprecated làm dữ liệu chính. |

### 2.3. Các Endpoint chính
> **Quy ước triển khai trong repository:** Các ví dụ `FacebookClient`/Repository ở mục này
> chỉ là tên vai trò. Implementation thật dự kiến đặt tại
> `app/src/main/java/com/lttbdd/socialagent/data/social/FacebookClient.kt` (hoặc
> `data/facebook/FacebookClient.kt` nếu nhóm tách package theo nền tảng), được expose qua
> `core/social/SocialChannelClient.kt` và cung cấp bằng Hilt trong `di/`. Không import trực tiếp
> implementation từ feature khác.

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
     - **Số lượng ảnh và dung lượng file:** Không hard-code giới hạn cố định trong tài liệu nghiên cứu. Client phải đối chiếu tài liệu của endpoint/version đang dùng và xử lý rõ lỗi validation hoặc upload failure từ response.
     - Trong Milestone 1, model `PostDraft` ưu tiên hỗ trợ `imageUri` (ảnh đơn), quy trình đa ảnh được chuẩn hóa sẵn sàng cho giai đoạn mở rộng tiếp theo.

   > [!WARNING]
   > **F5 — Idempotency phía app (Facebook không có header native) và lỗ hổng bản giả**
   >
   > Meta Graph API **không** có cơ chế idempotency header native. App phải tự chống đăng trùng: cùng `idempotencyKey` thì `SocialChannelClient.publish` trả kết quả lần đăng trước, không tạo bài mới (Room DB khi có client thật; `publishedByKey` khi còn `FakeSocialChannelClient`).
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
   GET https://graph.facebook.com/v26.0/{post-id}?fields=shares,comments.summary(total_count),reactions.summary(total_count)&access_token={page-access-token}
   GET https://graph.facebook.com/v26.0/{post-id}/insights?metric=post_media_view&access_token={page-access-token}
   ```
   *Lưu ý thay đổi trường dữ liệu quan trọng:*
   - Meta đã deprecated `post_impressions` và cung cấp `post_media_view` làm alternative; `post_impressions_unique` có alternative là `post_total_media_view_unique`. Ngoài ra, theo Changelog v25.0, khi v26.0 phát hành thì các metric **`page_post_reach`**, **`page_reach`**, **`video_impressions`** và **`story_impressions`** cũng bị gỡ bỏ hoàn toàn cho mọi version. Nếu request chứa metric không còn được version hiện hành hỗ trợ, API sẽ trả lỗi HTTP 400 (Invalid metric) và làm thất bại cuộc gọi.
   - Để lấy số lượt hiển thị/xem, ưu tiên metric được tài liệu chính thức xác nhận cho version đang dùng, hiện là **`post_media_view`** cho lượt xem không duy nhất.
   - Các tương tác like/reaction, comment, share được lấy trực tiếp và chính xác qua summary connection: `reactions.summary(total_count)`, `comments.summary(total_count)` và `shares`.
8. **Lấy số follower của Page (F7):**
   ```http
   GET https://graph.facebook.com/v26.0/{page-id}?fields=followers_count&access_token={page-access-token}
   ```
   *Lưu ý về các trường số liệu trên Graph API v26.0 (Legacy Page Fields):*
   - **`followers_count`:** Field của Page được tài liệu Graph API sử dụng để đọc tổng số người theo dõi. Phải kiểm tra field này còn được expose trên version, product và Page token thực tế trước khi dùng làm nguồn dữ liệu runtime.
   - **`page_fans`:** Là metric Insights đã deprecated; không dùng làm fallback runtime. `fan_count` là Graph field khác với metric `page_fans`, vì vậy không được tự động coi là deprecated chỉ từ thay đổi metric; nếu cần dùng phải xác nhận field và quyền trên version hiện hành. `page_follows` là alternative của metric Insights `page_fans`, không mặc định là field thay thế trong truy vấn Page object.

### 2.4. Giới hạn & Quy định kiểm duyệt (App Review)
- **Giới hạn nội bộ của app:** `63,206` ký tự theo cấu hình `ChannelType.FACEBOOK(maxChars = 63_206)`. Đây không phải giới hạn Pages API được Meta xác nhận trong tài liệu này; client phải xử lý lỗi validation nếu endpoint/version thực tế áp dụng giới hạn khác.
- **Kích thước ảnh & Định dạng:**
  - Định dạng và giới hạn ảnh phải đối chiếu theo endpoint/version đang dùng; không suy ra một danh sách định dạng chung cho mọi Pages API upload.
  - **Giới hạn dung lượng (⚠️ Trạng thái: Chưa xác minh từ tài liệu chính thức của Meta — Số liệu thực nghiệm/quan sát cộng đồng dev):**
    - Không hard-code giới hạn dung lượng `4 MB` hoặc `10 MB`: đây không phải các ngưỡng cố định được xác nhận trong tài liệu Pages API hiện hành.
    - Client cần kiểm tra lỗi phản hồi khi upload, giới hạn được công bố cho endpoint/version đang dùng và khả năng tải lên thực tế trước khi chọn ngưỡng nội bộ.
    *(Meta có thể kiểm tra định dạng, kích thước pixel và các giới hạn khác theo endpoint; không suy ra giới hạn MB chính thức nếu tài liệu endpoint không công bố).*
- **Rate Limit (Giới hạn tần suất gọi):** Áp dụng cơ chế **Business Use Case (BUC) Rate Limiting** (và Platform Rate Limiting theo token):
  - **Bản chất giới hạn động (Dynamic Threshold):** Theo tài liệu chính thức của Meta về [Graph API Rate Limiting](https://developers.facebook.com/docs/graph-api/overview/rate-limiting/), không có hạn ngạch cuộc gọi cố định cho tất cả các Page. Ngưỡng giới hạn được tính toán linh hoạt theo cửa sổ trượt (rolling window) dựa trên số lượng người dùng tương tác với Page (Page Engaged Users) và loại use-case cụ thể.
  - **Cơ chế theo dõi tài nguyên:** Ứng dụng theo dõi trực tiếp mức tiêu thụ tài nguyên qua header HTTP trả về:
    - `X-Business-Use-Case-Usage`: Chứa đối tượng JSON biểu thị tỉ lệ % đã sử dụng (`call_count`, `cpu_time`, `total_time` tính trên ngưỡng 100%) và thời gian chờ phục hồi (`estimated_time_to_regain_access`).
    - `X-App-Usage`: Biểu diễn % tài nguyên cấp độ ứng dụng.
  - **Xử lý khi chạm trần:** Có thể nhận HTTP 403/429 hoặc lỗi Graph API kèm mã và header usage tùy endpoint. Client phải đọc body lỗi cùng `X-Business-Use-Case-Usage`/`X-App-Usage`; chỉ map sang `PublishResult.RetryableError` khi response cho thấy lỗi tạm thời hoặc có thời gian retry, không ánh xạ cố định mọi mã lỗi vào retry.
- **Chính sách hết hạn quyền truy cập dữ liệu (90-Day Data Access Expiration):**
  - **Bản chất chính sách bảo mật nền tảng (Platform Policy):** Theo tài liệu chính thức về [Authentication Versus Data Access](https://developers.facebook.com/documentation/facebook-login/auth-vs-data), Meta áp dụng chính sách thời hạn truy cập dữ liệu người dùng là **90 ngày tính từ lần hoạt động cuối cùng của người dùng** trên ứng dụng.
  - **Phân biệt giữa Xác thực (Authentication), Data Access Expiration và Permission Expiration:**
    - Khi hết hạn 90 ngày, phiên đăng nhập của người dùng **vẫn tồn tại** (người dùng vẫn ở trạng thái authenticated, access token không tự động mất hiệu lực xác thực danh tính), nhưng ứng dụng **bị chặn hoàn toàn quyền đọc/truy cập dữ liệu người dùng** cho đến khi được người dùng cấp lại quyền.
    - Đây là **Data Access Expiration** của từng người dùng, không đồng nghĩa bản thân mọi permission
      đều có cùng một TTL. Khi data access hết hạn, app phải xin lại quyền/truy cập theo luồng Meta
      hiện hành; quyền cũng có thể bị thu hồi, thay đổi trạng thái xét duyệt hoặc bị từ chối theo
      endpoint/product. Không suy luận trạng thái hết hạn riêng của `pages_read_user_engagement` nếu
      tài liệu permission/version hiện hành không liệt kê permission đó.
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
    - Meta có thể yêu cầu người dùng xác nhận lại consent hoặc đăng nhập lại; ứng dụng không được giả định rằng mọi lần cấp lại quyền đều chỉ cần một lần bấm.
  - **Lưu ý về Permission Expiration:**
    - Meta vẫn có thể thu hồi, thay đổi trạng thái xét duyệt hoặc từ chối permission theo endpoint/product; không được suy luận quyền đang hợp lệ chỉ từ việc đã từng được App Review phê duyệt.
    - *Tác động đến đồ án:* Cần kiểm tra permission và metric thực tế trên App Dashboard cũng như phản hồi API; không đồng nhất việc người dùng không hoạt động 90 ngày với việc permission cấp độ ứng dụng hết hạn.
- **Quyền nào cần App Review (Meta App Review):**
  - *Default Access (Tự động có, không cần review):* Chỉ có `public_profile` và `email`.
  - *Advanced Access (BẮT BUỘC App Review nếu dùng công chúng):* Các quyền phục vụ tính năng chính cần được xét theo product và endpoint: `pages_show_list`, `pages_manage_posts`, `pages_read_engagement`, `read_insights`, `pages_read_user_engagement`, `pages_read_user_content` (chỉ khi dùng endpoint tương ứng) và `pages_manage_engagement`; cần xác minh quyền và trạng thái xét duyệt trên App Dashboard.
  - *Điều kiện qua App Review:* Hồ sơ phải đáp ứng các yêu cầu mà Meta hiển thị cho từng permission/product; có thể bao gồm **Business Verification**, video screencast và các bước xác minh doanh nghiệp. Không suy ra rằng mọi permission Pages đều có cùng một bộ điều kiện.
- **Chế độ Development dùng được tới đâu:**
  - **Phạm vi và Điều kiện hoạt động:** Ở chế độ **Development Mode**, các quyền nâng cao (ví dụ `pages_manage_posts`) chỉ hoạt động **trong phạm vi tài khoản nội bộ và có điều kiện ràng buộc**, cụ thể:
    1. **Ràng buộc tài khoản (App Roles):** Chỉ các tài khoản Facebook có vai trò chính thức trong mục *App Roles* trên Meta Dashboard (*Administrators*, *Developers*, hoặc *Testers*) mới có thể đăng nhập và cấp quyền. Người dùng vãng lai bên ngoài sẽ bị Meta từ chối.
    2. **Ràng buộc vai trò trên Page (Page Tasks):** Tài khoản thử nghiệm phải nắm vai trò quản trị hoặc nhiệm vụ phân quyền cụ thể (*Page Tasks*) trên chính Facebook Page được thử nghiệm:
       - **Đăng bài (F5):** Yêu cầu Page task `CREATE_CONTENT` hoặc `MANAGE`.
       - **Đọc và Trả lời comment (F6):** Yêu cầu Page task `MODERATE` hoặc `MANAGE` trên cả Page và comment ID mục tiêu.
       - **Đọc số liệu Page Insights (F7):** Yêu cầu Page task `ANALYZE` hoặc `MANAGE` kết hợp quyền `read_insights` và `pages_read_engagement`.
    3. **Ràng buộc kích hoạt tính năng:** Sản phẩm/tính năng tương ứng (ví dụ: *Pages API* hoặc *Facebook Login for Business*) phải được cấu hình trong App Dashboard.
    4. **Giới hạn cần lưu ý:** Một số quyền nhạy cảm cao (như Live Video hoặc dữ liệu cá nhân chi tiết) vẫn có thể đòi hỏi xác minh doanh nghiệp ngay trong Development Mode. Với phạm vi đồ án (F1, F5, F6, F7 trên Page thử nghiệm của nhóm), chế độ Development Mode đáp ứng đủ yêu cầu phát triển mà chưa cần mở công chúng.

---

## 3. Kênh LinkedIn (LinkedIn REST API)

### 3.1. Luồng OAuth 2.0 trên Android & Quản lý Token

> [!IMPORTANT]
> **Điểm cốt lõi về Native PKCE của LinkedIn:**
> LinkedIn phân tách rõ ràng giữa **giao thức Native PKCE dành riêng cho ứng dụng di động** và **luồng Standard OAuth 2.0 Web**. Nhóm cần nắm rõ điều kiện áp dụng của từng luồng để tránh lỗi phân quyền `Not enough permissions to access Native PKCE protocol` khi tích hợp.

#### Luồng A: LinkedIn Native PKCE Protocol (Trực tiếp Client — Không giữ Secret)
Đây là giao thức chính thức của LinkedIn dành cho các ứng dụng native (Android/Desktop) không có
backend; đối chiếu [OAuth 2.0 for Native Clients](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow-native):
- **Authorization Endpoint:** `https://www.linkedin.com/oauth/native-pkce/authorization` *(khác với `/oauth/v2/authorization`)*
- **Token Endpoint:** `https://www.linkedin.com/oauth/v2/accessToken`
- **Redirect URI:** Bắt buộc dùng **Loopback Interface** (ví dụ: `http://127.0.0.1:{port}`,
  `http://127.0.0.1:{port}/callback` hoặc IPv6 loopback `http://[::1]:{port}`). Theo tài liệu
  Native PKCE, LinkedIn chỉ giao tiếp với loopback IP; app phải mở HTTP listener trên cổng ngẫu nhiên.
  App phải dùng trình duyệt mặc định của hệ điều hành để thực hiện authorization, không dùng WebView
  nhúng. Việc dùng Custom Tabs, cách giữ listener sống qua vòng đời Android và có cần service hay không
  là quyết định triển khai của app, không phải yêu cầu riêng được LinkedIn bảo đảm.
- **Điều kiện áp dụng bắt buộc:**
  - Mặc định các App tạo trên LinkedIn Developers **chưa được bật cờ Native PKCE**.
  - **Bắt buộc:** LinkedIn phải bật PKCE OAuth 2 flow cho App ID. Nhóm cần liên hệ đầu mối LinkedIn
    được chỉ định cho ứng dụng (point of contact/Developer Support theo kênh mà LinkedIn cấp) trước
    khi triển khai; không giả định app mới được bật protocol mặc định.
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

#### Luồng B — Tài liệu thiết kế tham khảo cho Backend/BFF (Ngoài phạm vi Milestone 1)

> Phần này chỉ mô tả Standard OAuth 2.0 để tham khảo khi nhóm có backend/BFF riêng.
> Trong Milestone 1, nếu Native PKCE chưa được LinkedIn bật cho App ID, tính năng LinkedIn
> được đánh dấu **blocked**; không triển khai Luồng B trong repository Android.

##### Standard OAuth 2.0 (`/oauth/v2/...`) qua Kiến trúc BFF (Backend Proxy)
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
       > *Ghi chú về `expires_in`:* Giá trị `5184000` (~60 ngày) là số giây ví dụ minh họa. Máy chủ BFF bắt buộc phải **chuyển tiếp nguyên vẹn trường `expires_in` thực tế** nhận được từ LinkedIn về cho App, và App client lưu `expires_at = System.currentTimeMillis() + (response.expires_in * 1000L)` (tuyệt đối không gán cứng hằng số 60 ngày).
     - **Cơ chế cấp & vai trò của `bff_session_token` phía BFF:**
       - **Cách cấp:** BFF sinh mã phiên ngẫu nhiên độ entropy cao (hoặc JWT ký bởi secret server) và gửi trực tiếp cho App trong response body của endpoint `POST https://api.yourdomain.com/auth/linkedin/token` (kênh HTTPS back-channel bảo mật, tuyệt đối không gửi qua URL/Deep Link).
       - **Vai trò:** `bff_session_token` là khóa định danh phiên bí mật giữa App và BFF. Token này đảm nhận hai vai trò an ninh then chốt:
         1. **Làm mới token tự động:** Được app đính kèm trong header `Authorization: Bearer {bff_session_token}` khi gọi `POST /auth/linkedin/refresh` (mục 3 dưới đây), giúp BFF chứng thực chính xác app thật trước khi dùng `refresh_token` lưu trên server để đổi lấy token mới với LinkedIn.
         2. **Thu hồi phiên & Hủy `refresh_token` khi Ngắt kết nối (`POST /auth/linkedin/logout`):** Khi người dùng bấm "Ngắt kết nối" (F1), app gửi `bff_session_token` lên endpoint `POST /auth/linkedin/logout` để yêu cầu BFF xóa vĩnh viễn `refresh_token` trong DB máy chủ và vô hiệu hóa phiên, ngăn ngừa nguy cơ tồn đọng credential mồ côi trên backend sau khi app đã hủy kết nối.
     - **Quy tắc phân định lưu trữ an toàn phía Client:**
       - **Chỉ lưu Token trong `EncryptedSharedPreferences`:** Toàn bộ token bí mật gồm `access_token`, `refresh_token` (nếu có) và `bff_session_token` được lưu trữ an toàn trong `EncryptedSharedPreferences` bằng Android Keystore (`MasterKey.DEFAULT_AES256_GCM_SPEC`).
       - **Room Database tuyệt đối KHÔNG lưu token:** Room chỉ lưu trữ metadata phi nhạy cảm của tài khoản (`account_id`, `name`, `avatar_url`, `expires_at`, `connection_status`, `capabilities`). Khi người dùng đăng nhập lần đầu, app đồng thời ghi nhận mốc `refreshTokenIssuedAt = System.currentTimeMillis()` vào Room DB (nếu kích hoạt luồng refresh) để làm căn cứ xác định hạn trần 365 ngày của refresh token. Khi gọi API, Repository sẽ lấy metadata từ Room và truy xuất token từ `EncryptedSharedPreferences`.

#### Bảng so sánh hai luồng OAuth của LinkedIn

| Tiêu chí | Luồng A: Native PKCE (`/oauth/native-pkce/`) | Luồng B: Standard OAuth (`/oauth/v2/`) qua BFF |
|---|---|---|
| **Endpoint cấp mã** | `https://www.linkedin.com/oauth/native-pkce/authorization` | `https://www.linkedin.com/oauth/v2/authorization` |
| **Kiểu Redirect URI** | Loopback IP (`http://127.0.0.1:{port}/callback`) | URL HTTPS về server BFF (`https://api.yourdomain.com/.../callback`) *(App nhận mã `exchange_code` tạm thời qua Android App Links rồi gọi POST HTTPS lấy token)* |
| **Yêu cầu Client Secret** | ❌ **Không** (Trao đổi bằng `code_verifier`) | ✅ **Bắt buộc** (Phải ủy quyền và giữ secret tại Backend/BFF) |
| **Điều kiện kích hoạt** | Cần liên hệ **LinkedIn Support duyệt bật cờ Native PKCE** | Endpoint Standard OAuth là luồng web có sẵn, nhưng product, scope, redirect URI và quyền dùng thực tế vẫn phải được xác nhận trên Developer Portal |
| **Phù hợp đồ án** | Tối ưu khi đã được LinkedIn duyệt cờ Native PKCE | Dùng dự phòng nếu chưa kịp duyệt cờ PKCE từ LinkedIn |

#### Vòng đời Token & Cơ chế Tái xác thực (Token Lifecycle & Re-authentication)

> Refresh token của LinkedIn không phải entitlement mặc định. Chỉ dùng khi ứng dụng được cấp
> programmatic refresh token; đối chiếu [Programmatic Refresh Tokens](https://learn.microsoft.com/en-us/linkedin/shared/authentication/programmatic-refresh-tokens).

> [!CAUTION]
> **Ràng buộc nền tảng về Programmatic Refresh Token của LinkedIn:**
> - `offline_access` và refresh token **không phải capability mặc định có thể suy ra cho mọi App**. Chỉ đưa scope này vào authorization request khi tài liệu/product và Developer Portal của App xác nhận hỗ trợ; nếu không, request có thể bị từ chối.
> - Quyền cấp **Refresh Token (TTL 365 ngày)** để làm mới ngầm qua API là tính năng phụ thuộc entitlement/phê duyệt của LinkedIn, thường gắn với chương trình đối tác như Marketing Developer Platform (MDP). **Đồ án sinh viên không nên giả định được cấp**, nên phương án refresh chỉ là kiến trúc tham khảo và không được dùng làm đường vận hành duy nhất.
> - **Nguyên tắc thiết kế F1:** Ứng dụng **tuyệt đối không phụ thuộc hoàn toàn vào Refresh Token**; nếu không được cấp, dùng luồng re-authentication khi access token hết hạn theo `expires_in` thực tế, không coi 60 ngày là hằng số runtime.

1. **Vòng đời Access Token Tiêu chuẩn:** Theo tài liệu LinkedIn, access token **thường có thời hạn mặc định 60 ngày**; ứng dụng phải dùng `expires_in` thực tế thay vì hard-code thời hạn.
2. **Phương án Xử lý khi Hết hạn Token (Re-authentication Flow — Cho ứng dụng thông thường):**
   - **Theo dõi thời hạn:** Khi đăng nhập thành công, app lưu `expires_at = System.currentTimeMillis() + (expires_in * 1000L)` vào Room Database (app không suy đoán bằng hằng số 60 ngày mà lưu thời hạn thực tế từ `expires_in`).
   - **Kích hoạt trạng thái hết hạn:** 
     - Khi thời gian còn lại dưới 3 ngày (72 giờ), hoặc khi nhận mã HTTP `401 Unauthorized` từ bất kỳ request API LinkedIn nào.
     - Repository cập nhật trạng thái kênh trong Room DB thành `ConnectionStatus.TOKEN_EXPIRED`.
   - **Trải nghiệm người dùng (UX):**
     - Màn hình "Kênh của tôi" (F1) và màn hình Soạn bài/Duyệt bài (F4) hiển thị Badge cảnh báo dựa trên `expires_at`, ví dụ: *"Phiên kết nối LinkedIn đã hết hạn. Vui lòng kết nối lại"*; không hard-code chu kỳ 60 ngày vào trạng thái runtime.
     - Nút "Kết nối lại" (Re-connect) kích hoạt mở lại Custom Tabs. Trình duyệt hệ thống có thể vẫn lưu phiên đăng nhập, nhưng API vẫn có thể yêu cầu consent hoặc đăng nhập lại đầy đủ; UX không được coi là luôn "1 chạm".
3. **Phương án Dự phòng khi được cấp Programmatic Refresh Token (Nếu được duyệt Partner Tier — Thực tế rất khó áp dụng với đồ án):**
   > [!IMPORTANT]
   > **Yêu cầu bắt buộc `client_secret` khi Refresh Token:**
   > Theo tài liệu chính thức của LinkedIn OAuth 2.0 (`POST /oauth/v2/accessToken` với `grant_type=refresh_token`), request làm mới token **bắt buộc phải truyền cả `client_id` và `client_secret`**.
   > Vì ứng dụng Android là Public Client không được giữ `client_secret`, **request refresh token này BẮT BUỘC phải do máy chủ BFF thực hiện** (nơi lưu giữ `client_secret` an toàn phía server), tuyệt đối không gọi trực tiếp từ Android client.

   > [!WARNING]
   > **Phạm vi xung đột với Quy tắc 5 (AGENTS.md) — Rộng hơn chỉ refresh token:**
   > - **Quy tắc 5 trong `AGENTS.md`:** *"Không bao giờ log, commit hay gửi API key/token lên bất kỳ server nào ngoài nhà cung cấp LLM/mạng xã hội. Key lưu bằng Android Keystore."*
   > - **Phạm vi mâu thuẫn thực tế:**
   >   - **Luồng B (LinkedIn) và Phương án 2 (Facebook Web OAuth qua BFF):** Trong cả hai luồng này, access token phải đi qua bộ nhớ cache tạm thời của BFF (TTL ≤ 60 giây) trong bước đổi `exchange_code`. Đây vẫn là "gửi token lên server ngoài nhà cung cấp" theo đúng nghĩa của Quy tắc 5, dù chỉ tạm thời và không ghi vào database lâu dài.
   >   - **Luồng lưu `refresh_token` vào DB server (nếu được duyệt Partner Tier):** Mức độ vi phạm sâu hơn — token tồn tại lâu dài (365 ngày) trên database máy chủ BFF.
   > - **Kết luận về phạm vi tuân thủ:**
   >   - ✅ **Tuân thủ hoàn toàn Quy tắc 5:** **LinkedIn Luồng A (Native PKCE)** + **Facebook Phương án 1 (Meta SDK)** — token không đi qua bất kỳ server trung gian nào của dự án.
   >   - ⚠️ **Cần ngoại lệ Policy Exception:** **LinkedIn Luồng B** và **Facebook Phương án 2 (Web OAuth qua BFF)** — access token đi qua BFF cache ≤ 60 giây; chấp nhận được về rủi ro kỹ thuật nhưng về chữ nghĩa Quy tắc 5 thì cần nhóm thống nhất.
   >   - ❌ **Xung đột rõ ràng, cần Policy Exception qua issue `contract`:** Lưu `refresh_token` LinkedIn vào DB server — phương án này thực tế **rất khó áp dụng** với đồ án vì yêu cầu đủ điều kiện MDP Partner.
   > - **Baseline chính thức cho Milestone 1:** Đồ án chỉ triển khai **Luồng A (Native PKCE)** cho LinkedIn
   >   khi Developer Portal đã bật protocol, và **Phương án 1 (Meta SDK)** cho Facebook. Nếu LinkedIn
   >   chưa được bật Native PKCE, tính năng LinkedIn bị đánh dấu **blocked**; không dùng Luồng B/Phương án 2
   >   làm dự phòng trong repository Android.
   > - **Điều kiện tiên quyết:** Trước khi triển khai bất kỳ luồng nào qua BFF, **toàn nhóm bắt buộc phải họp thống nhất và ghi nhận phạm vi ngoại lệ (Policy Exception) cho Quy tắc 5 trong `AGENTS.md`** thông qua ticket/issue nhãn `contract`.

   - **Thực thi hoàn toàn phía Server/BFF:**
     - **Cơ chế xác thực giữa App và BFF (BFF Session Authentication):**
       - Khi hoàn tất đăng nhập ban đầu, máy chủ BFF lưu trữ `refresh_token` của LinkedIn an toàn trong cơ sở dữ liệu server (được mã hóa at-rest).
       - BFF cấp cho app Android một token phiên nội bộ (**BFF Session Token**, chuỗi ngẫu nhiên độ entropy cao hoặc JWT ký bởi server). App lưu Session Token này riêng biệt trong `EncryptedSharedPreferences`.
       - Khi token sắp hết hạn (thời gian hiệu lực còn lại dưới 3 ngày / 72 giờ, đồng bộ với ngưỡng cảnh báo F1, phát hiện qua trường `expiresAt` trong Room DB), app Android gửi yêu cầu làm mới ngầm đến server BFF qua kết nối HTTPS:
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
     - **Phản hồi, Tính toán Thời hạn Thực tế & Lưu trữ phía Client:**
       - Server BFF nhận Access Token mới cùng trường `expires_in` biểu thị thời hạn hiệu lực thực tế (số giây còn lại) từ LinkedIn, và chuyển tiếp nguyên vẹn `expires_in` này trong JSON response body cho app Android qua kênh HTTPS bảo mật:
         ```json
         {
           "access_token": "AQXN...",
           "token_type": "bearer",
           "expires_in": 5184000
         }
         ```
         > *Ghi chú về `expires_in`:* Giá trị `5184000` (~60 ngày) chỉ là số giây ví dụ minh họa. Máy chủ BFF bắt buộc phải **chuyển tiếp nguyên vẹn trường `expires_in` thực tế** nhận được từ LinkedIn về cho App (không tự ý làm tròn hay gán hằng số).
       - **Cách tính hạn token sau refresh (Token Expiration Calculation):**
         - **Không suy diễn thời hạn từ mốc mặc định:** Thời hạn sau refresh phụ thuộc response và chính sách/product của LinkedIn; không giả định luôn là 60 ngày hoặc tự suy ra từ một mốc refresh token.
         - **App client tính toán động theo `expires_in` thực tế (Tuyệt đối không cộng cố định 60 ngày):**
           App client không suy hạn bằng hằng số 60 ngày. Mỗi lần nhận token (khi đăng nhập ban đầu hoặc khi refresh), app đều đọc giá trị `expires_in` thực tế do BFF chuyển tiếp từ LinkedIn để tính và lưu vào Room DB:
           $$\text{expires\_at} = \text{System.currentTimeMillis}() + (\text{response.expires\_in} \times 1000\text{L})$$
           *(Công thức này đồng bộ chính xác với cách tính hạn ở lần đăng nhập đầu tiên tại mục 2, đảm bảo app phản ánh trung thực hạn token thực tế và hiển thị badge cảnh báo kịp thời kể cả trong các chu kỳ refresh cuối cùng).*
         - **Mốc hết hạn Refresh Token:** Với Programmatic Refresh Tokens được LinkedIn cấp, tài liệu hiện hành mô tả refresh token có TTL ban đầu 365 ngày và không được gia hạn khi dùng để refresh. Response `refresh_token_expires_in` là nguồn runtime để tính thời gian còn lại; không hard-code 365 ngày thay cho response.
         - **Ngưỡng kích hoạt làm mới & Cơ chế chống lặp Refresh vô tận (Infinite Refresh Loop Prevention):**
           - **Ngưỡng kích hoạt:** Đồng bộ thống nhất với ngưỡng cảnh báo F1 là **dưới 3 ngày (72 giờ)**. Khi thời hạn còn lại của token $\le 3\text{ ngày}$ (phát hiện qua `expiresAt` trong Room DB), app mới kích hoạt request gọi `POST /auth/linkedin/refresh` lên BFF.
           - Nếu response sau refresh vẫn gần hết hạn, không retry vô hạn. Repository ghi nhận thời hạn thực tế và cảnh báo người dùng; khi refresh token hết hạn hoặc refresh thất bại, chuyển sang trạng thái cần kết nối lại dựa trên response thực tế và hiển thị hướng dẫn re-authentication.
       - **Lưu trữ chuẩn:** App cập nhật Access Token mới trực tiếp vào **`EncryptedSharedPreferences`**. Room DB **chỉ cập nhật metadata** `expiresAt` (tính theo công thức động ở trên) và `ConnectionStatus.CONNECTED` (tuyệt đối không ghi token vào Room).
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
   - **Lưu ý với Client Native thuần túy (không có BFF):** Nếu chạy Luồng A (Native PKCE trực tiếp) mà không có backend server, tính năng programmatic refresh thường không thể thực thi do yêu cầu `client_secret` và entitlement tương ứng. Khi đó, app phải theo dõi `expires_in` thực tế và khởi động lại luồng OAuth khi token hết hạn; không hard-code chu kỳ 60 ngày.
   - *Thời hạn tối đa:* Với Programmatic Refresh Tokens được cấp cho app, tài liệu LinkedIn mô tả refresh token có TTL 365 ngày; mỗi lần refresh cấp access token mới với TTL tối đa 60 ngày, nhưng ở cuối chu kỳ cả hai token có thể còn cùng thời gian sống. Refresh không gia hạn refresh token; app vẫn phải dùng `expires_in` và `refresh_token_expires_in` thực tế trong response và re-authenticate khi refresh token hết hạn.

### 3.2. Danh sách quyền (Permissions / Scopes)
Đối chiếu trực tiếp với các khả năng trong `ChannelCapability` (`core/model/Channel.kt`) theo chuẩn LinkedIn REST API & Community Management API:

> **Lưu ý về API tương tác:** Các trang tài liệu LinkedIn được đối chiếu ở đây vẫn mô tả
> `socialActions/comments` cho thao tác đọc/ghi comment. Không tự suy ra rằng một API khác đã thay thế
> endpoint này; trước khi triển khai production phải xác nhận endpoint, product, version và permission
> được Developer Portal cấp cho ứng dụng.

| Chức năng / Khả năng | Scope cần xin | Mục đích & Chi tiết kỹ thuật |
|---|---|---|
| **F1 (Kết nối & Quản trị)** | `openid`, `profile`, `rw_organization_admin` | Xác thực đăng nhập qua OpenID Connect và kiểm tra vai trò quản trị viên (Admin) trên Page công ty. |
| **`PUBLISH` (Đăng bài)** | `w_organization_social` *(Page)*<br>`w_member_social` *(Cá nhân)* | Đăng bài viết (text, link, media URN) lên Organization Page hoặc trang cá nhân của thành viên. |
| **`READ_COMMENTS` (Đọc comment)** | `r_organization_social_feed` (Page)<br>*(Cá nhân: Không khả dụng)* | Đọc danh sách bình luận, các reply phân cấp và reaction trên bài viết của Organization Page (`GET /rest/socialActions/{targetUrn}/comments`). **Yêu cầu:** Sản phẩm Community Management API và role được tài liệu endpoint cho phép.<br>⚠️ *Đối với Member cá nhân:* Tài liệu Social Metadata API quy định `r_member_social` là **Closed Permission** (quyền đóng, hiện không nhận đơn đăng ký mới). Do đó tính năng đọc comment Member không khả dụng qua API, luôn kích hoạt luồng `HANDED_OFF`. |
| **`REPLY` (Trả lời comment)** | `w_organization_social_feed` (Page)<br>`w_member_social_feed` (Cá nhân - cần xác nhận) | Gửi bình luận trả lời (`POST /rest/socialActions/{targetUrn}/comments`) dưới danh nghĩa `urn:li:organization:{org-id}`. **Yêu cầu:** Sản phẩm Community Management API và role được tài liệu endpoint cho phép.<br>⚠️ *Đối với Member cá nhân:* Trang Network Update Social Actions liệt kê scope mới `w_member_social_feed` (thay thế cho `w_member_social` từ 06/2023 cho các social action APIs). Cần kiểm tra trên Developer Portal xem app có xin được scope này không; nếu không, fallback về `HANDED_OFF`. |
| **`MENTION` (Nhắc tên)** | `w_organization_social` *(chỉ là quyền đăng; capability phải xác nhận riêng)* | ⚠️ Không coi mention Member/Organization là capability chung của LinkedIn Posts API. Chỉ thử khi tài liệu của product/version hiện hành xác nhận cú pháp, URN và quyền; nếu không xác nhận được thì dùng luồng `HANDED_OFF`. LinkedIn không cung cấp API tìm kiếm profile URN tự do cho app thông thường. |
| **`METRICS` (Số liệu)** | `rw_organization_admin` cho Organization Follower Statistics và Organization Share Statistics; cần role `ADMINISTRATOR` và xác nhận Product/Developer Portal<br>*(Cá nhân: Không khả dụng)* | Đọc số liệu tương tác (`likeCount`, `commentCount`, `shareCount`, `impressionCount`, `clickCount`) qua `organizationalEntityShareStatistics` và số người theo dõi (`firstDegreeSize`) qua `networkSizes`.<br>⚠️ *Đối với Member cá nhân:* Do `r_member_social` là quyền đóng, không có API công khai đọc thống kê bài viết Member; kích hoạt `HANDED_OFF`. |
| **Làm mới ngầm (F1 - Tùy chọn)** | Scope và entitlement do Developer Portal/product cấp; không tự thêm `offline_access` nếu chưa được xác nhận | Programmatic Refresh Token phụ thuộc entitlement/phê duyệt của LinkedIn. Tài liệu hiện hành mô tả refresh token có TTL 365 ngày và không được gia hạn khi refresh; access token mới thường có TTL 60 ngày nhưng phải dùng `expires_in` thực tế. Request refresh phải thực thi qua BFF kèm `client_secret`. |

### 3.3. Các Endpoint chính (LinkedIn Versioned REST API)

> [!IMPORTANT]
> **Chính sách Phiên bản & Vòng đời API của LinkedIn (Version Sunset Policy):**
> - LinkedIn áp dụng cơ chế định phiên bản theo tháng với định dạng **`YYYYMM`** thông qua header `Linkedin-Version`.
> - **Thời hạn hỗ trợ:** Mỗi phiên bản API được hỗ trợ ổn định trong **tối thiểu 1 năm** kể từ ngày phát hành; thời điểm sunset cụ thể phải kiểm tra trong bảng migration/version hiện hành.
> - **Cảnh báo lỗi:** Việc gửi một phiên bản đã sunset có thể khiến request bị từ chối; mã lỗi cụ thể phải được xử lý theo response thực tế và migration notice của LinkedIn, không hard-code một mã duy nhất.
> - **Phiên bản áp dụng chuẩn hóa:** Tài liệu dùng **`202610`** làm giá trị ví dụ tại thời điểm cập nhật. Phải xác nhận lại version đang được LinkedIn cấp và ngày sunset trong bảng version/migration hiện hành trước khi triển khai; không suy ra vòng đời từ giá trị `YYYYMM` hoặc hard-code ngày sunset trong client.
> - **Quy ước triển khai:** Trong mã nguồn Kotlin của Mảng B, version này cần được định nghĩa thành hằng số tập trung (ví dụ: `private const val LINKEDIN_API_VERSION = "202610"`) để dễ bảo trì và nâng cấp định kỳ.

1. **Lấy thông tin người dùng / URN cá nhân (F1):**
   ```http
   GET https://api.linkedin.com/v2/userinfo
   Authorization: Bearer {access-token}
   ```
   **Lấy các ACL Organization mà member có vai trò quản trị (F1):**
   *(Scope yêu cầu: `rw_organization_admin`; cần role `ADMINISTRATOR` và trạng thái `APPROVED` cho các Organization API yêu cầu quản trị.)*
   ```http
   GET https://api.linkedin.com/rest/organizationAcls?q=roleAssignee&role=ADMINISTRATOR&state=APPROVED
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
     X-Restli-Protocol-Version: 2.0.0
   ```
   *Nhận về:* danh sách ACL chứa Organization URN, role, state và `roleAssignee`. Finder này trả
   về các ACL phù hợp; client phải lọc tiếp theo member URN đã xác thực nếu cần danh sách của đúng
   member. Response mặc định có thể chỉ đủ để xác định ACL; nếu cần metadata hiển thị, có thể dùng
   projection được tài liệu hỗ trợ hoặc gọi Organizations lookup.
   Request đã lọc theo finder `roleAssignee`, role `ADMINISTRATOR` và state `APPROVED`.
   Theo schema chính thức hiện hành, `roleAssignee` là trường trong từng ACL response; không thêm
   tham số query `roleAssignee=urn:li:person:{person-id}` vì endpoint không khai báo tham số đó.
   Nếu cần kiểm tra một member cụ thể, client phải lọc các phần tử response theo `roleAssignee`
   sau khi đã xác thực member URN, hoặc dùng finder được tài liệu hiện hành hỗ trợ.
   Repository vẫn phải kiểm tra lại các
   giá trị response và không suy ra quyền khác từ ACL. Sau đó trích xuất
   `{org-id}` từ `urn:li:organization:{org-id}`, rồi lưu Organization như một `ChannelAccount`
   riêng trong Room. `accountId` của account này là Organization ID/URN ổn định, không dùng
   `urn:li:person:{id}` thay thế.

   Các Organization URN thu được ở bước này là đầu vào cho các endpoint F5, F6 và F7 bên dưới:
   `author`/`owner` khi đăng hoặc upload ảnh, `targetUrn` khi đọc/trả lời comment,
   `organizationalEntity` khi lấy metrics và `orgUrn` khi lấy follower count.

   > **Lưu ý quyền:** `organizationAcls` chỉ cho biết các Organization và role mà access token có
   > thể truy vấn; từng thao tác vẫn phải kiểm tra thêm capability, sản phẩm Community Management
   > và quyền cụ thể của Organization. Không suy ra rằng có ACL là chắc chắn được đăng bài hoặc
   > đọc comment.

   > **Ghi chú lấy tên và logo cho UI F1:** Response mặc định của `organizationAcls` không nên được
   > coi là nguồn đầy đủ cho `localizedName` hoặc logo. Có thể dùng projection được tài liệu
   > `organizationAcls` hỗ trợ hoặc Organizations lookup **chỉ sau khi xác nhận đúng resource path,
   > quyền và version trong Developer Portal/tài liệu LinkedIn hiện hành**. Không hard-code
   > `/rest/organizations/{org-id}` hoặc batch `List(...)` nếu chưa có xác nhận đó.

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
3. **Đăng bài viết kèm ảnh đơn (F5 — Idempotency phía App & Mention cú pháp chuẩn):**
   ```http
   POST https://api.linkedin.com/rest/posts
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
     X-Restli-Protocol-Version: 2.0.0
     Content-Type: application/json

   {
     "author": "urn:li:organization:{org-id}",
     "commentary": "Chúc mừng Nguyễn Văn A và SocialAgent Team đã hoàn thành xuất sắc dự án!",
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
   > *Lưu ý về Idempotency:* LinkedIn Posts API không có header idempotency native chung được tài liệu chính thức xác nhận. Việc chống đăng trùng lặp hoàn toàn do ứng dụng tự quản lý qua Room DB (lưu `idempotencyKey`, nếu trùng thì trả lại kết quả bài cũ, không tạo bài mới).

4. **Đăng bài viết đa ảnh (Multi-image Post — F5):**
   - **Quy trình tải lên:** Khởi tạo `initializeUpload` và tải dữ liệu nhị phân lên `uploadUrl` cho từng ảnh (tương tự quy trình ảnh đơn) để thu thập danh sách Media URN: `[urn:li:image:{id1}, urn:li:image:{id2}, ...]`.
   - **Đăng bài bằng thuộc tính `multiImage`:**
     ```http
     POST https://api.linkedin.com/rest/posts
     Headers:
       Authorization: Bearer {access-token}
       Linkedin-Version: 202610
       X-Restli-Protocol-Version: 2.0.0
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
     - **Số lượng ảnh:** Tối thiểu **2** và tối đa **20** ảnh cho một MultiImage post. Mỗi ảnh phải thuộc quyền sở hữu của author hoặc Organization; vẫn phải xác nhận product/quyền và version đang được app cấp trước khi bật tính năng. Tài liệu hiện hành cũng giới hạn API partners ở việc tạo **non-sponsored** MultiImage post.
     - **Độ phân giải & Định dạng:** Tài liệu Images API quy định tổng độ phân giải **dưới 36.152.320 pixel** (chiều rộng $\times$ chiều cao); định dạng hỗ trợ gồm JPG, GIF, PNG. `altText` là tùy chọn và có giới hạn độ dài riêng theo schema MultiImage.
     - **Dung lượng file:** Con số "8 MB/ảnh" không có trong tài liệu chính thức của Images API (trạng thái: *Chưa xác minh trong tài liệu*; khuyến nghị thực tế nên giữ file dưới 8 MB - 10 MB để đảm bảo hiệu năng tải lên từ thiết bị di động).
     - *Phạm vi Milestone 1:* Model `PostDraft` hiện ưu tiên hỗ trợ `imageUri` (ảnh đơn), cấu trúc `multiImage` được chuẩn hóa sẵn sàng cho giai đoạn mở rộng tiếp theo.

5. **Lấy danh sách comment (F6):**
   *(Theo tài liệu Comments API hiện hành, dùng `r_organization_social_feed`; khi chọn version khác,
   phải đối chiếu lại trang endpoint và Developer Portal, không tự đổi tên scope.)*
   ```http
   GET https://api.linkedin.com/rest/socialActions/urn%3Ali%3Ashare%3A{share-id}/comments
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
     X-Restli-Protocol-Version: 2.0.0
   ```
   *Lưu ý về `{targetUrn}`:* Với thread cấp một, đây là URN của bài viết gốc (`remotePostId`, ví dụ: `urn:li:share:{share-id}` hoặc `urn:li:ugcPost:{ugcPost-id}`). Khi đọc replies của một comment, Social Actions API cho phép dùng chính `commentUrn` làm target trên path.
   > **Quy tắc URL Encoding của Rest.li 2.0.0:** URN trên URL path phải được percent-encode
   > (`:` thành `%3A`, `(` thành `%28`, `)` thành `%29`, `,` thành `%2C` nếu có). Với OkHttp,
   > dùng `HttpUrl.Builder.addPathSegment(rawUrn)` để truyền URN thô và để OkHttp percent-encode
   > đúng một lần. Không truyền sẵn `%3A` khi thêm path segment, vì sẽ tạo double-encoding.
   > Không encode cả chuỗi URL bằng tay sau khi OkHttp đã dựng request.

6. **Trả lời comment (Nested Reply — F6):**
   *(Theo tài liệu Comments API hiện hành, dùng `w_organization_social_feed`; khi chọn version khác,
   phải đối chiếu lại trang endpoint và Developer Portal, không tự đổi tên scope.)*
   ```http
   POST https://api.linkedin.com/rest/socialActions/{targetUrn}/comments
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
     X-Restli-Protocol-Version: 2.0.0
     Content-Type: application/json

   {
     "actor": "urn:li:organization:{org-id}",
     "object": "{targetUrn}",
     "message": {
       "text": "Cảm ơn sự đóng góp của bạn!"
     },
     "parentComment": "{parentCommentUrn}"
   }
   ```
   *Lưu ý kỹ thuật & Hướng dẫn triển khai `SocialChannelClient.reply(commentId, text)` để tránh lỗi nhầm lẫn URN:*
   - **Phân định rõ `{targetUrn}` trên URL & `"object"` trong body:**
     - Trường `"object"` là URN của share/UGC post chứa comment. Với reply lồng nhau, `{targetUrn}` trên URL có thể là `commentUrn` của comment cha; với comment cấp một, `{targetUrn}` là URN bài viết. Không được đặt `commentUrn` vào trường `"object"`.
     - Trường `"parentComment"` chỉ dùng cho nested reply và là URN của chính comment cha (`parentCommentUrn`).
   - **Khớp nối với Interface Contract của dự án (`SocialChannelClient.reply`):**
     - Chữ ký hàm trong `core/social/SocialChannelClient.kt` chỉ nhận: `suspend fun reply(commentId: String, text: String)`. Implementation `LinkedInClient` dự kiến đặt tại `data/social/LinkedInClient.kt` (hoặc `data/linkedin/LinkedInClient.kt`) và được cung cấp qua module trong `di/`.
     - Để lấy được URN bài viết gốc và `parentCommentUrn` khi thực thi cuộc gọi API này trong `LinkedInClient`:
       1. **Cách 1 (Khuyến nghị theo Data Model F6):** Model `Comment` trong `core/model/Comment.kt` đã lưu sẵn trường `remotePostId` (chính là `{targetUrn}` của bài viết gốc). Khi người dùng bấm trả lời một comment, Repository tra cứu bản ghi `Comment` trong Room DB theo `commentId` để lấy `remotePostId` tương ứng và truyền vào client.
       2. **Cách 2 (Trích xuất từ Compound URN):** Định dạng comment URN của LinkedIn là URN phức hợp lồng nhau: `urn:li:comment:({threadUrn},{comment-sub-id})`. Lớp `LinkedInClient` có thể parse `threadUrn` để xác định thread gốc, nhưng nên ưu tiên `remotePostId` từ database vì thread URN có thể là Activity URN khác với URN lưu cho bài viết.
   - **Header `X-Restli-Protocol-Version: 2.0.0`:** Bắt buộc phải có để LinkedIn Rest.li framework xử lý đúng các ký tự đặc biệt (dấu ngoặc đơn, dấu phẩy) trong Compound URN của `parentComment`.
   - **Encoding của endpoint reply:** `{targetUrn}` trên path dùng cùng quy tắc như endpoint đọc
   comment: truyền URN thô vào `HttpUrl.Builder.addPathSegment(rawUrn)` để OkHttp encode một lần.
   Các URN trong JSON body (`actor`, `object`, `parentComment`) giữ ở dạng URN hợp lệ của JSON;
   không percent-encode thủ công nội dung body.
7. **Lấy số liệu bài viết (F7):**
   *(Scope yêu cầu: `rw_organization_admin`; người dùng phải có role `ADMINISTRATOR` và Product/endpoint phải được cấp trên Developer Portal.)*
   
   Theo tài liệu chính thức về [Share Statistics API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics), endpoint này cung cấp hai tham số lọc riêng biệt tùy theo định dạng URN của bài viết:
   - **Trường hợp URN dạng Share (`urn:li:share:{id}`):** Dùng tham số `shares=List(...)`:
     ```http
     GET https://api.linkedin.com/rest/organizationalEntityShareStatistics?q=organizationalEntity&organizationalEntity=urn%3Ali%3Aorganization%3A{org-id}&shares=List(urn%3Ali%3Ashare%3A{share-id})
     Headers:
       Authorization: Bearer {access-token}
       Linkedin-Version: 202610
       X-Restli-Protocol-Version: 2.0.0
     ```
   - **Trường hợp URN dạng UGC Post (`urn:li:ugcPost:{id}`):** Dùng tham số chỉ mục
     `ugcPosts[0]` (và `ugcPosts[1]`, ...) theo schema hiện hành:
     ```http
     GET https://api.linkedin.com/rest/organizationalEntityShareStatistics?q=organizationalEntity&organizationalEntity=urn%3Ali%3Aorganization%3A{org-id}&ugcPosts[0]=urn%3Ali%3AugcPost%3A{ugc-post-id}
     Headers:
       Authorization: Bearer {access-token}
       Linkedin-Version: 202610
       X-Restli-Protocol-Version: 2.0.0
     ```
   > **Quy tắc encode query của Rest.li 2.0.0 (với OkHttp):** Với `shares=List(...)`, dấu `:`
   > trong từng URN phải được percent-encode; khi có nhiều share, dấu phẩy trong `List(...)`
   > cũng phải được encode theo ví dụ chính thức. Với UGC Post, dùng tham số chỉ mục
   > `ugcPosts[0]`, `ugcPosts[1]`, ... và encode giá trị URN. Khi dùng `HttpUrl.Builder`, phải
   > dùng đúng cơ chế `addEncodedQueryParameter`/tên tham số chỉ mục và encode mỗi URN đúng một
   > lần; không truyền URN đã encode vào API lần thứ hai.

   *Quy tắc triển khai & Bẫy thực tế khi parse dữ liệu (Crucial Implementation Pitfalls):*
   - **Các trường dữ liệu trả về:** `likeCount`, `commentCount`, `shareCount`, `impressionCount`, `uniqueImpressionsCount` (lượt hiển thị duy nhất), `clickCount`, `engagement`.
   - **Bẫy 1 — Bài viết chưa có tương tác không xuất hiện trong `elements`:** Nếu bài viết (`share` hoặc `ugcPost`) chưa từng có bất kỳ tương tác hoặc hiển thị nào, LinkedIn sẽ **không trả về phần tử tương ứng trong mảng `elements`** của JSON response. Phía client parser phải xử lý an toàn: nếu không tìm thấy URN trong `elements`, coi tất cả các chỉ số (`likeCount`, `commentCount`, `shareCount`, `impressionCount`, `uniqueImpressionsCount`, `clickCount`, `engagement`) có giá trị mặc định là `0`, tuyệt đối không throw ngoại lệ `NoSuchElementException`.
   - **Bẫy 2 — `likeCount` có thể mang giá trị âm:** Theo tài liệu chính thức của LinkedIn Share Statistics, `likeCount` có thể mang giá trị âm (do độ trễ cập nhật batch khi người dùng unlike hoặc hủy reaction). Lớp parser phía app phải bọc qua `max(0, rawLikeCount)` trước khi lưu vào Room DB hoặc hiển thị lên giao diện F7 để tránh hiển thị số âm bất thường.
   - **Bẫy 3 — Khuyến nghị gọi `socialActions` cho Like/Comment thời gian thực:** Tài liệu LinkedIn nêu rõ `organizationalEntityShareStatistics` là hệ thống tổng hợp số liệu theo lô (batch computation) nên có độ trễ cập nhật nhất định. Để lấy số lượng like/reaction và comment khớp chính xác theo thời gian thực với bài đăng trên feed, tài liệu khuyến nghị gọi trực tiếp endpoint `GET /rest/socialActions/{targetUrn}`.
   - **Phân biệt định dạng URN:** Bài viết tạo qua endpoint `POST /rest/posts` trả về định danh trong header `x-restli-id`; phải lưu nguyên giá trị response và kiểm tra loại URN thực tế trước khi gọi endpoint metrics. Share Statistics hỗ trợ tham chiếu `share` hoặc `ugcPost` theo tham số tương ứng; không tự đổi tiền tố hoặc suy diễn một định dạng duy nhất.
   - Mã nguồn Mảng B cần phân tích tiền tố của URN bài viết lưu trong Room DB để ánh xạ sang tham số tương ứng (`shares` hoặc `ugcPosts[0]`, tăng chỉ số khi truy vấn nhiều UGC Post), và phải báo lỗi rõ ràng nếu nhận URN ngoài các dạng được endpoint metrics hỗ trợ.
   - ⚠️ *Cần kiểm chứng thực tế:* Do `organizationalEntityShareStatistics` là endpoint thống kê kế thừa, nhóm cần gọi thử nghiệm trực tiếp trên môi trường dev với ID bài đăng vừa tạo từ `POST /rest/posts` để xác nhận định dạng URN tương thích chính xác trên phiên bản `202610`.
   - **Giới hạn dữ liệu:** Endpoint có giới hạn cửa sổ truy vấn theo tài liệu/version hiện hành (tài liệu hiện được đối chiếu mô tả cửa sổ rolling **12 tháng**); không dùng endpoint này để giả định có thể truy vấn lịch sử lâu hơn.
8. **Lấy số follower (F7):**
   *(Scope yêu cầu: `rw_organization_admin`; cần kiểm tra thêm vai trò `ADMINISTRATOR` và quyền của app)*
   ```http
   GET https://api.linkedin.com/rest/networkSizes/urn%3Ali%3Aorganization%3A{org-id}?edgeType=COMPANY_FOLLOWED_BY_MEMBER
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
     X-Restli-Protocol-Version: 2.0.0
   ```
   *Trả về:* `firstDegreeSize`.
   
   *Lưu ý về tham số `edgeType`:*
   - Với versioned REST API hiện hành, tài liệu Organization Lookup sử dụng giá trị enum **`COMPANY_FOLLOWED_BY_MEMBER`**.
   - Không dùng giá trị camelCase cũ nếu version/product hiện hành không còn tài liệu hỗ trợ; khi nâng version phải kiểm tra lại enum và response thực tế.

### 3.4. Giới hạn (Rate Limits) & Quy định kiểm duyệt
- **Độ dài bài viết tối đa:** `3,000` ký tự (khớp cấu hình trong `ChannelType.LINKEDIN(maxChars = 3_000)`).
- **Kích thước ảnh & Định dạng:**
  - **Giới hạn pixel (Chính thức theo Images API):** Tổng độ phân giải mỗi ảnh phải **dưới 36.152.320 pixel** (chiều rộng $\times$ chiều cao). Định dạng hỗ trợ: JPG/JPEG, GIF, PNG.
  - **Dung lượng file:** Trạng thái: *Chưa xác minh mức trần cụ thể trong tài liệu Images API* (con số 8 MB là khuyến nghị thực tế để tránh timeout khi tải lên di động).
  - Tỉ lệ khuyến nghị: `1.91:1` (ngang: 1200 x 627 px) hoặc `1:1` (vuông: 1200 x 1200 px).
- **Hạn ngạch & Giới hạn tần suất gọi (Rate Limits & Quotas theo Product / Tier):**
  - **Sản phẩm Share on LinkedIn (Đăng bài cá nhân):**
    - LinkedIn không công bố một quota chuẩn chung trong tài liệu cho mọi endpoint. Hạn mức application/member thực tế phải xem trên Developer Portal Analytics sau khi app gọi endpoint tương ứng.
    - Hạn mức được tính theo số request API trong cửa sổ 24 giờ và reset vào 00:00 UTC; không đồng nhất với số bài đăng vì bài có media còn phát sinh các request upload.
  - **Sản phẩm Community Management API (Page Công ty — F6/F7):**
    - Không hard-code quota Development/Standard Tier theo một con số cố định. Tài liệu LinkedIn nêu rằng giới hạn application/member thay đổi theo endpoint, API Product và cấp độ ứng dụng; hạn mức thực tế phải xem trên Developer Portal Analytics sau khi app gọi endpoint tương ứng.
    - Không hard-code quota trong client. Hạn mức là giới hạn application/member theo endpoint và app; tài liệu rate-limit hiện hành mô tả chu kỳ 24 giờ và reset lúc 00:00 UTC, nhưng giá trị quota cụ thể vẫn phải lấy từ Developer Portal.
  - **Cơ chế xử lý khi vượt ngưỡng:** LinkedIn có thể trả về HTTP `429 Too Many Requests`; client
    phải coi đây là lỗi tạm thời có điều kiện, áp dụng backoff có giới hạn và không mặc định rằng
    response luôn có header `Retry-After` hoặc tự suy ra thời điểm phục hồi. Chỉ map sang
    `PublishResult.RetryableError` khi response/endpoint cho thấy đúng là lỗi rate limit.
- **Theo dõi Quota & Usage thực tế theo từng endpoint trên LinkedIn Developer Portal:**
  - Theo tài liệu chính thức từ LinkedIn về [API Rate Limits](https://learn.microsoft.com/en-us/linkedin/shared/api-guide/concepts/rate-limits), LinkedIn **không công bố một hạn mức chuẩn chung phổ quát** cho tất cả các endpoint, mà rate limit được thiết lập riêng biệt theo từng API Product, từng endpoint và cấp độ ứng dụng.
  - Do đó, **tài liệu ưu tiên phương pháp đối chiếu và kiểm tra trực tiếp hạn ngạch theo từng endpoint trên tab Analytics của Developer Portal**:
    1. Đăng nhập [LinkedIn Developer Portal](https://developer.linkedin.com/), chọn ứng dụng `SocialAgent App`.
    2. Chọn tab **Analytics** trên thanh menu điều hướng của ứng dụng.
    3. Chọn xem theo bộ lọc **Application Quota** hoặc **Member Quota** để theo dõi số lượng call đã thực hiện và hạn ngạch còn lại trong ngày theo từng endpoint.
    4. **Lưu ý hiển thị của Portal:** Tab *Analytics* chỉ là nguồn quan sát usage/rate limit mà LinkedIn hiển thị cho từng app/product/endpoint. Không dùng trạng thái hiển thị của Portal để suy ra quota runtime hoặc tự tạo request chỉ nhằm “kích hoạt” một hạn mức; khi cần xác minh, dùng request hợp lệ trong môi trường Development và đối chiếu tài liệu hiện hành.
- **Quyền nào cần App Review / Phê duyệt Sản phẩm (Products):**
  - *Sản phẩm Share on LinkedIn & Sign In with OpenID Connect:* Có thể cung cấp luồng xin các scope `openid`, `profile` và `w_member_social`, nhưng không được coi sản phẩm là bằng chứng rằng mọi scope đã được cấp tự động cho mọi app. Runtime phải đọc scope/token thực tế và kiểm tra Developer Portal; nếu `w_member_social` không được cấp thì không gán capability `PUBLISH`.
  - *Sản phẩm Community Management API (Cần đăng ký & duyệt):* Chỉ ghi các scope thực sự được
    Developer Portal cấp cho app. Tên scope có thể khác giữa các trang endpoint/version (ví dụ
    `r_organization_social_feed` ở tài liệu Comments API hiện hành); không suy ra scope hợp lệ chỉ
    từ tên product hoặc từ một endpoint khác.
- **Chế độ Development dùng được tới đâu:**
  - **Phạm vi hoạt động:** Có thể kiểm thử đăng bài lên trang cá nhân của Developer/Tester và tương tác với Organization Page mà thành viên có vai trò phù hợp, nếu app đã được cấp đúng product/scope. Việc là Developer/Tester không tự cấp quyền quản trị Organization hoặc thay thế điều kiện của Community Management API. Rate limit cụ thể theo endpoint/member phải kiểm tra trên Developer Portal Analytics; không coi Development Tier có quota cố định chung cho mọi endpoint.
  - **Dự phòng rào cản kiểm duyệt:** Nếu LinkedIn chưa kịp cấp quyền Community Management API cho Organization Page, app có thể kích hoạt luồng **`HANDED_OFF`** (Mục 4) để copy bài và mở Android Share Intent.

---

## 4. Chức năng không làm được qua API & Đề xuất "Đã bàn giao" (`HANDED_OFF`)

### 4.1. Các hạn chế bất khả kháng từ chính sách API
1. **Đăng bài lên Facebook cá nhân (Personal Profile Timeline):** Meta đã đóng hoàn toàn quyền `publish_actions` từ năm 2018. Không ứng dụng bên thứ ba nào được phép đăng bài trực tiếp lên trang cá nhân của người dùng qua API (chỉ cho phép đăng lên Page).
2. **Mention người dùng cá nhân trên Facebook & Rào cản tìm kiếm ID:**
   - *Meta (Facebook):* Mention chỉ là capability có điều kiện. App phải xác nhận endpoint/version, Page task, product và định danh Page-scoped ID được phép trước khi thử; đây không phải cơ chế tìm kiếm/mention tự do mọi hồ sơ cá nhân. Nếu điều kiện không được xác nhận hoặc request bị từ chối, app kích hoạt luồng `HANDED_OFF` để người dùng tag trực tiếp trên app Facebook.
   - *LinkedIn:* Việc mention Member/Organization bằng URN trong nội dung bài đăng **chưa được coi là capability được bảo đảm chung**; chỉ bật sau khi kiểm thử thành công với Posts API, product, version và quyền cụ thể của ứng dụng. Nếu chưa xác nhận hoặc không có URN hợp lệ, hệ thống kích hoạt luồng `HANDED_OFF` để người dùng mention trực tiếp trên ứng dụng mạng xã hội.
3. **App chưa được cấp quyền Public / Chưa qua App Review:** Người dùng ngoài danh sách Developer/Tester có thể không dùng được các permission/product yêu cầu Advanced Access hoặc App Review. Business Verification, screencast và điều kiện bổ sung chỉ áp dụng khi Meta yêu cầu cho permission/product cụ thể; không suy ra rằng mọi permission Pages đều bắt buộc cùng một điều kiện.
4. **Kênh không thể tự khắc phục ở runtime:** Nếu sau khi kiểm tra token, permission, Page task, product và endpoint mà thao tác vẫn không được cấp quyền, client không nên retry vô hạn và có thể dùng `HANDED_OFF`. Không gắn nhãn `PermanentError` chỉ từ một response permission error; phải giữ thông tin lỗi để phân biệt thiếu quyền tạm thời, token bị thu hồi và capability không được hỗ trợ.

### 4.2. Cơ chế giải pháp "Đã bàn giao" (`HANDED_OFF`)
Khớp với `PostStatus.HANDED_OFF` trong `core/model/Post.kt` (yêu cầu F5 trong `requirements.md`):
```
PostStatus: DRAFT → QUEUED → PUBLISHING → HANDED_OFF
```
Thay vì xem trường hợp không gọi được API là lỗi thất bại (`PostStatus.FAILED`), hệ thống chuyển sang luồng **Bàn giao quyền đăng bài cho người dùng** thông qua Android OS native capabilities:

1. **Sao chép nội dung vào Clipboard:** Tự động sao chép toàn bộ text bài viết (kèm hashtag và link nếu có) vào `android.content.ClipboardManager`.
2. **Chia sẻ qua Android Share Intent (`Intent.ACTION_SEND` / `Intent.ACTION_SEND_MULTIPLE`):**
   - Hỗ trợ cả text và file ảnh. `draft.imageUri` phải là `content://` URI do
     `FileProvider.getUriForFile(...)` tạo ra; không được truyền trực tiếp `file://` hoặc đường dẫn
     `/data/user/0/...`, vì Android 7.0+ có thể ném `FileUriExposedException`. URI phải trỏ tới file
     nằm trong các path đã khai báo cho `FileProvider`.
   - **Bài viết text hoặc kèm 1 ảnh đơn (`Intent.ACTION_SEND` — chuẩn `Draft` Milestone 1):**
     ```kotlin
     val shareIntent = Intent(Intent.ACTION_SEND).apply {
         type = if (draft.imageUri != null) "image/*" else "text/plain"
         putExtra(Intent.EXTRA_TEXT, draft.text)
         draft.imageUri?.let { uriStr ->
            // uriStr là content:// URI đã được cấp bởi FileProvider.
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
   - Trước khi gọi `startActivity(chooser)`, chỉ hiển thị `Toast` ngắn hoặc để Android tự hiển thị
     clipboard preview; không mở Dialog/Snackbar đè lên Share Sheet. Trên Android 13+ (API 33+),
     Clipboard Overlay và Share Sheet có thể che khuất hoặc cạnh tranh với UI của app.
   - Khi người dùng quay lại app, có thể hiển thị Snackbar: *"Nội dung bài viết đã được sao chép vào
     bộ nhớ tạm. Hãy dán vào ứng dụng mạng xã hội để hoàn tất đăng bài."*
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
    - Cho Luồng Standard qua BFF: Dùng URL HTTPS trỏ về máy chủ backend/BFF (ví dụ: `https://api.yourdomain.com/auth/linkedin/callback` hoặc Cloudflare Worker endpoint). Việc dùng custom scheme trực tiếp phụ thuộc vào giao thức và cấu hình Developer Portal được cấp; không coi đây là redirect mặc định cho Standard OAuth nếu chưa được LinkedIn xác nhận.

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
   - **Phạm vi xung đột với Quy tắc 5 (AGENTS.md) — Xem chi tiết tại §3.1:**
      - Quy tắc 5 trong `AGENTS.md` quy định: *"Không bao giờ log, commit hay gửi API key/token lên bất kỳ server nào ngoài nhà cung cấp LLM/mạng xã hội. Key lưu bằng Android Keystore."*
      - **Phạm vi mâu thuẫn đầy đủ** (không chỉ riêng refresh token):
        - ✅ **Tuân thủ hoàn toàn:** LinkedIn Luồng A (Native PKCE) + Facebook Phương án 1 (Meta SDK).
        - ⚠️ **Cần nhóm thống nhất ngoại lệ:** LinkedIn Luồng B + Facebook Phương án 2 (Web OAuth qua BFF) — access token đi qua BFF cache ≤ 60 giây khi đổi `exchange_code`.
        - ❌ **Xung đột rõ ràng, yêu cầu Policy Exception qua issue `contract`:** Lưu `refresh_token` LinkedIn vào DB server (thực tế rất khó áp dụng với đồ án vì đòi hỏi điều kiện đối tác MDP).
      - **Baseline chính thức Milestone 1:** Chỉ triển khai Luồng A (LinkedIn Native PKCE khi đã được
        bật) và Phương án 1 (Facebook Meta SDK). Luồng B/Phương án 2 cùng mọi endpoint BFF nằm ngoài
        phạm vi Milestone 1 và không được dùng làm fallback trong repository Android.
   - **Quy trình bảo mật khi "Ngắt kết nối" (Disconnect Flow — F1):**
     Khi người dùng bấm ngắt kết nối một tài khoản hoặc kênh trên ứng dụng:
     - **Đối với Kênh Facebook:**
       - *Nếu dùng luồng Web OAuth qua BFF:* App gửi request `POST /auth/facebook/logout` kèm header `Authorization: Bearer {bff_session_token}` để BFF vô hiệu hóa phiên làm việc và dọn dẹp session cache phía server.
       - *Nếu dùng Meta SDK:* Gọi `LoginManager.getInstance().logOut()` để SDK xóa bỏ phiên người dùng trong bộ nhớ cache của SDK.
       - *Dọn dẹp phía Client:* Xóa sạch toàn bộ User Access Token, mã phiên `bff_session_token`, và **toàn bộ Page Access Token** (`fb_page_token_{page_id}`) của các Page thuộc tài khoản đó khỏi `EncryptedSharedPreferences`; cập nhật hoặc xóa metadata kênh trong Room DB.
     - **Đối với Kênh LinkedIn:**
       - *Thiết kế logout phía BFF (`POST /auth/linkedin/logout`):* Đối với các phiên kết nối qua BFF (đặc biệt khi BFF lưu trữ `refresh_token` trên server DB), app có thể gửi request `POST /auth/linkedin/logout` kèm header `Authorization: Bearer {bff_session_token}` và `account_id`. Máy chủ BFF nên xóa token đã lưu, vô hiệu hóa `bff_session_token` và dọn dẹp session theo chính sách của hệ thống. Đây là thiết kế nội bộ của ứng dụng, không phải hành vi logout được LinkedIn API bảo đảm cho mọi sản phẩm.
       - *Dọn dẹp phía Client:* Xóa sạch `access_token`, `refresh_token` (nếu có), và `bff_session_token` khỏi `EncryptedSharedPreferences`; cập nhật trạng thái kênh trong Room DB thành `ConnectionStatus.DISCONNECTED` (hoặc xóa tài khoản/kênh khỏi Room DB).

### 5.4. Kiến trúc tích hợp và bài toán đa tài khoản

#### Vấn đề cần giải quyết

`SocialChannelClient` hiện định danh implementation bằng `ChannelType`, trong khi token thực tế
được cấp cho từng tài khoản hoặc Page. Vì vậy, chỉ inject một client cố định theo `FACEBOOK` hoặc
`LINKEDIN` là chưa đủ để xử lý các trường hợp sau:

- Người dùng kết nối nhiều Facebook Page dưới cùng một tài khoản Facebook.
- Người dùng kết nối đồng thời LinkedIn Member và LinkedIn Organization.
- Một bài viết, comment hoặc số liệu phải được gọi đúng bằng token của `accountId` tương ứng.

Nếu chọn token chỉ theo `ChannelType`, request có thể dùng nhầm Page Access Token. Hậu quả là bài
viết được đăng lên sai Page, comment không đọc được hoặc số liệu bị gán nhầm tài khoản. `accountId`
phải được coi là một phần của ngữ cảnh gọi API, không phải dữ liệu chỉ dùng để hiển thị trên UI.

#### Phương án được đề xuất

Implementation thật của các client dự kiến đặt trong `data/social/` (hoặc tách thành
`data/facebook/` và `data/linkedin/`), gồm `FacebookClient.kt` và `LinkedInClient.kt`.
Các feature chỉ phụ thuộc `core/social/SocialChannelClient.kt`; Hilt cung cấp implementation
qua module tương ứng trong `di/`, không import trực tiếp lớp trong `data/` của mảng khác.

Mở một issue nhãn `contract` để thiết kế factory/context theo hướng:

```kotlin
interface SocialChannelClientFactory {
    fun create(accountId: String): SocialChannelClient
}
```

Đây chỉ là **đề xuất thiết kế**, chưa phải contract được phép thêm vào `core/`. Trước khi mở rộng
interface, nhóm cần thống nhất:

1. `accountId` là ID ổn định của Page/Organization/Member và phải khác với `ChannelType`.
2. Factory phải kiểm tra metadata trong Room để xác định `ChannelType`, trạng thái kết nối và
   capability trước khi tạo client.
3. Client chỉ lấy token sau khi đã có `accountId`; token được tra cứu từ
   `EncryptedSharedPreferences` bằng key có namespace và account ID, ví dụ
   `fb_page_token_{accountId}`. Không lưu token plaintext trong Room.
4. `publish`, `fetchComments`, `reply` và `fetchMetrics` phải dùng cùng account context trong
   toàn bộ một thao tác. Không suy ra account từ `remotePostId` hoặc từ client mặc định.
5. Khi `accountId` không tồn tại, đã ngắt kết nối, hết hạn token hoặc thiếu capability, factory/
   repository phải trả lỗi rõ ràng; không được fallback im lặng sang tài khoản khác.
6. Với LinkedIn, `Comment.remotePostId` vẫn được dùng để lấy URN bài viết gốc khi reply, còn
   `accountId` quyết định Organization/Member token dùng cho request. Hai loại định danh này không
   được trộn lẫn.

#### Trình tự gọi dự kiến

```text
UI/ViewModel
    -> Repository(accountId)
    -> SocialChannelClientFactory.create(accountId)
    -> Client lấy token theo accountId từ secure storage
    -> API của Facebook Page hoặc LinkedIn Member/Organization
```

#### Phạm vi và tiêu chí chấp nhận

- Không thay đổi chữ ký hiện tại của `SocialChannelClient` trong issue nghiên cứu này.
- Issue contract phải bao gồm migration cho Hilt, Repository, fake client và test đa tài khoản.
- Test bắt buộc chứng minh hai `accountId` dùng hai token và endpoint context khác nhau; không chỉ
  kiểm tra khác `ChannelType`.
- Phải có test khi một account bị disconnect/expired để bảo đảm request bị từ chối rõ ràng và không
  rơi sang account khác.

---

## 6. Checklist trước khi triển khai

Các mục dưới đây phải được xác nhận bằng tài liệu portal hoặc integration test trước khi chuyển từ
`FakeSocialChannelClient` sang client thật:

- [ ] Meta Developer Portal xác nhận loại Login đang bật, `config_id` (nếu dùng Login for Business),
      redirect URI và các quyền cần App Review.
- [ ] Facebook Page test xác nhận các thao tác đăng bài, đọc/trả lời comment và lấy metrics bằng token
      Page thật; không suy luận capability chỉ từ HTTP status.
- [ ] Xác nhận metric insights và field follower đang khả dụng trên version Graph API được App cấp.
- [ ] LinkedIn xác nhận sản phẩm Community Management, quyền Organization và định dạng URN trả về từ
      endpoint đăng bài.
- [ ] LinkedIn xác nhận Native PKCE cho App ID; nếu chưa có, mở issue backend/BFF riêng trước khi triển
      khai Standard OAuth.
- [ ] Kiểm thử retry, rate limit, token revoked/expired và fallback `HANDED_OFF` trên môi trường Development.
- [ ] Kiểm tra disconnect xóa token cục bộ; nếu có BFF thì phải có quy trình thu hồi server-side trước khi
      coi kết nối đã ngắt hoàn toàn.

## 7. Tóm tắt nội dung dùng cho Slide Báo cáo (Research Presentation)

Dưới đây là dàn ý nội dung được thiết kế sẵn theo từng slide (kèm gợi ý hình ảnh/sơ đồ và lời thuyết trình) giúp nhóm dễ dàng đưa vào PowerPoint / Google Slides:

### 🎯 Slide 1: So sánh khả năng kênh & Đối chiếu Kiến trúc (Channel Capabilities)
* **Ý chính trình chiếu:**
  * Đối chiếu trực tiếp với `enum class ChannelCapability`: `PUBLISH`, `READ_COMMENTS`, `REPLY`, `MENTION`, `METRICS`.
  * **Facebook Page:** Hỗ trợ đăng bài, comment/reply và metrics theo Page task, permission và version; mention là capability có điều kiện. Không hỗ trợ native Idempotency $\rightarrow$ Quản lý qua Room DB cục bộ.
  * **LinkedIn Organization:** Hỗ trợ Company Page theo product/quyền được cấp; mention và idempotency header chỉ dùng sau khi xác minh theo endpoint/version.
  * **LinkedIn Member (Cá nhân):** Có thể đăng bài cơ bản khi app được cấp `w_member_social`; quyền đọc/trả lời comment, metrics và mention không được coi là capability mặc định.
* **Gợi ý Visual:** Bảng ma trận so sánh 3 cột (Facebook Page | LinkedIn Org | LinkedIn Member) với các icon tick xanh ✅ và cảnh báo ⚠️.

---

### 🔐 Slide 2: Kiến trúc Xác thực An toàn — Public Client Không Giữ Secret
* **Ý chính trình chiếu:**
  * **Chuẩn bảo mật Native (RFC 8252 & RFC 6750):** Ứng dụng Android là Public Client $\rightarrow$ Loại bỏ hoàn toàn `client_secret` khỏi APK và mã nguồn. Tuyệt đối không truyền `access_token` trong URL/Deep Link.
  * **Cơ chế bảo vệ mã ủy quyền trong Milestone 1:** Facebook dùng Meta Android SDK; LinkedIn dùng
    Native PKCE qua Loopback Redirect (`127.0.0.1`) nếu Developer Portal đã bật protocol, kèm
    xác thực `state` chống CSRF. AppAuth/Custom Tabs + BFF chỉ là thiết kế tham khảo ngoài phạm vi.
  * **Định danh ứng dụng an toàn:**
    * *Facebook:* Meta Android SDK (App ID + Package Name + SHA-1 Key Hash). Web OAuth qua BFF
      chỉ nằm trong tài liệu tham khảo ngoài phạm vi.
    * *LinkedIn:* Client ID công khai + luồng Native PKCE (`/oauth/native-pkce/`) qua Loopback Redirect
      không giữ secret. Standard OAuth qua BFF chỉ là phương án backend tham khảo.
  * **Vòng đời & Lưu trữ Token an toàn:** 
    * *Facebook:* Long-lived User Token thường khoảng 60 ngày nhưng không phải TTL cố định; Meta SDK
    quản lý một phần vòng đời token. Ứng dụng vẫn phải kiểm tra hết hạn/thu hồi và Page
    Token phải được kiểm thử trên Page development.
    * *LinkedIn:* Access Token thường có thời hạn 60 ngày $\rightarrow$ khi hết hạn có thể phải mở lại luồng OAuth và xác nhận consent hoặc đăng nhập lại; runtime phải dùng `expires_in` thực tế. Nếu Native
    PKCE chưa được cấp, tính năng bị blocked trong Milestone 1.
    * *Lưu trữ:* Token nhạy cảm chỉ lưu trong `EncryptedSharedPreferences` (Keystore); Room DB chỉ
    quản lý metadata phi nhạy cảm. Luồng logout BFF và `bff_session_token` chỉ thuộc thiết kế backend
    tham khảo.
* **Gợi ý Visual:** Sơ đồ Meta SDK và LinkedIn Native PKCE Loopback cho Milestone 1; phần BFF chỉ
  được trình bày như phương án tương lai ngoài phạm vi.

---

### ⚙️ Slide 3: Endpoints Cốt lõi & Giới hạn Kỹ thuật (Technical Specs)
* **Ý chính trình chiếu:**
  * **Đăng bài có ảnh (F5):**
    * *Facebook:* 1 bước qua `POST /{page-id}/photos` (ảnh đơn) hoặc 2 bước qua `attached_media` (đa ảnh).
    * *LinkedIn:* `initializeUpload` $\rightarrow$ PUT binary $\rightarrow$ Đăng bài với Image URN (ảnh đơn) hoặc `multiImage` (2–20 ảnh) nếu product/quyền của app hỗ trợ; mỗi ảnh phải dưới 36.152.320 pixel.
  * **Tương tác & Số liệu (F6, F7):** Đọc comment phân cấp, reply theo ID/URN, lấy insights (sử dụng metric được version hiện hành hỗ trợ, ví dụ `post_media_view`) và follower count.
  * **Giới hạn kiểm soát:**
    * *Độ dài bài:* Facebook (63.206 ký tự) vs LinkedIn (3.000 ký tự) $\rightarrow$ Đã cấu hình cứng trong `ChannelType`.
    * *Kích thước ảnh:* Facebook (giới hạn dung lượng multipart chưa được xác minh trong tài liệu Pages API) vs LinkedIn (dưới 36.152.320 pixel/ảnh).
    * *Rate limit:* Facebook (giới hạn động theo BUC / Page Engaged Users) vs LinkedIn (giới hạn application/member thay đổi theo Product và endpoint); quota thực tế phải kiểm tra trên Portal Analytics và response thực tế.
* **Gợi ý Visual:** Bảng so sánh 2 cột trực quan các chỉ số dung lượng, ký tự và Rate Limit.

---

### 🛡️ Slide 4: Rào cản Chính sách & Giải pháp "Đã bàn giao" (`HANDED_OFF`)
* **Ý chính trình chiếu:**
  * **Rào cản API bất khả kháng:**
    1. Meta cấm bên thứ ba đăng bài lên Facebook cá nhân (đóng quyền `publish_actions` từ 2018).
    2. Meta chỉ cho Page mention người đã đăng bài/comment khi có Page-scoped ID và đủ điều kiện; LinkedIn chỉ bật mention Member/Organization sau khi kiểm thử endpoint/product cụ thể với URN hợp lệ.
    3. Rào cản kiểm duyệt App Review và điều kiện Advanced Access thay đổi theo permission/product; Business Verification hoặc tài liệu doanh nghiệp chỉ được yêu cầu khi Meta hiển thị cho trường hợp cụ thể.
  * **Giải pháp 1 (Nội bộ):** Chạy ứng dụng ở **Development Mode** $\rightarrow$ Dành cho tài khoản được gán vai trò Tester/Developer trong App Roles và có Page Tasks quản trị trên Facebook Page thử nghiệm.
  * **Giải pháp 2 (Dự phòng F5 - `HANDED_OFF`):** 
    * Khi không thể gọi API và đã xác nhận lỗi phân quyền không thể tự khắc phục (sau khi phân tích `error.code`, `error_subcode`, endpoint, Page task và trạng thái quyền): Copy text vào Clipboard $\rightarrow$ Kích hoạt Android Share Intent native $\rightarrow$ Cập nhật trạng thái `PostStatus.HANDED_OFF`.
    * Đảm bảo luồng trải nghiệm không bao giờ đứt gãy.
* **Gợi ý Visual:** Sơ đồ rẽ nhánh: [Agent soạn bài] $\rightarrow$ [Đủ quyền API? $\rightarrow$ Gọi API] HOẶC [Thiếu quyền $\rightarrow$ Copy Clipboard + Mở Share Sheet $\rightarrow$ Badge HANDED_OFF].

---

### 🚀 Slide 5: Kiến nghị Contract & Lộ trình Milestone 1
* **Ý chính trình chiếu:**
  * **Kiến nghị Contract (`SocialChannelClient`):** Như phân tích tại §5.4, đề xuất mở Issue nhãn
    `contract` để bổ sung `SocialChannelClientFactory`, giúp mỗi lệnh gọi gắn với đúng `accountId`
    (Facebook Page, LinkedIn Member hoặc LinkedIn Organization). Đây là đề xuất, chưa thay đổi
    `SocialChannelClient` hiện tại.
  * **Kế hoạch triển khai:**
    * *Tuần 1:* Họp nhóm chốt contract/context đa tài khoản, tiêu chí test tại §5.4 và hoàn thiện cấu
      hình Developer Portals; xác nhận LinkedIn Native PKCE được bật cho App ID.
    * *Tuần 2:* Triển khai Meta Android SDK cho Facebook và LinkedIn Native PKCE Loopback (`127.0.0.1`);
      tích hợp secure storage lưu token theo `accountId` và Room DB quản lý metadata. Không triển khai
      AppAuth/BFF trong Milestone 1.
  * **Checklist kiểm thử tích hợp thực tế (Integration Verification Checklist):**
    Các điểm cần gọi thử nghiệm thực tế với Access Token thật trên môi trường dev trong Tuần 1 để chốt cấu hình:
    1. **Quyền của `organizationAcls` (LinkedIn):** Kiểm chứng endpoint `GET /rest/organizationAcls` chấp nhận `rw_organization_admin` hay chỉ yêu cầu `r_organization_admin` khi lấy danh sách Organization Pages mà user làm Admin.
    2. **Danh sách field của Page node trên Graph API v26.0 (Facebook):** Gọi thử `GET /{page-id}?fields=followers_count,fan_count` để xác nhận trường nào trả về dữ liệu chuẩn và trường nào là legacy/deprecated.
    3. **Enum `COMPANY_FOLLOWED_BY_MEMBER` của `networkSizes` (LinkedIn):** Kiểm tra tham số `edgeType=COMPANY_FOLLOWED_BY_MEMBER` trên phiên bản LinkedIn API hiện hành để đảm bảo không bị lỗi 400 Bad Request.
* **Gợi ý Visual:** Timeline lộ trình thực hiện theo tuần của Milestone 1.
