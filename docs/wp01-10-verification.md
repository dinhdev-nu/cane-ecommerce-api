# WP01-10 — Hồ sơ kiểm CSRF/me/logout

Khởi đầu **14/11/2026**, branch `feat/wp01-10-csrf-me-logout` từ main WP01-09
`8b9d69a421ec14456e454ebc5fe1c6106825a1b7`. Đang kiểm, chưa PR/CI/merge.
[Runbook](admin-auth.md) mô tả hợp đồng, cấu hình và giới hạn.

Phạm vi: origin/CSRF trước session, me 401/200 có token, logout transaction và clear
cookie sau commit, hook CSRF login; không đổi V001/16 settings/grant runtime.

Kiểm HTTP đầu phát hiện framework tự chạy authentication strategy khi lookup cookie,
làm xoay CSRF mỗi request và clear cookie trước logout commit. Đã chọn yêu cầu gọi
strategy tường minh; kiểm lại token me ổn định, logout rollback không clear cookie
và hook login vẫn xoay sau commit. Test đọc đúng thuộc tính cookie Servlet thay vì
chuỗi header rút gọn của MockHttpServletResponse; HTTP thật kiểm SameSite và cookie
hết hạn. Biên nhận thao tác nằm trong business_events, không có bảng idempotency mới.

Kiểm tập trung cuối **132 unit + 32 PostgreSQL/Redis/HTTP integration đạt**, 0
failures/errors/skipped; format/JAR đạt, Maven 1 phút 02 giây, kết thúc
**11:29:00 GMT+7** ngày 14/11. Log ngoài Git `.local/wp01-10-focused.log`.
Có kiểm API từ chối PUBLIC_ORIGIN trống/sai/HTTP staging trước khi server bắt đầu;
staging/production chỉ chạy bằng fixture local, không tuyên bố đã triển khai.

Runner chuẩn cuối **132 unit + 139 integration đạt**, 0 failures/errors/skipped;
format/build JAR đạt, Maven 5 phút 25 giây, kết thúc **11:34:56 GMT+7** ngày 14/11.
Log ngoài Git `.local/wp01-10-release.log`; XML đã đối chiếu (13 unit suite/11 IT
suite). Compose `cane-prep02-check-c4e9ec325fcd` đã dọn container/network/volume;
không còn Testcontainers CANE hoặc image mới. JAR không có fixture/probe/lớp thử.
V001/16 settings/grant và hai diff có sẵn ở checkout backend gốc giữ nguyên.
Diff và liên kết local đạt. Chờ PR/CI/merge.

HTTP thật dùng session fixture, không mở controller/chain thay thế; login HTTP,
browser/proxy/HTTPS vẫn tiếp nối task 11/16. Không Verified toàn UC-A01/BR-016/
NFR-006 hoặc T58/T60/T75/T99 từ phần nền này.
