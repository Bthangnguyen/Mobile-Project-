# Kiến trúc

Theo [Guide to app architecture](https://developer.android.com/topic/architecture) của Android.

```
UI layer                          Data layer
┌──────────────────────┐          ┌───────────────────────────────┐
│ Fragment (XML view)  │ observe  │ Repository                    │
│        ▲             │◄─────────│   ├─ Room DAO (offline)       │
│        │ StateFlow   │          │   ├─ SocialChannelClient      │
│ ViewModel ───────────┼─────────►│   └─ LlmClient / ContentAgent │
└──────────────────────┘  gọi hàm └───────────────────────────────┘
```

- **Fragment** chỉ hiển thị và chuyển sự kiện người dùng cho ViewModel.
- **ViewModel** giữ `UiState`, gọi repository/interface, không biết đến Android View.
- **Repository / Client** nói chuyện với mạng, DB, WorkManager.
- **Offline-first:** UI đọc từ Room; mạng chỉ cập nhật Room. Nhờ đó app vẫn xem được dữ liệu khi mất mạng (F9).
  Repository của từng tính năng inject DAO (`DraftDao`, `PostDao`, `CommentDao`, `MetricsDao`) từ `di/DatabaseModule.kt`,
  đọc bằng `Flow` và đổi entity ↔ model qua `core/database/Mappers.kt`. Riêng bài đăng chưa có model trong
  `core/model`, nên repository F5 dùng thẳng `PostEntity` (muốn có model `Post` thì mở issue `contract`). Đổi schema thì tăng `version` trong
  `AppDatabase`, thêm migration và commit file mới trong `app/schemas/`.

## Hợp đồng chung (`core/`)

Các mảng giao tiếp với nhau **chỉ qua interface trong `core/`**:

| Interface | Chủ | Ai gọi |
|---|---|---|
| `core/social/SocialChannelClient` | B | F5 đăng bài (A), F6 comment (B), F7 số liệu (D) |
| `core/ai/LlmClient` | C | ContentAgent (C), F6 phân loại + soạn trả lời (B) |
| `core/ai/ContentAgent` | C | F3 nhập topic, F4 duyệt bài (D) |

Mỗi interface có bản giả trong `data/fake/`, được gắn bằng `di/FakeDataModule.kt`. Nhờ vậy cả nhóm code song song
được: khi bản thật xong, chủ mảng chỉ thay hàm `@Provides` tương ứng, code gọi không phải sửa.

```
D (F3 nhập topic) ──► ContentAgent (C)
D (F4 duyệt bài)  ──► ContentAgent (C)        rewrite / sinh lại ảnh
D (F4 bấm Đăng)   ──► F5 hàng đợi (A) ──► SocialChannelClient (B)
B (F6 comment)    ──► LlmClient (C)           phân loại, soạn trả lời
D (F7 dashboard)  ──► Room snapshot (A) ◄── job lấy số liệu (B)
```

## Quy tắc thay đổi hợp đồng

1. **Thêm** hàm/field mới (có giá trị mặc định): mở PR bình thường, chủ interface phải nằm trong 2 người review.
2. **Đổi hoặc xoá** (breaking change):
   - Mở issue nhãn `contract`, ghi lý do và phương án.
   - Cả 4 thành viên đồng ý trên issue.
   - Người đề xuất sửa **toàn bộ chỗ gọi** trong cùng một PR.
3. Hàm sắp bỏ: đánh dấu `@Deprecated("…", ReplaceWith("…"))` trước, xoá ở milestone sau.
4. `.github/CODEOWNERS` tự yêu cầu review của chủ sở hữu khi có thay đổi trong `core/`.

## Cấu trúc package

```
com.lttbdd.socialagent
├── core/
│   ├── model/        Draft, Comment, PostMetrics, ChannelType, AgentEvent...
│   ├── social/       SocialChannelClient
│   ├── ai/           LlmClient, ContentAgent
│   ├── database/     Room DB + DAO + entity + mapper (A)
│   └── ui/           component dùng chung (D)
├── data/
│   ├── fake/         bản giả của các interface
│   ├── social/       FacebookClient, LinkedInClient (B)
│   └── ai/           OpenAiClient, GeminiClient, ContentAgentImpl (C)
├── di/               Hilt module
└── feature/
    ├── channels/     F1 Kênh của tôi (B)
    ├── agent/        F2 tiến độ + nhật ký agent (C)
    ├── compose/      F3 nhập topic (D)
    ├── review/       F4 duyệt bài (D)
    ├── publishing/   F5 đăng bài + hàng đợi (A)
    ├── comments/     F6 hộp thư comment (B)
    ├── analytics/    F7 dashboard (D)
    ├── apikeys/      F8 BYOK (C)
    └── settings/     F9 cài đặt (A)
```

## Đăng nhập LinkedIn qua BFF (ngoại lệ Quy tắc 5, #88)

Native PKCE của LinkedIn từ chối scope `openid`, nên App Member dùng Standard OAuth qua một BFF nhỏ ở **repository backend riêng** ([social-agent-linkedin-bff](https://github.com/dhminh678/social-agent-linkedin-bff); repository này không chứa mã backend). Facebook không đi qua BFF: app dùng Meta SDK.

```
App ──POST /auth/linkedin/start (state, code_challenge)──► BFF
App ──mở trình duyệt hệ thống──► LinkedIn ──redirect──► BFF /auth/linkedin/callback
BFF ──đổi code lấy token (giữ client_secret)──► LinkedIn
BFF ──302 deep link com.lttbdd.socialagent://oauth/linkedin/callback?exchange_code&state──► App
App ──POST /auth/linkedin/token (exchange_code, code_verifier)──► BFF ──access token + expires_in──► App
```

- Token chỉ nằm trong bộ nhớ BFF tối đa 60 giây và bị xóa ngay lần đổi đầu tiên; BFF không ghi đĩa/DB, không log token, `code`, `state`, `code_verifier` và không chuyển tiếp `refresh_token`.
- App kiểm `state` ở mọi nhánh của deep link và chỉ đổi `exchange_code` kèm `code_verifier` còn trong RAM. Token được lưu bằng `SecureTokenStore` như các kênh khác.
- Không có `/refresh` hay `/logout`: token LinkedIn hết hạn thì người dùng đăng nhập lại.
- Redirect dùng custom scheme (bản demo). Rủi ro phishing của custom scheme là known issue; sẽ chuyển sang Android App Link bằng ticket riêng.
- Chi tiết quyết định và điều kiện: issue #88 và `docs/research-social-api.md` (quyết định 4, Luồng B).

## Điều hướng

Một Activity (`MainActivity`) + Navigation Component, bottom navigation có 5 tab:
Tạo bài · Bài viết · Bình luận · Số liệu · Cài đặt. Các tab hiện dùng `PlaceholderFragment`.
Khi làm tính năng, thay `android:name` trong `res/navigation/nav_graph.xml` bằng Fragment thật (giữ nguyên `android:id`).
