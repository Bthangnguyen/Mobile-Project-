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

Trên Windows PowerShell dùng `./gradlew.bat` hoặc `.\gradlew.bat`.
Chạy cả `assembleDebug` và `testDebugUnitTest` trước khi báo xong việc. Nếu môi trường không cho phép chạy,
phải báo rõ **chưa xác minh** và lý do; không tự nhận test đã PASS.

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

## AI-dev skills — Hướng dẫn sử dụng (Issue #2)

Nhóm cung cấp **ba skill chuyên biệt** để AI làm việc nhất quán với quy tắc trong file này.
Các skill là **hướng dẫn thực hiện công việc**, không thay thế Acceptance Criteria của Issue,
quyết định kỹ thuật của nhóm hay việc review/Approve của con người.

### Vị trí và chức năng

Thư mục chuẩn của nhóm:

```text
.claude/skills/
├── new-feature/
│   └── SKILL.md
├── write-test/
│   └── SKILL.md
└── review-pr/
    └── SKILL.md
```

| Skill | Khi nào sử dụng | Kết quả mong đợi |
|---|---|---|
| `new-feature` | Triển khai một tính năng/màn hình theo Issue đã phân công | Kế hoạch theo Acceptance Criteria, code Kotlin/XML đúng MVVM, unit test phù hợp, báo cáo build/test |
| `write-test` | Bổ sung/kiểm tra test cho ViewModel, Repository, parser, queue, Coroutine/Flow | Test tại `app/src/test/`, Fake/fixture, các trường hợp thành công/lỗi/biên, kết quả chạy thực tế |
| `review-pr` | Kiểm tra một PR hoặc local diff trước khi người thật quyết định review | Báo cáo theo Acceptance Criteria, kiến trúc, bảo mật, test/CI; nhận xét có bằng chứng và mức độ ưu tiên |

### Quy trình chung khi dùng skill

1. **Đọc quy định:** `AGENTS.md` trước, sau đó đọc `CLAUDE.md` (nếu có), tài liệu liên quan trong `docs/` và file `SKILL.md` được chọn.
2. **Xác định đầu vào:** số/URL Issue hoặc PR, Acceptance Criteria, branch và phạm vi file. Nếu thiếu dữ liệu, ghi rõ phần chưa xác minh; không tự bịa yêu cầu.
3. **Làm đúng phạm vi:** tôn trọng chủ sở hữu module và hợp đồng `core/`, không sửa các file không liên quan hoặc tự bổ sung tính năng [N] nếu ticket chỉ yêu cầu [B].
4. **Kiểm tra kết quả:** với code mới, viết test phù hợp và thực thi `assembleDebug`, `testDebugUnitTest` khi môi trường cho phép; xem thêm `lintDebug`. Đối với `review-pr`, phân biệt kết quả CI với kiểm thử đã trực tiếp thực hiện.
5. **Báo cáo có bằng chứng:** nêu các file đã đọc/sửa, Acceptance Criteria đã đáp ứng, lệnh đã chạy, PASS/FAIL/**Chưa xác minh**, vấn đề và bước tiếp theo. Không tuyên bố đã thử skill, build hay test thành công nếu chưa làm thật.

### Cách gọi trong công cụ AI

**Claude Code** (trong ô nhập của Claude Code, **không phải PowerShell**):

```text
/new-feature #32
/write-test kiểm tra ViewModel theo Issue #32
/review-pr 1
```

Các ví dụ trên chỉ hoạt động nếu Claude Code đã nhận diện skill ở `.claude/skills/`.
Nếu không thấy skill, kiểm tra đường dẫn, frontmatter `name`/`description`, khởi tạo phiên Claude Code mới rồi thử lại.

**OpenAI Codex:** Codex thường nhận diện skill dự án ở `.agents/skills/`, không mặc định dùng
`.claude/skills/`. Khi thử nghiệm với Codex, có thể tạo **bản sao** `SKILL.md` cần thử tại
`.agents/skills/<tên-skill>/SKILL.md`, rồi chọn skill bằng cú pháp như `$review-pr`
**nếu giao diện Codex nhận diện được skill**. Bản chính theo Issue #2 vẫn là `.claude/skills/`;
không commit bản sao thử nghiệm nếu nhóm chưa thống nhất hỗ trợ song song hai công cụ.

Nếu công cụ không có chức năng gọi skill, có thể yêu cầu nó đọc file `SKILL.md` và làm theo,
nhưng trong báo cáo phải nói rõ đây là **thử bằng cách đọc file trực tiếp**, không phải kiểm thử
cơ chế tự động nhận diện skill.

### Quy định riêng theo skill

- **`new-feature`:** dùng Kotlin + XML/ViewBinding, MVVM, Hilt, Coroutines/Flow; giao tiếp qua interface `core/`, đa ngôn ngữ VI/EN, unit test logic mới; không tự chuyển sang Compose. Không thay đổi contract khi chưa có Issue `contract` được nhóm thông qua.
- **`write-test`:** ưu tiên JUnit, `kotlinx-coroutines-test`, Fake có thể điều khiển, kiểm tra coroutine/Flow một cách xác định. Không gọi API/LLM thật, không sử dụng key/token thật, không sửa test chỉ để làm CI xanh.
- **`review-pr`:** **chỉ đọc** và tạo báo cáo review. Kiểm tra Acceptance Criteria, hard-coded UI strings, thay đổi `core/`, secrets, CI và test. Phân loại `Blocker` / `Important` / `Suggestion`; không tự sửa code, comment, Approve, Request changes hoặc merge trên GitHub.

### Kiểm thử skill và bằng chứng trong PR

Khi thêm hoặc sửa skill, người thực hiện phải **thử ít nhất một skill** theo Acceptance Criteria của Issue #2,
và ghi vào PR:

- Skill, công cụ/phiên bản và cách gọi thực tế.
- Tình huống thử nghiệm (Issue/PR hoặc thay đổi mẫu); phạm vi dữ liệu AI đã đọc.
- Kết quả thực tế và ví dụ đầu ra (không chứa API key/token hay dữ liệu nhạy cảm).
- Bằng chứng: trích đoạn log đã lọc bí mật, ảnh chụp hoặc báo cáo; nêu rõ hạn chế và những gì chưa xác minh.

Không đưa nội dung review tự động lên GitHub như một lượt **Approve** của thành viên. Theo quy định nhóm,
**hai thành viên khác** phải tự xem xét và Approve PR; AI chỉ là công cụ hỗ trợ.

## Quy trình Git (xem chi tiết [docs/git-workflow.md](docs/git-workflow.md))
- Branch: `feature/<số-issue>-<mô-tả-ngắn>`, `fix/...`, `chore/...`, `docs/...` — tạo từ `main` mới nhất.
- Commit: `<type>(#<issue>): <mô tả>` — type ∈ feat, fix, docs, refactor, test, chore.
- Không push thẳng `main`. Mỗi PR một ticket, cần **2 approve** mới merge.
- AI agent không tự merge, không force push, không sửa lịch sử branch đã push.
