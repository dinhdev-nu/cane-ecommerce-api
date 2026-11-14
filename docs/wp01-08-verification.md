# WP01-08 — Hồ sơ kiểm Redis/rate limit

Khởi đầu **14/11/2026**, branch `feat/wp01-08-rate-limits`.
Trạng thái: đang kiểm, chưa PR/CI/merge.

Phạm vi: năm nhóm đếm, cửa sổ 60 giây/TTL nguyên tử, 429/Retry-After, process fallback,
IP qua proxy allowlist; filter login trước Security. [Runbook](rate-limits.md).
Không thay schema, 16 settings hoặc quyền SQL/Redis hiện có.

Lượt kiểm tập trung đầu chưa tạo được trạng thái OOM bền vững: buffer đầu vào của
SET lớn gây OOM tạm thời rồi được giải phóng. Fixture được chỉnh để tạo payload
trong Redis và kiểm lỗi OOM thật. Script counter khai báo `#!lua` để Redis kiểm OOM
trước script, gồm counter đã có. Kết quả cuối được bổ sung sau khi runner đạt.

Kiểm tập trung cuối: **98 unit + 14 integration đạt**, 0 failures/errors/skipped,
format/build JAR đạt; Maven 47,008 giây, kết thúc **10:30:00 GMT+7** ngày 14/11.
Log ngoài Git `.local/wp01-08-focused.log`. Redis thật kiểm Lua/TTL, đồng thời
100 request, các nhóm/IP, HTTP 429/requestId/no-store, forwarding giả/nhiều dòng,
path encode, timeout/refused/OOM/phục hồi và ACL namespace. Unit kiểm biên chính
xác 60 giây, process restart/concurrency, canonical IP và log không lộ dữ liệu.

Runner chuẩn cuối **98 unit + 118 integration đạt**, 0 failures/errors/skipped;
format/build JAR đạt, Maven 5 phút 10 giây, kết thúc **10:35:38 GMT+7** ngày 14/11.
Log ngoài Git `.local/wp01-08-release.log`; đã đối chiếu XML. Compose project
`cane-prep02-check-975ca6b4ea79` không còn container/network/volume, không còn
Testcontainers CANE và không có image mới so inventory. JAR không chứa fixture/
probe/lớp thử. V001/grant không đổi, diff/liên kết local đã kiểm. Chờ PR/CI/merge.

Giới hạn: chưa có controller login; kiểm HTTP filter qua deny-all/CSRF thật và bộ
gọi nội bộ cho các nhóm chưa có route. Redis rỗng được mô phỏng bằng DEL đúng key
thử, không tuyên bố đã restart server. Process restart được kiểm bằng instance mới
của counter. Proxy độc lập/HTTPS/mạng private staging kiểm tại WP01-16. T59/T74 và
NFR-006/009 chưa được đánh Verified toàn phần từ bằng chứng nền này.
