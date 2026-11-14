# WP01-09 — Hồ sơ kiểm session/quyền hiện hành

Khởi đầu **14/11/2026**, branch `feat/wp01-09-staff-sessions` từ main WP01-08
`6b7e77810aa9681cadcba7423fa6479f4a42c7fc`. Đang kiểm, chưa PR/CI/merge.
[Runbook](staff-sessions.md) mô tả caller transaction, cookie và kiểm quyền.

Phạm vi: token 32 byte/canonical/SHA-256, cấp/thay/thu hồi nguyên tử, hạn tuyệt đối
tám giờ theo mốc DB, cookie đúng scope, filter và application guard kiểm staff/
version/role/flag bank hiện hành. Không đổi schema/16 settings/quyền runtime.

Kiểm tập trung **114 unit + 8 PostgreSQL/Redis/HTTP integration đạt**, 0 failures/
errors/skipped; format/JAR đạt, Maven 51,200 giây, kết thúc **10:56:45 GMT+7** ngày
14/11. Log ngoài Git `.local/wp01-09-focused.log`. Ca tranh chấp đồng bộ bằng khóa
staff và quan sát `pg_locks` (không nới quyền runtime/quan sát); PostgreSQL thật từ
chối cấp phiên version cũ sau khi chờ thay đổi commit. Test mất DB pause đúng
container riêng, trả 503 hữu hạn rồi phục hồi khi Redis vẫn đang chạy.

Runner chuẩn cuối **114 unit + 126 integration đạt**, 0 failures/errors/skipped;
format/build JAR đạt, Maven 5 phút 16 giây, kết thúc **11:03:48 GMT+7** ngày 14/11.
Log ngoài Git `.local/wp01-09-release.log`; XML đã đối chiếu. Compose project
`cane-prep02-check-7d310b20bad7` đã dọn container/network/volume; không còn
Testcontainers CANE hoặc image mới. JAR không có fixture/probe/lớp thử; V001/grant
không đổi, diff và liên kết local đạt. Chờ PR/CI/merge.

Không dùng kết quả nền để xác nhận login/me/logout/CSRF hoặc T58/T60/T75/T99 toàn
phần. Test có controller và rule riêng trong test classes để kiểm HTTP dùng cơ chế
production; sản phẩm vẫn deny-all ngoài filter phiên, không đóng gói probe. Hạn
bằng/trước/sau kiểm bằng snapshot thời gian DB fixture ở unit; SQL thật kiểm cấp
đúng +8 giờ, phiên còn hạn/quá hạn và không tự gia hạn. HTTPS/browser cookie thực
tế kiểm tại WP01-16, không suy từ HTTP local.
