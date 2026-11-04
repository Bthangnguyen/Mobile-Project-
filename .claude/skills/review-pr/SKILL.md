---
name: review-pr
description: Review Android Kotlin/XML pull requests against linked issue acceptance criteria, AGENTS.md, architecture, tests, security, and Git workflow. Use when asked to review a PR number, URL, or local diff.
---

# Review Pull Request — Social Agent

## Mục tiêu

Hỗ trợ thành viên nhóm kiểm tra Pull Request (PR) theo bằng chứng cụ thể, trước khi người review quyết định **Approve**, **Comment** hoặc **Request changes**. Skill chỉ tạo **báo cáo đề xuất**; không thay thế review của con người.

## Phạm vi và giới hạn

- **Chỉ đọc**: không sửa file, không tự commit, push, force-push, rebase, merge, đóng issue, gửi comment, yêu cầu review hay Approve PR.
- Không chạy lệnh có tác dụng sửa trạng thái repo (bao gồm `gh pr checkout`, `git switch`, `git merge`) nếu người dùng không yêu cầu riêng. Ưu tiên `gh pr view`, `gh pr diff`, `gh pr checks`, `git diff` và đọc file.
- Không gửi mã nguồn, API key, token, dữ liệu cá nhân hoặc diff lên dịch vụ bên ngoài để phân tích. Không in nguyên giá trị bí mật trong báo cáo.
- Chỉ nhận định những gì có thể kiểm chứng. Phân biệt **Đã xác minh**, **Chưa xác minh**, **Đề xuất**. Không tự nhận đã chạy build/test nếu chưa chạy thực tế.
- Không tự ý mở rộng phạm vi review sang việc refactor các module khác.

## Đầu vào

Nhận một trong các dạng: số PR (`#12` hoặc `12`), URL PR hoặc thay đổi cục bộ (`local diff`). Nếu chưa rõ mục tiêu review, hỏi người dùng cung cấp số/URL PR hoặc xác nhận review thay đổi cục bộ.

Nếu `gh` (GitHub CLI) chưa được cài/đăng nhập hoặc không truy cập được GitHub, nói rõ giới hạn và đề nghị cung cấp PR diff, mô tả Issue, trạng thái CI. Không đoán nội dung không đọc được.

## Bước 1 — Đọc quy định dự án

Đọc theo thứ tự trước khi đánh giá:

1. `AGENTS.md`: quy tắc bắt buộc và tech stack.
2. `CLAUDE.md` (nếu có): hướng dẫn công cụ bổ sung; không được trái `AGENTS.md`.
3. `docs/requirements.md`: yêu cầu F1–F9, phân biệt [B] và [N].
4. `docs/architecture.md`: kiến trúc, ranh giới các module.
5. `docs/git-workflow.md`: quy ước branch, commit, PR và review.
6. Issue được liên kết với PR: Description, Acceptance Criteria, Solution và phạm vi ticket (nếu được cung cấp).

Nếu tài liệu thiếu hoặc mâu thuẫn, báo rõ; không tự suy diễn thay quyết định nhóm.

## Bước 2 — Thu thập bằng chứng

Với PR trên GitHub, ưu tiên các lệnh **chỉ đọc** (khi `gh` đã có quyền truy cập):

```bash
gh pr view 12 --json number,title,body,author,baseRefName,headRefName,files,commits,reviewDecision
gh pr diff 12
gh pr checks 12
```

Thay `12` bằng số PR thực tế. Nếu cần xem Issue liên quan, có thể dùng `gh issue view <issue-number>`.

Với thay đổi cục bộ, có thể dùng:

```bash
git status --short
git diff
git diff --cached
```

Xác định: mục đích PR, Issue liên kết, file thêm/sửa/xóa, kiểm tra CI, phạm vi tính năng. Không coi dấu tích xanh CI là bằng chứng mọi chức năng đều đúng.

## Bước 3 — Review yêu cầu trước, chất lượng sau

### 3.1. Đúng phạm vi Issue và Acceptance Criteria

- Mỗi PR phục vụ **một ticket**; các file thay đổi có liên quan trực tiếp không?
- Đối chiếu **từng Acceptance Criterion** với code hoặc bằng chứng test. Ghi `Đạt`, `Chưa đạt` hoặc `Chưa xác minh`, cùng đường dẫn file/dòng liên quan.
- Không nhầm code stub/Fake với tính năng thật đã hoàn thành.
- Kiểm tra yêu cầu bắt buộc [B] liên quan; không yêu cầu triển khai [N] ngoài phạm vi ticket.
- Nếu gặp thay đổi ngoài phạm vi hoặc thay đổi trùng với PR/Issue khác, chỉ rõ và đề nghị người thực hiện giải thích.

### 3.2. Kiến trúc và Kotlin/Android

- Sử dụng **Kotlin + Android View/XML + ViewBinding**, không thêm Jetpack Compose.
- Tôn trọng luồng **Fragment → ViewModel → Repository → DataSource/Client**.
- Hilt dùng để DI; không tự khởi tạo implementation của mảng khác từ feature.
- Các mảng giao tiếp qua interface `core/`, không import trực tiếp `data/` của mảng khác.
- **Không thay chữ ký** class/interface/hàm trong `core/`. Khi cần breaking change, ghi nhận là blocker và yêu cầu issue nhãn `contract` cùng sự thống nhất của nhóm; không tự sửa.
- ViewModel expose `StateFlow<UiState>`; Fragment collect với `repeatOnLifecycle(STARTED)`.
- ViewBinding của Fragment sử dụng `_binding` nullable và giải phóng trong `onDestroyView`.
- Tác vụ mạng/DB dùng `suspend` hoặc `Flow`, không block main thread; kiểm tra xử lý lỗi/hủy coroutine khi có liên quan.
- Tuân thủ Kotlin code style, indent 4 spaces, trailing comma cho khai báo nhiều dòng; comment/KDoc bằng tiếng Việt có dấu.

### 3.3. Resource và thư viện

- Không hard-code chuỗi **hiển thị cho người dùng** trong Kotlin/XML. Phải có `res/values/strings.xml` (EN) và `res/values-vi/strings.xml` (VI).
- Thư viện/phiên bản mới phải khai báo qua `gradle/libs.versions.toml`, và PR phải nêu lý do thêm.
- Xem các thay đổi resource, Navigation và giao diện có tương thích với XML/ViewBinding không.

### 3.4. Bảo mật, dữ liệu và yêu cầu nghiệp vụ

- Không đưa API key, access token, secret vào source, commit, log, ảnh chụp hay tài liệu. Khi phát hiện, chỉ nêu vị trí/loại bí mật, **không trích giá trị**; đề xuất thu hồi/rotate nếu đã lộ.
- Với BYOK, kiểm tra key được lưu qua Android Keystore theo `AGENTS.md`; không tự ý gửi về server ứng dụng.
- Agent **không tự đăng bài nếu người dùng chưa duyệt** (F4).
- Nếu PR liên quan đăng bài: kiểm tra chống đăng trùng, lỗi mạng/rate limit và hàng đợi offline theo Acceptance Criteria.
- Nếu PR liên quan AI/sub-agent: kiểm tra streaming, hủy, cô lập lỗi từng kênh, chạy lại, log token/chi phí theo Acceptance Criteria.
- Chỉ áp dụng các mục nghiệp vụ liên quan trực tiếp đến PR đang review.

### 3.5. Test và CI

- Logic mới (ViewModel, Repository, hàng đợi, parser) phải có unit test trong `app/src/test/`.
- Xem test có kiểm tra happy path, lỗi và trường hợp biên phù hợp không; ưu tiên Fake để không phụ thuộc API thật.
- Kiểm tra trạng thái CI từ GitHub; phân biệt `pass`, `fail`, `pending`, `not available`.
- Có thể **đề xuất** chạy các lệnh bên dưới, nhưng **không tự chạy** nếu người dùng chưa cho phép hoặc môi trường không phù hợp:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Trên Windows PowerShell dùng `./gradlew.bat` hoặc `.\gradlew.bat`. Theo `AGENTS.md`, cần chạy build và unit test trước khi tuyên bố công việc hoàn thành. Chỉ ghi `PASS` nếu đã có kết quả thực tế từ CI hoặc môi trường chạy, và nêu nguồn kết quả.

## Bước 4 — Xác minh và phân loại phát hiện

Dựa trên thực hành evidence-first và kiểm tra edge cases của Claude-Kit:

- Đối chiếu phát hiện với **đoạn diff**, không chỉ suy đoán từ tên file.
- Thử nghĩ đến dữ liệu rỗng/sai, mất mạng, hủy coroutine, trạng thái cạnh tranh, trùng lặp thao tác và lỗi quyền nếu liên quan.
- Với mỗi phát hiện, ghi **file/dòng**, tác động, điều kiện gây lỗi, bằng chứng, cách sửa đề xuất.
- Không báo các vấn đề đã tồn tại từ trước như lỗi của PR này nếu thay đổi không tạo ra hoặc không làm nặng thêm lỗi; có thể ghi là ghi chú ngoài phạm vi.

Mức độ:

- **Blocker**: lỗi bảo mật, phá vỡ contract, vi phạm bắt buộc, build/test bắt buộc thất bại, lỗi chức năng trọng yếu đã xác minh.
- **Important**: Acceptance Criterion chưa đạt hoặc lỗi logic/thiếu test đáng kể cần giải quyết trước merge.
- **Suggestion**: cải thiện không chặn merge (tên biến, tài liệu, Fake mô phỏng tốt hơn, v.v.).

Không dùng `Approve` chỉ vì PR có CI xanh; không dùng `Request changes` chỉ vì có góp ý không bắt buộc.

## Bước 5 — Xuất báo cáo review

Viết **tiếng Việt**, ngắn gọn, theo mẫu:

```markdown
# Review PR #<number>: <title>

## Phạm vi
- Issue: #...
- Branch: <source> -> <target>
- File đã kiểm tra: ...

## Đối chiếu Acceptance Criteria
| Tiêu chí | Trạng thái | Bằng chứng |
|---|---|---|
| ... | Đạt / Chưa đạt / Chưa xác minh | file:line / CI / test |

## Phát hiện
1. [Blocker/Important/Suggestion] Tiêu đề
   - Vị trí: `path/to/file:line`
   - Bằng chứng và tác động: ...
   - Đề xuất: ...

## Kiểm thử
- Build: PASS / FAIL / Chưa xác minh (nguồn: ...)
- Unit test: PASS / FAIL / Chưa xác minh (nguồn: ...)
- Lint: PASS / FAIL / Chưa xác minh (nguồn: ...)
- Manual test: PASS / FAIL / Chưa xác minh (phạm vi: ...)

## Kết luận đề xuất
- Approve / Comment / Request changes
- Lý do và điều kiện còn phải kiểm tra: ...
```

Nếu không có phát hiện, ghi rõ “Không phát hiện vấn đề trong phạm vi đã kiểm tra” thay vì khẳng định code hoàn hảo.

## Quy trình review của con người

- AI chỉ **đề xuất** `Approve`, `Comment` hoặc `Request changes`.
- Reviewer thật đọc báo cáo, xác minh bằng chứng và tự gửi review trên GitHub.
- Theo quy định nhóm: **cần hai người khác Approve mới merge**; AI không tính là một reviewer.
- Không tự động merge hoặc đóng Issue.

## Ví dụ gọi skill

- `/review-pr 1` — kiểm tra PR #1.
- `/review-pr https://github.com/Bthangnguyen/Mobile-Project-/pull/1` — review từ URL PR.
- `/review-pr local diff` — kiểm tra các thay đổi cục bộ đang có.

Khi không truy cập được GitHub CLI hoặc dữ liệu liên quan, phải nói rõ thiếu gì, không tạo báo cáo giả.
