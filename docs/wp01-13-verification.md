# WP01-13 — Hồ sơ kiểm POST quản trị chống lặp

Bắt đầu **14/11/2026**, branch `feat/wp01-13-admin-post-idempotency` từ main WP01-12
`f565ebc1de3527b55336c9555122d07fc3294eb4`.
[PR #14](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/14) đã squash merge
`6c358012208e355df761d7f4812bd25b1f28f47a`; tree khớp branch đã kiểm
`b4b57ba94d8316e35727cd862c32e64c467ab43f`.
[CI PR](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43566044989)
đạt (tổng 3 phút 47 giây); [CI main](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43566560236)
đạt, job verify 3 phút 45 giây. Cane-MVP commit local `2000bdc`, không push.
[Runbook](admin-idempotency.md) ghi canonicalization, contract quyền và transaction.

Nguồn API §1.2–1.3, processing §1, security §1–3, test cases §3.8/3.11–3.13 và
kế hoạch WP01-13. Giữ V001/16 settings/grants và ranh giới mô-đun; không thêm bảng
idempotency hoặc POST thử, không đổi quy tắc ngân hàng hay API stock-adjustments.

Unit kiểm hash key order/UTF-8/null/nested/array, toàn body/expected/kind/rotate/
route/đích, phạm vi POST, copy/redaction. Hash golden đối chiếu Python hashlib/json
độc lập. HTTP mapping mismatch 409/no Retry-After dùng GET trong probe lỗi cũ chỉ
ở test; REQUEST_IN_PROGRESS vẫn 409/Retry-After: 1.

Integration PostgreSQL dùng session service, StaffAuthorization/JDBC hiện hành
và quyền API thật. Harness ghi stock/event/ledger/neo chỉ minh họa A-TX, không phải
API nghiệp vụ kho: guard expected giả lập để kiểm thứ tự replay, chưa validate
toàn DTO/guard stock-adjustments. Kiểm namespace actor; fast replay giữ result
cũ trước tồn/expected; mismatch body/route; no-op key mới; session active/version/
expiry/delete và công tắc bank trước replay; kiểm quyền lại trong TX; busy cùng/
khác hash; commit trong khoảng fast-read/advisory; thiếu/sai/đúp neo; rollback và
retry sau mất response; callback sau commit lỗi không lặp.

UNIQUE fallback được cưỡng bức bằng connection riêng đã commit cố ý không dùng
advisory, kiểm rollback trước đọc thắng và mismatch. UNIQUE source không bị coi
replay. Giữ nguyên test deadlock thật ở TransactionsIT; test A-TX tiêm SQLSTATE
40P01 từ PostgreSQL ở hai lượt để kiểm giữ key/hash và chỉ một tác dụng sau retry,
không gọi đó là deadlock thật. HTTP CSRF/origin/session được kiểm ở các suite
production chain có sẵn; tích hợp A-TX vào POST nghiệp vụ thật tiếp nối WP03–WP06.

Lượt đầu integration phát hiện fixture hết hạn đặt expires_at=created_at, trái
CHECK của schema; đã sửa created_at/hạn về quá khứ với khoảng tám giờ hợp lệ,
không nới constraint. Kiểm tập trung cuối **151 unit + 50 integration đạt**, 0
failures/errors/skipped; Maven 1 phút 08 giây, kết thúc **13:13:25 GMT+7** ngày
14/11. Log ngoài Git `.local/wp01-13-focused-final.log`.

Runner chuẩn cuối **151 unit + 192 integration đạt**, 0 failures/errors/skipped;
format/build JAR đạt, Maven 5 phút 59 giây, kết thúc **13:20:04 GMT+7** ngày 14/11.
Log ngoài Git `.local/wp01-13-release.log`; XML đã đối chiếu 17 unit suite/14 IT
suite. JAR không chứa fixture/probe/test; Compose `cane-prep02-check-4cc6cfd40d49`
đã dọn container/network/volume, không còn Testcontainers CANE hoặc image mới.
Log cuối không lộ marker hash-body/key/source hoặc lỗi SQLSTATE fixture. V001,
settings/grants và hai diff có sẵn ở checkout gốc giữ nguyên; SHA-256 hai file
khớp trước khi làm. Diff và liên kết local đạt; PR/CI/merge xem đầu hồ sơ. WP01-14 đổi tên thành phần hash thành CanonicalOperation, giữ factory/golden POST và thêm framing CLI tách biệt.
