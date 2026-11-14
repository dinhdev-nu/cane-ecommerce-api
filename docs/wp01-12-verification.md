# WP01-12 — Hồ sơ kiểm event/biên nhận/outbox

Bắt đầu **14/11/2026**, branch `feat/wp01-12-event-receipt-outbox` từ main WP01-11
`1dbe2842b41483c1f755c7ff9731365a07a308da`. Đã merge qua
[PR #13](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/13), main
`f565ebc1de3527b55336c9555122d07fc3294eb4`.
[Runbook](event-receipts-outbox.md) ghi contract caller và giới hạn.

Nguồn: database §3.8–3.10/§4/§7, processing §1/§8.2, test cases
§3.2/§3.13/§3.16 và kế hoạch WP01-12. Không sửa migration V001, settings hoặc
quyền runtime. Không mở endpoint test, không thêm nghiệp vụ/dispatcher/renderer.

Unit kiểm allowlist/redaction/copy, cấu trúc nested có quantity 0 và key/hash.
Integration dùng PostgreSQL thật, grants API/worker và trigger production:
write-TX guard; cùng connection/commit; visibility ngoài transaction; neo theo
actor; ưu tiên khách và chuẩn hóa email/Telegram; duplicate exact tuple và concurrent
INSERT; phân biệt UNIQUE source/op/PK; FK/nguồn sai; payment snapshot/refund reference;
receipt đã đổi order vẫn giữ event/outbox cũ; unverified/unlinked chỉ Owner;
cash completed không tách email tiền; settings đổi không sửa recipient đã xếp.
Fault injection sau từng bước money/stock/event+neo/ledger/outbox và beforeCommit
đối chiếu toàn bộ DB trước/sau, không để neo thành công khi rollback.

Đây là harness thành phần dùng fixture hợp lệ về schema/nguồn, không xác nhận
guard nghiệp vụ hoàn chỉnh hoặc toàn T/BR/NFR ở các gói sử dụng. Caller còn phải
chọn đủ facts theo event/template, public_label cố định và kiểm quyền/khóa.
Không kiểm provider gửi đúng một lần, HTTPS hoặc staging trong task này.

Lượt đầu bắt lỗi cách gọi assertion trong test; lượt integration đầu bắt mã đơn
fixture dài hơn CHECK cho phép. Đã sửa test/fixture, không nới schema hoặc guard.
Kiểm tập trung cuối **146 unit + 52 integration đạt**, 0 failures/errors/skipped;
Maven 1 phút 16 giây, kết thúc **12:44:25 GMT+7** ngày 14/11.
Log ngoài Git `.local/wp01-12-focused-final.log`. Sau lượt này, harness rollback
được bổ sung UPDATE order/áp cash principal để kiểm cả thay đổi đơn; runner chuẩn
kiểm lại bản cuối.

Runner chuẩn cuối **146 unit + 172 integration đạt**, 0 failures/errors/skipped;
format/build JAR đạt, Maven 5 phút 58 giây, kết thúc **12:50:49 GMT+7** ngày 14/11.
Log ngoài Git `.local/wp01-12-release.log`; XML đã đối chiếu 16 unit suite/13 IT
suite. JAR không chứa fixture/probe/test; Compose `cane-prep02-check-f59a9bc7d368`
đã dọn container/network/volume, không còn Testcontainers CANE hoặc image mới.
V001/16 settings/grants và hai diff có sẵn ở checkout gốc giữ nguyên; SHA-256 hai
file khớp trước khi làm. Diff và liên kết local đạt. [CI PR](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43563637224)
Success, tổng 3 phút 20 giây (job verify 3 phút 18 giây); [CI main](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43563945061)
Success, tổng 3 phút 35 giây (job verify 3 phút 33 giây). Git fetch xác nhận SHA/tree
trùng branch đã kiểm. Cane-MVP commit local `f20f1af`, không push.
Hồ sơ này được cập nhật trong task kế tiếp.
