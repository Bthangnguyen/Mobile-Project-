# Quy trình làm việc với Git & GitHub

## Vòng đời một ticket

```
Ticket (Todo) → branch → commit… → push → PR (In Review) → 2 approve → Merge (Done)
```

1. **Ticket** trên GitHub Project phải đủ: Title, Description, Acceptance criteria, Solution,
   Estimate (giờ), Assignee, Milestone. Bắt đầu làm thì kéo sang **In Progress**.
   **Chưa có Acceptance criteria thì chưa mở PR.** Reviewer dựa vào AC để biết PR đã làm đủ chưa;
   ticket trống khiến mỗi vòng review lại phát sinh yêu cầu mới.
2. **Tạo branch** từ `main` mới nhất:
   ```bash
   git checkout main
   git pull
   git checkout -b feature/12-oauth-facebook
   ```
3. **Commit nhỏ, thường xuyên**:
   ```bash
   git commit -m "feat(#12): add Facebook login screen"
   ```
   | type | Dùng khi |
   |---|---|
   | `feat` | Thêm tính năng |
   | `fix` | Sửa lỗi |
   | `docs` | Tài liệu |
   | `refactor` | Sửa cấu trúc code, không đổi hành vi |
   | `test` | Thêm/sửa test |
   | `chore` | Cấu hình, build, việc vặt |
4. **Cập nhật `main` vào branch** trước khi mở PR, xử lý conflict nếu có:
   ```bash
   git pull origin main
   ```
5. **Push và mở PR** vào `main`:
   ```bash
   git push -u origin feature/12-oauth-facebook
   ```
   Điền đủ PR template, ghi `Closes #12`, kéo ticket sang **In Review**.
   2 reviewer được **tự động gắn** theo vòng xoay (xem bên dưới).
   Trước khi nhờ review, tác giả **tự review bằng AI** (xem [Tự review bằng AI](#tự-review-bằng-ai)).
6. **Review**: đọc code, chạy thử, để comment cụ thể, rồi chọn **Approve** hoặc **Request changes**.
   Người làm sửa bằng commit mới trên cùng branch, sau đó bấm **Re-request review** (biểu tượng ↻ cạnh tên reviewer).
7. **Merge** khi đủ **2 approve** và CI xanh, dùng kiểu **Create a merge commit**. Thường tác giả tự merge PR của mình;
   thành viên khác được merge hộ theo điều kiện ở mục [Merge hộ](#merge-hộ). Branch được GitHub tự xoá sau khi merge.
   Nên bật **auto-merge** ngay khi mở PR để GitHub tự merge lúc đủ điều kiện (xem [Auto-merge](#auto-merge)).

## Quy tắc

- Không push thẳng vào `main` (branch protection chặn).
- Không merge PR (của mình hay của người khác) khi chưa đủ 2 approve.
- AI agent không tự merge PR, kể cả khi đủ điều kiện merge hộ, và không tự bật auto-merge (xem `AGENTS.md`).
- Mỗi PR một ticket, cố gắng dưới ~400 dòng thay đổi.
- Mỗi tuần mỗi người phải có PR của mình **và** review PR của người khác — điểm cá nhân tính theo đó.

### Tự review bằng AI

Trước khi bấm nhờ review (lúc mở PR, và mỗi lần sửa xong một vòng lớn), tác giả chạy skill `review-pr`
trên chính PR của mình trong Claude Code:

```text
/review-pr <số PR>
```

- Sửa hết các mục `Blocker` / `[bắt buộc]` mà AI tìm ra và **có bằng chứng** (vị trí, lý do), sau đó mới nhờ người review.
- Mục AI báo nhưng tác giả thấy không đúng thì ghi ngắn lý do trong mô tả PR, không cần sửa.
- Ghi trong mô tả PR: đã chạy `review-pr` lúc nào, đã sửa gì.
- Tự review bằng AI **không thay** review của người: PR vẫn cần 2 approve. AI cũng không được Approve,
  Request changes hay merge thay thành viên (xem `AGENTS.md`).

Mục đích là bắt các lỗi dễ thấy (thiếu chuỗi VI/EN, thiếu test, merge hỏng file, đụng `core/`) trước khi
người review tốn một vòng cho chúng.

## Thời hạn review và merge

PR chỉ merge được khi đủ approve **và** không còn Request changes. Comment không gỡ chặn được PR,
nên mỗi bước dưới đây đều có hạn:

| Việc | Ai làm | Hạn |
|---|---|---|
| Mở PR cho việc của tuần | Tác giả | **Thứ Năm** (để còn thứ Sáu, thứ Bảy cho review và sửa) |
| Review lần đầu, chọn **Approve** hoặc **Request changes** (không chỉ Comment) | Reviewer được gắn | **24 giờ** sau khi được gắn |
| Sửa theo review rồi bấm **Re-request review** | Tác giả | Càng sớm càng tốt |
| Xem lại và Approve (hoặc nêu rõ còn thiếu gì) | Người đã Request changes | **24 giờ** sau khi được re-request |
| Merge | Tác giả, hoặc thành viên khác theo mục [Merge hộ](#merge-hộ) | **24 giờ** sau khi đủ 2 approve và CI xanh |

Quá hạn thì nhắc trực tiếp trong nhóm chat. Nếu vẫn không có phản hồi sau **48 giờ** kể từ lúc được nhắc:

- **Đã Request changes, tác giả đã sửa và re-request, nhưng người đó không xem lại:** chủ repo **Dismiss review**
  đang chặn, ghi rõ lý do trong PR (đã sửa những điểm nào, đã nhắc lúc nào).
- **Chưa từng review** (không có review nào để dismiss): tác giả gỡ người đó khỏi danh sách Reviewers
  và gắn thành viên còn lại review thay.

Cả hai trường hợp PR vẫn phải đủ 2 approve như bình thường.

### Khi nào được Request changes

**Request changes** chặn merge, nên chỉ dùng cho:

- Lỗi thật (sai logic, crash, mất dữ liệu, đăng trùng…) có bằng chứng: vị trí và cách tái hiện hoặc lý do.
- Chưa đạt **Acceptance criteria** của ticket.
- Vi phạm quy tắc bắt buộc trong `AGENTS.md` (đổi `core/`, chuỗi hard-code, lộ key/token, thiếu test cho logic mới…).
- Build hoặc test lỗi.

Không dùng Request changes cho:

- Yêu cầu **ngoài AC** của ticket: ghi `[gợi ý]`, hoặc mở ticket mới và dẫn link trong review.
- Sở thích cách viết code khi code vẫn đúng quy ước: ghi `[gợi ý]`.

**Vòng review đầu phải nêu hết** các điểm `[bắt buộc]` mà reviewer thấy. Ở các vòng sau, chỉ được thêm
điểm `[bắt buộc]` mới khi nó nằm trong phần code mới sửa, hoặc là lỗi nghiêm trọng mà vòng trước bỏ sót
(ghi rõ "bỏ sót ở vòng trước"). Điểm nào đã sửa đúng thì reviewer Approve, không giữ PR lại vì ý tưởng mới.

### Chủ động review

Không cần chờ hết 48 giờ: thành viên bất kỳ được **chủ động review** PR không được gắn cho mình, và approve đó
được tính vào 2 approve. Điều kiện:

- Người review thay phải đọc code và chạy thử như reviewer chính thức (xem [Viết review tốt](#viết-review-tốt)),
  không approve chỉ để đủ số.
- Reviewer được gắn vẫn có trách nhiệm review: nếu PR đã đủ approve trước khi họ kịp xem thì vẫn nên đọc và
  comment, vì điểm cá nhân tính cả phần review người khác.
- Tác giả không tự approve PR của mình (GitHub cũng không cho phép).

### Merge hộ

Thành viên bất kỳ được merge PR của người khác khi **đủ tất cả** các điều kiện sau:

1. Đủ **2 approve**, CI xanh trên `main` mới nhất (branch không bị "out-of-date"), **không còn Request changes** nào đang chặn.
2. PR **không** có nhãn `do-not-merge`, không ở trạng thái Draft, và mô tả PR không ghi phụ thuộc vào PR khác
   chưa merge (ví dụ "merge sau #98").
3. **Báo tác giả trong nhóm chat trước khi merge**, và báo lại sau khi merge xong.
4. Dùng đúng kiểu **Create a merge commit**.

Tác giả muốn tự merge hoặc còn định push thêm thì gắn nhãn `do-not-merge` (gỡ nhãn khi sẵn sàng).
Nếu merge hộ làm `main` lỗi, tác giả vẫn là người sửa, người merge hộ hỗ trợ.

### Auto-merge

Repo bật **Allow auto-merge** và yêu cầu branch **phải cập nhật với `main`** trước khi merge
(Require branches to be up to date):

- Tác giả bấm **Enable auto-merge** → chọn **Create a merge commit** ngay khi mở PR. GitHub sẽ tự merge khi
  đủ 2 approve, CI xanh và không còn Request changes, không cần chờ tác giả online. Bật auto-merge tính là
  tác giả tự merge.
- Còn định push thêm thì **chưa bật** auto-merge (hoặc bấm Disable), giống ý nghĩa nhãn `do-not-merge`.
- Khi `main` có commit mới, PR hiện **"This branch is out-of-date"**. Bấm **Update branch** (hoặc
  `git pull origin main` rồi push) để CI chạy lại trên `main` mới nhất. Auto-merge **không** tự cập nhật
  branch, nên tác giả hoặc người merge hộ phải bấm nút này.
- Lý do bắt buộc cập nhật: CI của PR có thể xanh lúc mở nhưng hỏng khi gộp với `main` mới, dù không có
  conflict (ví dụ #99 thêm hàm vào `DraftDao` sau khi #95 đã merge một test kế thừa `DraftDao`).
- AI agent không tự bật auto-merge; chỉ thành viên bật cho PR của mình.

## Reviewer xoay vòng

Workflow [`assign-reviewers.yml`](../.github/workflows/assign-reviewers.yml) đặt **đúng 2 reviewer** theo bảng
khi mở PR, mở lại PR hoặc chuyển PR draft sang "Ready for review", và gán tác giả làm assignee. Lúc đó người được
gắn thừa (ví dụ chọn cả nhóm ở ô Reviewers, hoặc AI agent chạy `gh pr create --reviewer …`) bị gỡ, người còn thiếu
được thêm. Vì vậy khi mở PR cứ để trống ô Reviewers.

| Tác giả | Reviewer |
|---|---|
| Bthangnguyen (A) | dhminh678, sangcotdo2407 |
| dhminh678 (B) | sangcotdo2407, quaanluuw0210 |
| sangcotdo2407 (C) | quaanluuw0210, Bthangnguyen |
| quaanluuw0210 (D) | Bthangnguyen, dhminh678 |

Mỗi người review đúng 2 người khác. Reviewer bận thì có thể nhờ người còn lại thay, miễn đủ 2 approve:
gắn thêm người đó **sau khi** PR đã mở, workflow không gỡ (nó chỉ chạy ở các sự kiện trên).
Người không được gắn cũng có thể chủ động review (xem [Chủ động review](#chủ-động-review)).

## Viết review tốt

- Nêu **vấn đề + lý do + gợi ý**: "Hàm này gọi API trên main thread → app sẽ đơ, nên chuyển sang `viewModelScope.launch`."
- Phân biệt mức độ: `[bắt buộc]` phải sửa mới approve, `[gợi ý]` có thể để sau, `[hỏi]` cần giải thích.
- Kiểm tra: đúng acceptance criteria chưa, có test chưa, có chuỗi hard-code không, có đụng `core/` không.
- Chỉ approve khi đã đọc và chạy thử.

## Xử lý conflict

Conflict xảy ra khi hai người sửa **cùng dòng** của cùng file. Luôn xử lý **trên branch của mình**:

```bash
git checkout feature/12-oauth-facebook
git pull origin main
# mở file có <<<<<<< ======= >>>>>>>, chọn bản đúng, xoá các dấu đánh dấu
git add .
git commit -m "fix(#12): resolve conflict with main"
git push
```

## Báo cáo tuần (Slack, tối thứ Bảy)

```
TUẦN <ID>

Current tasks:
* Task 1 <assignee>

Next plan:
* Task 1 <assignee>

Blockers:
* Blocker 1
```
