# WP01-15 — Hồ sơ kiểm link HMAC

Bắt đầu **14/11/2026**, branch `feat/wp01-15-order-links` từ main WP01-14
`385e3026bc932a547192868140734a2b5b1d7d84`. Đã hoàn thành: [PR #16](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/16)
squash merge tại `ecf5744de1a5d58a64e685716d5c0f5090f1839c`, tree trùng branch đã
kiểm `1de4c0f6e9f5b003772cc9045d0f803006418e92`. [CI PR](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43570963794)
đạt 3 phút 34 giây; [CI main](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43571346560)
Success 3 phút 46 giây, job 3 phút 43 giây. Cane-MVP commit local `c195617`, không push.
[Runbook](order-links.md) ghi key ring, token, quyền, thời gian và quy trình xoay.
Nguồn hợp đồng §8, security §4, database link fields, test cases §3.9/3.16 và
kế hoạch WP01-15. Giữ V001/settings/grants/toolchain và ranh giới mô-đun.

Orders domain/application/crypto/JDBC tách trách nhiệm; bootstrap ghép ring cho cả
API/worker. OrderLinkService kiểm HMAC/hạn trước SQL, đúng ID/version/Instant hiện
hành và Clock sau SQL/đợi khóa; write yêu cầu TX caller và khóa đơn trước ghi. Token
chỉ trong wrapper trả caller, toString redacted; không DB/event/neo/outbox/Redis/URL.
Orders advice dùng ApiErrors để ánh xạ cùng 404 ORDER_LINK_INVALID, không import
ngược Orders vào support. Các route nghiệp vụ chưa triển khai vẫn deny-all.

Unit có vector MAC đối chiếu Python hmac/hashlib độc lập, canonical ASCII sáu phần,
kid/UUID/decimal/base64url/MAC/pad bits/512 byte, sửa từng phần, overflow, key ngắn/
lạ/duplicate/trailing và cấu hình lỗi không cause/echo. Đổi active giữ old/new key
xác minh chéo, bỏ kid cũ làm link kid đó không hợp lệ đúng giới hạn. So MAC dùng
MessageDigest.isEqual trong code, không gọi test chức năng là benchmark timing.

PostgreSQL thật/quyền API kiểm mint từ DB, một đơn, version/hạn/missing cùng lỗi,
timezone tương đương, trước/bằng/sau hạn, không tự gia hạn và không ghi token/lịch
sử. Link lỗi/hết hạn không chạm SQL; lỗi quyền DB vẫn DataAccessException kỹ thuật.
Ghi ngoài TX/read-only bị từ chối; kiểm Clock sau khóa tránh hết hạn trong lúc chờ.
Race hai transaction có barrier: rotate version/hạn trước guest lock, guest đã đọc
link cũ vẫn không ghi được. Rotate rollback giữ link cũ. Đây là harness cơ chế,
không phải use case rotate/guest ghi hoàn chỉnh có audit/outbox.

Runtime API và worker thật dùng cùng ring fixture và xác minh token của nhau;
missing/unknown-active/malformed config ở cả hai mode dừng trước HTTP/heartbeat,
exception trace không chứa input. Các suite Boot cũ nhận key ring thử từ fixture
riêng, không có key mặc định trong main resources/JAR. GET probe lỗi chỉ ở test
kiểm mapper HTTP 404, requestId/no-store/no Retry-After, chưa phải guest API.

Rà sau lượt kiểm tập trung đầu phát hiện nhánh DB expiry dưới millisecond cần
trả lỗi link chung thay vì lỗi dữ liệu kỹ thuật. Đã giữ nguyên Instant khi đọc DB,
so mismatch thành InvalidOrderLinkException; signer vẫn từ chối ký hạn chưa chuẩn
milliseconds. Kiểm tập trung cuối **159 unit + 59 integration đạt**, 0 failures/
errors/skipped; Maven 1 phút 21 giây, kết thúc **14:14:52 GMT+7** ngày 14/11.
Log ngoài Git `.local/wp01-15-focused-final.log`; marker config/token thử không
xuất hiện trong log.

Runner chuẩn cuối **159 unit + 224 integration đạt**, 0 failures/errors/skipped;
Maven 6 phút 20 giây, kết thúc **14:22:15 GMT+7** ngày 14/11. Log ngoài Git
`.local/wp01-15-release.log`; XML đối chiếu 19 unit suite/16 IT suite. Format/build
JAR đạt, không fixture/probe/test. Compose `cane-prep02-check-15ee25838319` đã dọn
container/network/volume, không còn Testcontainers CANE hoặc image mới. Marker
config/token thử không xuất hiện trong log cuối. Diff/liên kết đạt; V001/settings/
grants/POM và hai file có sẵn ở checkout gốc giữ nguyên SHA-256. PR/CI/merge xác nhận ở trên.

Chưa dùng secret/đơn thật; không xóa/đổi key đã có.
Đây là nền UC-C08/C09/A09, BR-013, NFR-006 và phần T50–T57; chưa Verified toàn yêu
cầu. Guest/resend/rotate HTTP, fragment/browser, render/gửi email và event/neo/outbox
thao tác Owner tiếp nối WP05; backup restore/HTTPS thật chưa nghiệm thu. Không thêm
lệnh sửa version bỏ qua audit hoặc tự gia hạn. Cane-MVP chỉ commit local, không push.
