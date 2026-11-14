# WP01-11 — Hồ sơ kiểm đăng nhập và khóa tài khoản

Bắt đầu **14/11/2026**, branch `feat/wp01-11-staff-login` từ main WP01-10
`1e52bb3f0b77e1ac0bcc44799ae3ebb7090e029d`. Đã merge qua
[PR #12](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/12), main
`1dbe2842b41483c1f755c7ff9731365a07a308da`.
[Runbook](admin-auth.md) ghi luồng và giới hạn; nguồn Cane-MVP API §5.1, bảo mật
§2.1–2.3, kịch bản §3.6–3.8/3.14/3.16; T58/T59/T64 tiếp nối trên nghiệp vụ.

Phạm vi: login thực qua origin/CSRF/rate, dùng encoder chung, khóa staff kiểm lại
credential, counter commit trước 401, cấp/thay phiên và xoay CSRF sau commit.
Không sửa V001, 16 settings hoặc quyền runtime; không mở route quản lý staff.

Unit kiểm biên trước/bằng/sau hạn khóa, dummy work kể cả chuỗi rỗng, bounded
reverify, giới hạn Unicode và redaction. Integration gọi controller/chain production
và application với PostgreSQL/Redis thật: hash ngoài TX, wrong 1–5 và đúng trong
khóa, counter commit/no extension, 8 wrong đồng thời, hết khóa, password/active/
auth_version đổi giữa verify và lock. Credential race được đồng bộ bằng barrier
và connection riêng; thay credential qua role vận hành trong fixture, không qua
prompt CLI (CLI đã kiểm task 07). Policy thời gian chính xác đến nanosecond kiểm
unit; DB thật kiểm deadline đã qua và mốc +15 phút trong khoảng DB trước/sau.

HTTP kiểm payload/cookie/8 giờ/hash-only/thay đúng browser, CSRF rotation, generic
401, 429 IP tách khóa tài khoản, validation và whitespace, malformed stored hash.
Giữ nguyên limiter/ACL; giữa các ca độc lập chỉ DEL đúng key IP thử trong namespace
test. Giữ row session ở connection vận hành để DELETE thay phiên timeout: 503
không cookie, rollback reset counter và session INSERT, rồi retry phục hồi.
Không tạo event/ledger/outbox từ login. Test HTTP gửi cookie tường minh, chưa xác
nhận hành vi browser Secure/host prefix qua HTTPS; vẫn thuộc task 16.

Lượt integration đầu phát hiện fixture ép kiểu Timestamp từ queryForMap sang
OffsetDateTime; đã sửa RowMapper đọc timestamptz tường minh, không đổi code sản phẩm.
Kiểm tập trung cuối **141 unit + 43 PostgreSQL/Redis/HTTP integration đạt**, 0
failures/errors/skipped; Maven 1 phút 40 giây, kết thúc **12:03:48 GMT+7** ngày
14/11. Log ngoài Git `.local/wp01-11-focused-final.log`.

Runner chuẩn cuối **141 unit + 150 integration đạt**, 0 failures/errors/skipped;
format/build JAR đạt, Maven 6 phút 18 giây, kết thúc **12:10:38 GMT+7** ngày 14/11.
Log ngoài Git `.local/wp01-11-release.log`; XML đã đối chiếu (15 unit suite/12 IT
suite). Compose `cane-prep02-check-61921c988154` đã dọn container/network/volume;
không còn Testcontainers CANE hoặc image mới. JAR không chứa fixture/probe/lớp thử.
Log tập trung/toàn bộ không có password fixture hoặc marker hash hỏng. V001,
settings/grant và hai diff có sẵn ở checkout backend gốc giữ nguyên; SHA-256 của
hai file gốc khớp trước khi làm. Diff/liên kết local đạt. Job verify
[PR CI](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43560363866)
đạt 3 phút 25 giây; [main CI](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43560731511)
Success, tổng 3 phút 03 giây (job verify 3 phút). Git fetch xác nhận SHA và tree
trùng branch đã kiểm. Cane-MVP commit local `ecf3b68`, không push.
Hồ sơ này được cập nhật trong task kế tiếp.

Đây là bằng chứng API auth/cơ chế nền, chưa Verified toàn UC-A01/BR-016/NFR-006
hoặc T58/T59/T64 trên hệ thống triển khai. Không benchmark hash trên máy staging,
không thay smoke HTTPS/browser của WP01-16 bằng HTTP fixture hoặc CI.
