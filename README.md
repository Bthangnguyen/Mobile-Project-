# Social Agent

Ứng dụng Android dùng **AI Agent để xây dựng kênh mạng xã hội**: nhập một topic, agent viết bài và sinh ảnh
riêng cho từng kênh (Facebook Page, LinkedIn). Người dùng duyệt rồi đăng một lần lên nhiều kênh. App còn
đọc và trả lời comment, và theo dõi tăng trưởng. Người dùng tự dùng API key LLM của mình (BYOK); key chỉ
lưu trên máy.

Đồ án cuối kỳ môn Lập trình thiết bị di động (LTTBDD).

## Tính năng

| | Tính năng | Trạng thái |
|---|---|---|
| F1 | Kết nối nhiều mạng xã hội (OAuth) | ⬜ |
| F2 | AI Agent viết bài + sinh ảnh, chạy song song mỗi kênh | ⬜ |
| F3 | Nhập topic + brief | ⬜ |
| F4 | Duyệt và chỉnh sửa bài | ⬜ |
| F5 | Đăng nhiều kênh, hàng đợi offline, chống đăng trùng | ⬜ |
| F6 | Hộp thư comment, AI phân loại và soạn trả lời | ⬜ |
| F7 | Số liệu và dashboard tăng trưởng | ⬜ |
| F8 | Nhập API key LLM (BYOK), theo dõi chi phí | ⬜ |
| F9 | Giao diện VI/EN, dùng được khi offline | ⬜ |

Chi tiết yêu cầu: [docs/requirements.md](docs/requirements.md)

## Thành viên

| Thành viên | GitHub | Mảng phụ trách |
|---|---|---|
| _(A)_ | @… | Core, DB, CI, F5 Đăng bài, F9 Cài đặt |
| _(B)_ | @… | F1 Kết nối kênh, client Facebook/LinkedIn, F6 Comment, thu thập số liệu |
| _(C)_ | @… | F8 BYOK, F2 AI Agent |
| _(D)_ | @… | UI chung, F3 Nhập topic, F4 Duyệt bài, F7 Dashboard |

## Tech stack

- **Ngôn ngữ / UI:** Kotlin, XML layout + ViewBinding, Material 3
- **Kiến trúc:** MVVM theo [Android app architecture](https://developer.android.com/topic/architecture)
- **Thư viện:** Hilt, Coroutines/Flow, Navigation Component, Room, WorkManager, OkHttp, kotlinx.serialization
- **Build:** Gradle 9.4.1, AGP 9.2.1, Kotlin 2.3, minSdk 26, targetSdk 36

## Cài đặt và chạy

**Yêu cầu:** Android Studio **Panda 4 (2025.3.4)** trở lên, Android SDK 36.

1. Clone repo:
   ```bash
   git clone <repo-url>
   ```
2. Mở thư mục bằng Android Studio và chờ Gradle sync. Android Studio tự tạo `local.properties` trỏ tới SDK.
3. Chọn máy ảo hoặc điện thoại thật rồi bấm **Run ▶**.

Chạy bằng dòng lệnh (dùng JDK đi kèm Android Studio):
```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Hiện app chạy hoàn toàn bằng dữ liệu giả (`data/fake/`), chưa cần API key hay tài khoản mạng xã hội.

## Cấu trúc dự án

```
app/src/main/java/com/lttbdd/socialagent/
├── core/       hợp đồng chung: model + interface (không tự ý sửa — xem docs/architecture.md)
├── data/       implementation thật và bản giả (fake)
├── di/         Hilt module
└── feature/    màn hình + ViewModel theo từng tính năng
```

Chi tiết: [docs/architecture.md](docs/architecture.md)

## Quy trình làm việc

- Quản lý task trên **GitHub Project**: mỗi ticket có Assignee, Estimate, Milestone.
- Mỗi ticket một branch → PR → **2 thành viên approve** → merge.
- Chi tiết: [docs/git-workflow.md](docs/git-workflow.md)

## Làm việc với AI

Repo có sẵn hướng dẫn cho AI coding agent ở [AGENTS.md](AGENTS.md) (Claude Code đọc qua [CLAUDE.md](CLAUDE.md)).
Gồm kiến trúc, quy ước code, các quy tắc bắt buộc (không sửa hợp đồng `core/`, không hard-code chuỗi,
không làm lộ API key) và quy trình Git.
