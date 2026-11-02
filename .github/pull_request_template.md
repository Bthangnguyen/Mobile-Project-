Closes #

## Thay đổi
<!-- Làm gì, thay đổi những file/màn hình nào -->

## Cách kiểm tra
<!-- Các bước để reviewer chạy thử -->
1. 

## Ảnh chụp / video
<!-- Bắt buộc nếu có thay đổi UI -->

## Checklist
- [ ] Đáp ứng đủ acceptance criteria của ticket
- [ ] `./gradlew assembleDebug testDebugUnitTest` chạy xanh
- [ ] Có unit test cho logic mới
- [ ] Không hard-code chuỗi (đã thêm cả `values/` và `values-vi/`)
- [ ] Không đổi chữ ký trong `core/` (nếu có: đã có issue `contract` được duyệt)
- [ ] Không commit API key / token / `local.properties`
- [ ] Đã mời 2 thành viên review
