# WP03 — Quote và đặt đơn

`POST /api/v1/checkout/quote` là phép đọc SQL nhất quán, không khóa/giữ tồn hoặc
ghi đơn. Giá và kiểm vùng, phí, ngưỡng miễn, maxQty, phương thức, shop và tồn dùng
chung `CheckoutPolicy` với đường tạo đơn. Tiền không xác định trả null; pickup
fee=0. Quote không công khai số tồn.

`POST /api/v1/orders` chuẩn hóa và hash nghiệp vụ, bỏ tiền do client tự gửi và
không hash `expectedTotalVnd`. Key đã tồn tại được replay trước mọi guard thay đổi.
Đường mới dùng transaction READ COMMITTED, try advisory lock theo key, đọc lại key,
đọc settings, khóa SKU tăng dần, kiểm/tính lại và so tổng khách đã xác nhận.
`QUOTE_CHANGED` trả quote mới và rollback; client phải xác nhận lại.

Order/items, snapshots, stock/ledger, placed event và outbox commit cùng nhau.
Event được insert trước ledger để giữ FK. Lỗi ghi bất kỳ rollback toàn bộ; UNIQUE
key chỉ đọc người thắng sau rollback; mã đơn trùng được sinh lại hữu hạn. Retry
deadlock và callback sau commit dùng nền `Transactions` có sẵn. Cache prices DEL
sau commit không làm mất kết quả thành công nếu Redis lỗi. Không gọi provider
hoặc Redis trong vùng khóa tồn.

COD = confirmed/unpaid; bank = awaiting_payment/unpaid, snapshot bank/mode/mốc.
Manual có `paymentReminderAt`, `expiresAt=null`; không tự hủy khi qua mốc. Đặt đơn
không tạo payment receipt. Response là DTO allowlist; link dùng phiên bản/hạn hiện
tại, fragment `#t=`, replay hết hạn không gia hạn và trả null. View page/API và gửi
thông báo thuộc WP05; WP03 chỉ ghi outbox, không khẳng định email đã gửi/đã nhận tiền.

Security mở đúng hai POST, quote 30/phút/IP và place 10/phút/IP, dùng trusted IP,
Redis counter/fallback hiện có. Request/error/result `no-store`, không log body,
PII hoặc link/token. Không sửa V001/schema/grants nền; giữ nguyên bộ mock/loader
WP02 và hướng dẫn thay dữ liệu thật ở [operator-data.md](operator-data.md).

Kiểm mới: `PlaceOrderCommandTest` (normalize/hash/unknown fields), `PlaceOrderIT`
(HTTP + PostgreSQL/Redis thật, bốn tổ hợp, quote, replay, tổng đổi, thiếu một dòng,
tranh chấp tồn/key, rollback outbox/cache, Redis mất kết nối và rate). Mỗi ca IT
dùng database riêng, giữ nguyên trigger audit và Redis ACL. Không dùng TRUNCATE,
KEYS/FLUSH hoặc nới quyền runtime để chuẩn bị fixture.

Sau đợt `scripts/Test-Backend.ps1`, nếu chỉ sửa lớp IT này, chạy đúng lớp đó bằng
PowerShell với tham số Maven có dấu chấm được quote:

```powershell
.\mvnw.cmd -B -ntp test-compile failsafe:integration-test failsafe:verify '-Dit.test=PlaceOrderIT'
```

Hồ sơ kết quả, quyết định giữ xem đơn ở WP05 và các điểm staging/demo còn mở được
quản lý trong `cane-mvp/evidence/wp03/wp03-execution.md`. Đạt kiểm local/CI không tự là
nghiệm thu mở bán hoặc xác minh tài khoản MOCK DEV.
