# KINTO Tech Blog – EncryptedSharedPreferences Migration Analysis

> **Nguồn tham khảo:** KINTO Tech Blog  
> **URL:** [https://blog.kinto-technologies.com/posts/2025-06-16-encrypted-shared-preferences-migration/](https://blog.kinto-technologies.com/posts/2025-06-16-encrypted-shared-preferences-migration/)  
> **Mục đích:** Báo cáo phân tích kỹ thuật và đo lường hiệu năng (Benchmark) việc di chuyển từ `EncryptedSharedPreferences` sang **DataStore** kết hợp **Android Keystore** hoặc **Google Tink**.

---

## 1. Lý do Di chuyển khỏi `EncryptedSharedPreferences`

- **Tài liệu chính thức Deprecated:** `EncryptedSharedPreferences` ngừng được bảo trì từ `v1.1.0-alpha07`.
- **Chuẩn hóa Android:** Chuyển sang **Jetpack DataStore** để tận dụng cơ chế Asynchronous Flow, tránh chặn Main Thread (UI Thread) và tương thích hoàn toàn với Kotlin Coroutines.
- **Phân tách trách nhiệm (Separation of Concerns):** Tách bạch giữa tầng lưu trữ dữ liệu (DataStore) và tầng thực thi mã hóa (Encryption Layer).

---

## 2. So sánh Kỹ thuật: Android Keystore vs. Google Tink

| Tiêu chí | Android Keystore Trực tiếp | Google Tink (by Google) |
|---|---|---|
| **Độ phức tạp triển khai** | **Phức tạp hơn:** Lập trình viên phải tự quản lý `Cipher`, xử lý tách/gộp mảng byte IV (Initialization Vector) 12 bytes và Tag GCM. Không có sample chuẩn từ Google. | **Đơn giản:** Đóng gói sẵn qua API cấp cao (`Aead`), tự động quản lý IV và mã hóa/giải mã mảng byte. |
| **Quản lý Khóa (Key Management)** | Sinh và thực thi mã hóa/giải mã trực tiếp **bên trong phần cứng** (TEE hoặc StrongBox). | Dùng Android Keystore để bảo vệ **Master Key**, sau đó thực thi mã hóa dữ liệu qua các hàm phần mềm tối ưu. |
| **Phụ thuộc Phần cứng & Tương thích** | Phụ thuộc vào phần cứng từng dòng máy (TEE / StrongBox). Hiệu năng khác biệt lớn giữa các thiết bị. | Đảm bảo tính nhất quán hiệu năng trên 100% dòng máy Android. |
| **Số lượng Allocations** | Tốn nhiều tài nguyên bộ nhớ (Garbage Collection allocations) do gọi IPC sang phần cứng hệ thống. | Tối ưu hóa bộ nhớ, số lượng allocations cực ít. |

---

## 3. Kết quả Đo lường Hiệu năng (Microbenchmark Results)

- **Môi trường thử nghiệm:** Sử dụng thư viện `androidx.benchmark:benchmark-junit4` (Microbenchmark) trên thiết bị **Google Pixel Tablet**, thực hiện ngoài UI Thread.
- **Payload thử nghiệm:** Thực hiện mã hóa lặp đi lặp lại mảng dữ liệu 10KB (`ByteArray(1024 * 10)`) bằng thuật toán `AES-GCM`.

### Bảng Kết quả Chi tiết:

| Giải pháp Mã hóa | Thời gian Mã hóa Trung bình (ms) | Số lượt Cấp phát Bộ nhớ (Memory Allocations) | So sánh Tốc độ tương đối |
|---|:---:|:---:|:---:|
| **Android Keystore (StrongBox SE)** | **209.00 ms** | 4,646 | Chậm nhất (Do giao tiếp chip SE riêng) |
| **Android Keystore (TEE)** | **7.07 ms** | 4,786 | Nhanh hơn StrongBox ~30 lần |
| **Google Tink Library** | **0.573 ms** | **38** | **Nhanh nhất (Nhanh hơn StrongBox ~360 lần, TEE ~12 lần)** |

---

## 4. Kết luận & Đề xuất cho Ứng dụng Android

1. **Về mặt Hiệu năng:** Google Tink cho tốc độ phản hồi mã hóa vượt trội (**0.573 ms**) và áp lực rác bộ nhớ (GC) cực thấp (chỉ **38 allocations** so với 4,600+ của Keystore).
2. **Về mặt Bảo mật:** Cả 2 phương án đều đảm bảo an toàn tuyệt đối do Master Key đều được niêm phong trong Android Keystore phần cứng.
3. **Đề xuất Kiến trúc cho Dự án:**
   - Đối với dữ liệu nhỏ như Access Token / Refresh Token (chuỗi ký tự < 2KB): Sử dụng **DataStore Preferences** kết hợp mã hóa **Android Keystore AES-256-GCM trực tiếp** là phương án tối ưu nhất để tránh phải kéo thêm dependency nặng của thư viện Tink.
   - Đối với dữ liệu lớn hoặc ghi liên tục: Nên cân nhắc **Google Tink**.
