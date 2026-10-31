# Quy trình làm việc với Git & GitHub

## Vòng đời một ticket

```
Ticket (Todo) → branch → commit… → push → PR (In Review) → 2 approve → Merge (Done)
```

1. **Ticket** trên GitHub Project phải đủ: Title, Description, Acceptance criteria, Solution,
   Estimate (giờ), Assignee, Milestone. Bắt đầu làm thì kéo sang **In Progress**.
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
   Điền đủ PR template, ghi `Closes #12`, mời **2 thành viên khác** review, kéo ticket sang **In Review**.
6. **Review**: đọc code, chạy thử, để comment cụ thể. Người làm sửa bằng commit mới trên cùng branch.
7. **Merge** khi đủ **2 approve** và CI xanh, dùng kiểu **Create a merge commit**, rồi xoá branch.

## Quy tắc

- Không push thẳng vào `main` (branch protection chặn).
- Không tự merge PR khi chưa đủ 2 approve.
- Mỗi PR một ticket, cố gắng dưới ~400 dòng thay đổi.
- Review PR của người khác trong vòng 24 giờ.
- Mỗi tuần mỗi người phải có PR của mình **và** review PR của người khác — điểm cá nhân tính theo đó.

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
