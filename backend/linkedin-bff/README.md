# LinkedIn BFF (Social Agent)

BFF tối giản cho đăng nhập LinkedIn (Standard OAuth 2.0). `client_secret` chỉ nằm ở máy chủ này, không nằm trong APK.
Thiết kế: `docs/research-social-api.md` (Luồng B). Không có dependency; cần Node.js 22 trở lên (đặt `NODE_VERSION=22` trên Render, trùng với CI).

## Endpoint
| Method | Đường dẫn | Việc |
|---|---|---|
| POST | `/auth/linkedin/start` | App đăng ký `{state, code_challenge}` (phiên sống 10 phút, dùng một lần) |
| GET | `/auth/linkedin/callback` | LinkedIn chuyển về đây; BFF đổi `code` lấy token rồi 302 về app kèm `exchange_code` + `state` |
| POST | `/auth/linkedin/token` | App gửi `{exchange_code, state, code_verifier}`; nhận `{access_token, token_type, expires_in, scope}` một lần (mã sống 60 giây) |
| GET | `/healthz` | Kiểm tra sống |

BFF không chuyển tiếp `refresh_token`, không có refresh/logout, không ghi log token/code/state/verifier.

## Cấu hình (biến môi trường)
| Biến | Ý nghĩa |
|---|---|
| `LINKEDIN_MEMBER_CLIENT_ID` | Client ID của App Member (công khai) |
| `LINKEDIN_MEMBER_CLIENT_SECRET` | Primary Client Secret của App Member. **Chỉ đặt trên máy chủ.** |
| `BFF_PUBLIC_URL` | URL HTTPS công khai của BFF, không có `/` cuối |
| `APP_REDIRECT_URI` | Mặc định `com.lttbdd.socialagent://oauth/linkedin/callback` (trùng Manifest) |
| `PORT` | Mặc định 8787 |
| `TRUSTED_PROXY_HOPS` | Số proxy tin cậy đứng trước BFF, dùng để lấy IP thật từ `X-Forwarded-For`. Mặc định `1` khi chạy trên Render (có biến `RENDER`), `0` ở nơi khác. Đặt sai (ví dụ `0` trên Render) thì mọi người chung một IP và dễ bị giới hạn nhầm. |

Redirect URL phải đăng ký ở LinkedIn Developer Portal (tab Auth của App Member): `{BFF_PUBLIC_URL}/auth/linkedin/callback`.

## Chạy và test
```bash
cd backend/linkedin-bff
npm test                # 30 test, LinkedIn được giả lập, không cần mạng
cp .env.example .env    # điền giá trị thật (không commit)
npm run start:local     # node --env-file=.env src/server.js
# hoặc đặt biến môi trường rồi: npm start
```

## Giới hạn tần suất
- `/start`: tối đa 20 lần/phút và 20 phiên đang chờ mỗi IP; vượt thì trả `429` kèm `Retry-After: 60`. `/token`: 60 lần/phút/IP.
- Kho phiên chung tối đa 1000 mục. Kho mã đổi đầy thì callback chuyển về app với `error=busy` (không trả `exchange_code` không tồn tại).
- `/start` với `state` đang chờ bị từ chối (`409 state_in_use`), không ghi đè `code_challenge` của phiên cũ.
- IPv6 được gom theo /64 khi làm khóa giới hạn; IPv4-mapped quy về IPv4.
- Lời gọi sang LinkedIn có timeout 10 giây; quá hạn thì callback chuyển về app với `error=exchange_failed`.
- Bộ đếm nằm trong bộ nhớ của một tiến trình, giống kho phiên.
- **Rủi ro nhóm chấp nhận:** kho phiên chung 1000 chỗ, mỗi IP giữ tối đa 20 phiên trong 10 phút, nên khoảng 50 địa chỉ khác nhau vẫn có thể làm đầy kho và người khác nhận `busy`. Chỉ ảnh hưởng việc đăng nhập được hay không, không làm lộ token. Ghi nhận ở issue #88.

## Lưu ý vận hành
- Phiên và mã nằm trong **bộ nhớ một tiến trình**: chạy đúng **một instance** (serverless nhiều instance sẽ làm callback/token rơi vào instance khác). Cần nhiều instance thì đổi sang kho dùng chung (Redis).
- Hosting free có thể "ngủ": lần gọi đầu chậm; phiên start→callback phải xảy ra trên cùng instance đang chạy.
- Chỉ phục vụ qua HTTPS (app từ chối `http://`).
