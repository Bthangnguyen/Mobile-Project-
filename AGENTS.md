# AGENTS.md — Hướng dẫn cho AI coding agent

File này dành cho mọi AI agent (Claude Code, Copilot, Cursor, Codex…) làm việc trong repo.
Đọc hết trước khi sửa code. Người trong nhóm cũng nên đọc.

## Dự án
App Android **Social Agent**: AI agent viết bài, đăng bài, trả lời comment và theo dõi tăng trưởng
trên nhiều mạng xã hội (Facebook Page, LinkedIn). Người dùng tự nhập API key LLM (BYOK).
Yêu cầu đầy đủ: [docs/requirements.md](docs/requirements.md). Kiến trúc: [docs/architecture.md](docs/architecture.md).

## Tech stack
- Kotlin, Android View + **XML layout** + ViewBinding (KHÔNG dùng Jetpack Compose)
- MVVM theo Android app architecture: Fragment → ViewModel → Repository → DataSource/Client
- Hilt (DI), Coroutines + Flow, Navigation Component, Material 3
- Room (offline), WorkManager (hàng đợi đăng bài, đồng bộ nền), OkHttp, kotlinx.serialization
- Version thư viện chỉ khai báo trong `gradle/libs.versions.toml`

## Lệnh hay dùng
```bash
./gradlew assembleDebug          # build APK
./gradlew testDebugUnitTest      # chạy unit test
./gradlew lintDebug              # lint
```
Chạy cả `assembleDebug` và `testDebugUnitTest` trước khi báo xong việc.

## Cấu trúc thư mục (`app/src/main/java/com/lttbdd/socialagent/`)
| Thư mục | Nội dung | Chủ sở hữu |
|---|---|---|
| `core/model/` | Data model dùng chung | Cả nhóm (A giữ cửa) |
| `core/social/` | Interface `SocialChannelClient` | B |
| `core/ai/` | Interface `LlmClient`, `ContentAgent` | C |
| `core/ui/` | Component UI dùng chung | D |
| `data/<mảng>/` | Implementation thật (FacebookClient, OpenAiClient…) | Chủ mảng |
| `data/fake/` | Bản giả để dựng UI/test khi chưa có bản thật | Chủ interface tương ứng |
| `di/` | Hilt module | Chủ mảng sửa hàm `@Provides` của mình |
| `feature/<tên>/` | Fragment + ViewModel của từng tính năng | Chủ tính năng |

## QUY TẮC BẮT BUỘC
1. **Không đổi chữ ký** (tên, tham số, kiểu trả về) của class/interface/hàm trong `core/`.
   Cần thêm thì thêm hàm mới hoặc tham số có giá trị mặc định; bỏ hàm cũ thì dùng `@Deprecated`.
   Nếu việc được giao thật sự cần đổi `core/`, DỪNG LẠI và báo người dùng để mở issue nhãn `contract`.
2. **Chỉ sửa file thuộc phạm vi ticket đang làm.** Không "tiện tay" refactor file của mảng khác.
3. Feature gọi sang mảng khác **qua interface trong `core/`**, không import trực tiếp class trong `data/` của mảng khác.
4. **Chuỗi hiển thị** phải đặt trong `res/values/strings.xml` (EN) **và** `res/values-vi/strings.xml` (VI). Không hard-code chữ trong layout/code.
5. **Không bao giờ** log, commit hay gửi API key/token lên bất kỳ server nào ngoài nhà cung cấp LLM/mạng xã hội. Key lưu bằng Android Keystore.
6. Agent **không được tự đăng bài** khi người dùng chưa duyệt (yêu cầu F4).
7. Thêm thư viện: thêm vào `gradle/libs.versions.toml`, ghi lý do trong mô tả PR.
8. Logic mới (ViewModel, repository, hàng đợi, parser) phải có unit test trong `app/src/test/`.

## Quy ước code
- Theo Kotlin official code style; 4 space; trailing comma khi xuống dòng.
- ViewModel expose `StateFlow<UiState>`; Fragment collect bằng `repeatOnLifecycle(STARTED)`.
- ViewBinding trong Fragment: `_binding` nullable, gán `null` ở `onDestroyView`.
- Hàm gọi mạng/DB là `suspend` hoặc trả `Flow`; không chặn main thread.
- Comment và KDoc viết tiếng Việt có dấu.

## Quy trình Git (xem chi tiết [docs/git-workflow.md](docs/git-workflow.md))
- Branch: `feature/<số-issue>-<mô-tả-ngắn>`, `fix/...`, `chore/...`, `docs/...` — tạo từ `main` mới nhất.
- Commit: `<type>(#<issue>): <mô tả>` — type ∈ feat, fix, docs, refactor, test, chore.
- Không push thẳng `main`. Mỗi PR một ticket, cần **2 approve** mới merge.
- AI agent không tự merge, không force push, không sửa lịch sử branch đã push.
