# WP01-04 — Hồ sơ kiểm runtime/readiness

Ngày **10/11/2026**, branch `feat/wp01-04-runtime-readiness`.
Trạng thái: **hoàn thành**, [PR #5](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/5)
merge main `49b7102`; CI PR `42785321588`, main `42785814206` đạt. Truy vết
Cane-MVP commit local `f0fd1b9`, không push. Local **36 unit + 59 integration,
0 failures/errors/skipped**.

Runner `pwsh -NoProfile -File scripts/Test-Backend.ps1` kiểm đúng toolchain, build
executable JAR; Maven **4 phút 18 giây**, kết thúc **15:45:32 GMT+7**. Đã đối chiếu
XML Surefire/Failsafe. Project `cane-prep02-check-92100d3174fe` đã dọn container,
network và volume; không còn Testcontainers thuộc bộ kiểm, không có image mới
so inventory. Log ngoài Git: `.local/wp01-04-verified.log`. V001 và grant không đổi.

## Phạm vi và nguồn

Backlog WP01-04; thiết kế kỹ thuật §2.2/5.4, vận hành §1–2; hạ tầng gốc §7–8;
thiết kế dữ liệu §8 và kịch bản nền §3.15. Không thay schema/V001 hoặc grant matrix.

`DatabaseReadiness` kiểm kết nối/role/schema/history/version/checksum và quyền
tối thiểu bằng runtime credential. `InfrastructureReadiness` cung cấp diagnostics
nội bộ theo yêu cầu. `InfrastructureStartupCheck` chạy trước HTTP/job; DB không
đúng làm context dừng, Redis lỗi chỉ suy giảm. Một JAR/hai vai trò, thêm profile
staging/production và chặn cấu hình sai. [Hướng dẫn](runtime-readiness.md).

## Ca kiểm

- Unit initializer: đúng/sai môi trường-vai trò, thiếu secret, protected settings,
  bật auto migration/init, môi trường lệch và forwarding không được phép.
- API có HTTP/no business scheduler; worker không HTTP, có scheduling và giữ tiến
  trình; cùng cấu hình JDBC/Redis runtime, không cần provider/SKU/staff giả để boot.
- PostgreSQL thật: thiếu schema/history/table/setting/grant; checksum sai, future,
  pending hoặc failed history; runtime không sửa hoặc tự migrate. So metadata,
  history và settings trước/sau kiểm trên fixture lỗi.
- Role có DDL, ownership hoặc membership bị từ chối. Search_path/SQL timeout sai
  bị từ chối; credential sai không READY. Bộ kiểm quyền đầy đủ giữ ở task 02.
- Sai checksum làm startup thất bại trước `WebServerInitializedEvent` và trước
  heartbeat thử trong worker. Profile staging/API và production/worker trên
  fixture local chạy được cả pool tối đa một connection; kiểm lại sau boot vẫn đạt.
- Diagnostics phát hiện schema mất sau startup, không dùng kết quả khỏe đã cache.
  Redis port không kết nối được có `ready=true, degraded=true` khi DB đúng.
- Kiểm lại API input/error/log, migration và trigger/grant hiện có qua runner đầy đủ.

## Giới hạn

Đây là kiểm nền và profile bằng fixture local, không Verified toàn bộ NFR-006/009.
Chủ dự án xác nhận ngày 10/11/2026 chưa có staging/domain. Chưa triển khai proxy,
TLS hoặc smoke staging. Rate/fallback thật thuộc task 08; auth/session/CSRF thuộc
09–11; secret HMAC/provider chỉ bắt buộc khi triển khai tính năng tương ứng.
Không mở health/public route hoặc dựng thêm nghiệp vụ để chứng minh readiness.
