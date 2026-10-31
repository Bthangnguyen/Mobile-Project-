# Mobile AI Agent xây dựng kênh Social Media — Yêu cầu tính năng

Ký hiệu: **\[B\]** Bắt buộc · **\[N\]** Nâng cao

## F1. Tích hợp nhiều mạng xã hội

- \[B\] Kết nối tối thiểu 2 kênh: đăng nhập OAuth (LinkedIn hoặc Facebook Page)  
- \[B\] Màn hình *Kênh của tôi*: trạng thái kết nối, tài khoản/Page đang dùng, các chức năng kênh hỗ trợ (đăng, đọc comment, trả lời, mention, số liệu).  
- \[B\] Đăng nhập OAuth qua trình duyệt hệ thống; tự làm mới token; báo kết nối lại khi token hết hạn.  
- \[B\] Ngắt kết nối và xoá dữ liệu của kênh.


## F2. AI Agent viết bài và sinh ảnh

- \[B\] Orchestrator nhận topic, lập brief chung và gọi song song các sub-agent, mỗi kênh một sub-agent.  
- \[B\] Mỗi sub-agent có luồng viết riêng: Dàn ý → Bản nháp → Tự rà theo quy tắc kênh → Mô tả ảnh → Sinh ảnh → Đóng gói kết quả.  
- \[B\] Mỗi sub-agent có luồng sinh ảnh riêng (kích thước, phong cách theo kênh); có phương án dự phòng dựng ảnh từ template khi không có API sinh ảnh.  
- \[B\] Hiển thị tiến độ từng bước, stream nội dung khi đang viết.  
- \[B\] Huỷ được giữa chừng; một kênh lỗi không ảnh hưởng kênh khác; chạy lại riêng từng kênh.  
- \[B\] Nhật ký agent: xem từng bước, prompt, kết quả, số token, chi phí.

## F3. Nhập topic

- \[B\] Nhập topic bằng văn bản kèm brief: mục tiêu, đối tượng đọc, giọng văn, ngôn ngữ (VI/EN), kênh đích.  
- \[N\] Nhập bằng giọng nói.  
- \[N\] Nhập bằng URL: agent đọc bài gốc và viết bài chia sẻ có trích nguồn.  
- \[N\] Nhập bằng ảnh (slide, whiteboard).  
- \[N\] Share extension: chia sẻ link từ app khác để tạo bài.  
- \[N\] Gợi ý topic từ bài hiệu quả nhất và nguồn xu hướng.

## F4. Duyệt và chỉnh sửa bài

- \[B\] Agent không tự đăng khi chưa có người duyệt.  
- \[B\] Xem trước theo giao diện từng kênh; sửa tay; đếm ký tự theo giới hạn kênh.  
- \[B\] Viết lại từng đoạn (ngắn hơn, trang trọng hơn, đổi hook…); sinh lại ảnh.  
- \[N\] Lịch sử phiên bản bản nháp.  
- \[N\] Sinh 3 phương án câu mở đầu để chọn.  
- \[N\] Tự sinh alt text cho ảnh.  
- \[N\] Cảnh báo trước khi đăng: số liệu chưa có nguồn, thông tin cá nhân, lời lẽ nhạy cảm.

## F5. Đăng mạng xã hội

- \[B\] Đăng một lúc lên nhiều kênh; trạng thái từng kênh: nháp → chờ → đang đăng → đã đăng / lỗi / đã bàn giao.  
- \[B\] Hàng đợi offline: mất mạng vẫn bấm đăng được, tự đăng khi có mạng.  
- \[B\] Không đăng trùng khi bấm hai lần hoặc thử lại.  
- \[B\] Tự thử lại khi lỗi mạng/rate limit; báo rõ lý do khi lỗi quyền hoặc nội dung.  
- \[B\] Lưu link bài đã đăng, mở được bài gốc.

## F6. Đọc comment và trả lời

- \[B\] Hộp thư comment hợp nhất từ mọi kênh; lọc theo kênh, bài, trạng thái.  
- \[B\] Đồng bộ comment (kéo để làm mới \+ đồng bộ nền).  
- \[B\] AI phân loại comment: câu hỏi, khen, góp ý, tiêu cực, spam.  
- \[B\] AI soạn câu trả lời theo nội dung bài và giọng văn; người dùng sửa rồi gửi.  
- \[B\] Chế độ tự trả lời có giới hạn: chỉ nhóm "khen" và "câu hỏi thường gặp"; không tự trả lời comment tiêu cực, có thông tin cá nhân, hoặc agent không chắc chắn.  
- \[N\] Push notification khi có comment mới, mở thẳng đúng comment.  
  - Chọn 1 trong 2 giải pháp: (a). Background app refresh \+ local notification, (b). Sử dụng server side để nghe và bắn notification đến mobile app.

## F7. Theo dõi tăng trưởng

- \[B\] Số liệu từng bài: thích, comment, chia sẻ, lượt hiển thị (nếu có), tỉ lệ tương tác.  
- \[B\] Lưu số liệu định kỳ để vẽ đường tăng trưởng theo thời gian.  
- \[B\] Dashboard: follower theo thời gian, tương tác theo tuần, top bài, so sánh các kênh.  
- \[N\] Phân tích theo chủ đề, định dạng, giờ đăng.  
- \[N\] Báo cáo tuần do AI viết: tăng/giảm gì, vì sao, tuần sau nên làm gì.  
- \[N\] Widget màn hình chính.  
- \[N\] Đặt mục tiêu tăng trưởng và theo dõi tiến độ.

## F8. Nhập API Key LLM (BYOK)

- \[B\] Hỗ trợ ≥2 nhà cung cấp LLM (OpenAI, Anthropic, Gemini, OpenRouter…).  
- \[B\] Kiểm tra key hợp lệ; lưu trong secure storage; không gửi lên server; hiển thị dạng che.  
- \[B\] Chọn model cho từng loại việc: viết bài, phân loại comment, sinh ảnh.  
- \[B\] Theo dõi token và chi phí theo ngày/tháng.  
- \[N\] Trần ngân sách ngày/tháng; chạm trần thì dừng agent.  
- \[N\] Tự chuyển sang nhà cung cấp phụ khi nhà cung cấp chính lỗi.  
- \[N\] Chế độ Demo không cần key.

## F9. Cài đặt chung

- \[B\] Giao diện VI/EN.  
- \[B\] Ứng dụng dùng được khi offline: xem bản nháp, comment và số liệu đã lưu.