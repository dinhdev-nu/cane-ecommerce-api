# CSRF, thông tin phiên và đăng xuất

Nguồn: Cane-MVP API §5.2–5.3, bảo mật §3 và nhóm kiểm §3.6/3.8. WP01-10 mở đúng
GET `/api/v1/admin/me` và POST `/api/v1/admin/auth/logout`; login thuộc WP01-11.
Không có endpoint bootstrap riêng, redirect HTML hoặc bảng/grant/settings mới.

## Cấu hình và nguồn request

API bắt buộc `PUBLIC_ORIGIN` từ cấu hình tin cậy, dạng scheme/host/port, không có
userinfo/path/query/fragment. Staging/production bắt buộc HTTPS. Local có thể đặt
`$env:PUBLIC_ORIGIN = 'http://localhost:3000'` trước `scripts/Run-Local.ps1 -Mode api`;
script kế thừa biến process này, không nạp từ `.local/dev.env`. Worker không cần
origin. Giá trị test `https://cane.example.test` chỉ ở test/fixture.

Mọi POST/PUT/PATCH/DELETE dưới `/api/v1/admin`, kể cả login/logout và đường chưa mở,
đều kiểm nguồn rồi CSRF trước lookup session. Origin có mặt phải hợp lệ và trùng
scheme/host/port; không dùng Referer để cứu một Origin sai. Origin vắng mới xét
Referer cùng origin. Thiếu cả hai, header trùng/không hợp lệ hoặc
Sec-Fetch-Site=cross-site bị 403 `FORBIDDEN`. Không dựng origin từ Host hay forwarding
header. Không mở CORS ngoài. Public/guest không dùng quyền cookie admin.

## Bootstrap và CSRF

GET me không body/query. Me materialize deferred token trước xác thực; chưa có/
hết/thu hồi phiên trả 401 JSON chung, vẫn có `X-CSRF-Token` và cookie CSRF nếu cần
cấp mới. Có phiên trả đúng `user:{id,username,role}`, `permissions:{canConfirmBank}`,
`csrfToken`, `sessionExpiresAt`; không trả principal, raw session/hash hoặc version.
Đọc không gia hạn session và không xoay CSRF đang hợp lệ. Mọi response no-store.

`StaffCsrf` tích hợp `CookieCsrfTokenRepository`/deferred token, giữ XOR request
attribute của Spring và chỉ giải giá trị thô từ đúng một `X-CSRF-Token`. Token có
định dạng UUID v4 canonical do framework sinh; không nhận body/query, header/cookie
trùng hoặc định dạng sai. Cookie `__Host-cane-csrf` Secure, SameSite=Lax, Path=/,
không Domain, HttpOnly=false. Cookie phiên có HttpOnly và khác phạm vi dữ liệu.
So token do `CsrfFilter` thực hiện constant-time. Xem [Spring Security 7.1 CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).

Cookie lookup mỗi request không chạy authentication strategy ngầm. Login custom
phải commit cấp/thay session rồi gọi `StaffCsrf.afterLogin` cùng thao tác cookie
session; hook gọi rõ `CsrfAuthenticationStrategy`, cấp cookie/header CSRF mới.
Hook từ chối chạy trong transaction. WP01-10 kiểm hook bằng request/filter fixture;
login HTTP thật sẽ kiểm tại task 11. Token header cũ không khớp cookie mới sau xoay.
Double-submit cookie không phải danh sách thu hồi token phía server: không tuyên bố
thu hồi toàn cục mọi cặp cookie/header cũ đã sao chép.

## Logout

POST logout nhận JSON `{}`, không query và không Idempotency-Key bắt buộc. Sau
origin/CSRF hợp lệ, application service xóa đúng hash cookie trong transaction.
Commit xong mới gọi `CsrfLogoutHandler`, xóa cookie session cùng scope, clear context
và trả 204 không body. CSRF cookie hết hạn có thể được Servlet/Tomcat biểu diễn bằng
Expires trong quá khứ thay cho Max-Age=0. Không xóa phiên trình duyệt khác.

Phiên vắng/đã thu hồi/không hợp lệ vẫn 204 sau các kiểm trên; không tạo phiên mới.
Lỗi DB khi xóa trả 503, giữ transaction/cookie chưa hoàn tất; không giả thành logout
thành công. Không ghi đơn/kho/event/biên nhận/outbox cho logout.

## Kiểm và giới hạn

`AdminAuthIT` gọi controller và SecurityFilterChain production qua HTTP thật, dùng
PostgreSQL/Redis thật và session fixture. Kiểm DTO, current permission, expiry,
logout đúng phiên/lặp, origin/CSRF/header/body/query, deny mặc định và rollback do
tranh khóa DELETE. Unit kiểm chuẩn origin, deferred/XOR/header-only, scope cookie,
xoay CSRF và từ chối đổi cookie trong transaction.

Test HTTP gửi cookie tường minh; chưa xác nhận browser lưu/gửi Secure/host-prefix
trên origin triển khai. HTTPS/proxy/browser thật vẫn thuộc WP01-16. Không coi hook
fixture là login HTTP, hoặc CI là nghiệm thu staging. [Hồ sơ kiểm](wp01-10-verification.md).
