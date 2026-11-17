# WP06 — Admin và worker

Phạm vi/DoD theo `../cane-mvp/docs/pm/wp06-implementation-plan.md` phiên bản 0.2.
Hồ sơ kết quả tại `../cane-mvp/docs/pm/wp06-execution.md`; không dùng runbook này
để coi MS-06, staging hoặc gửi provider thật đã được nghiệm thu.

## API và trách nhiệm

Thêm 16 endpoint dưới `/api/v1/admin`: Orders GET list/detail và tám POST prepare,
ship, ready-for-pickup, hand-over, deliver, cancel, delivery-issue,
close-delivery-issue; Catalog GET skus/stock-movements, PATCH sku, POST
stock-adjustments; Admin GET/PUT settings. Auth, năm endpoint tiền và resend-link
reuse nền WP01/WP04/WP05. Không thay V001 hoặc grants.

Orders điều phối lifecycle; Payments sở hữu principal/receipt; Catalog sở hữu giá
và tồn, Admin sở hữu settings/session. Worker gọi application service/reader công
khai của module, không ghi xuyên repository nội bộ. Read nhiều bảng dùng cùng
read-only REPEATABLE READ. Write dùng nền A-TX: auth/quyền hiện hành trước replay,
replay trước guard có thể đổi; advisory actor/key → order UUID → payment UUID → SKU
sort. Retry deadlock dùng transaction mới, tối đa ba lượt; provider IO ngoài TX.

- Prepare không gửi email/trừ kho lần nữa. Delivery COD được completed/unpaid.
  Pickup COD chưa thu yêu cầu đủ total và nhận hàng cùng TX tiền/đơn/event/outbox;
  chỉ một completed email, không thêm payment_received email.
- Hủy trước bàn giao trả đủ một lần. Đóng giao thất bại chỉ Owner, shipping delivery
  có issue, đủ SKU duy nhất, số còn bán được 0..đã mua, note khi ít hơn và xác nhận
  đã xử lý hàng. Qty 0 nằm trong audit, chỉ qty dương có delivery_return ledger.
  Đã paid → refund_pending; xác nhận hoàn đủ principal gồm phí giao không đổi kho.
- Set stock kiểm expected sau khóa; retry cùng key được replay trước expected cũ.
  Không sửa expected rồi tự gửi lại. Adjust 0 vẫn có event/ledger; restock phải dương.
  PATCH giá/no-op và settings/no-op không tạo audit thừa.
- PUT settings merge nhóm trên đủ 16 khóa đã khóa sort, validate sau merge, rollback
  cả lần nếu một khóa sai. `bank_auto_confirm=true` bị từ chối trong bản manual.
  Cache invalidation sau commit; snapshot đơn cũ không bị cấu hình mới ghi đè.
- List không items/phone/email; preview FE lấy detail hữu hạn. Detail explicit DTO,
  không raw operation data/key/hash/token/viewUrl. Owner failure total toàn shop,
  tối đa 20 dòng độc lập filter, mã lỗi allowlist; không mở lại outbox exhausted.

## Job vận hành

Chỉ profile `worker & !api` bật `OperationalJobs`:

| Job | Fixed delay / lần đầu | Hành vi |
|---|---|---|
| Manual quá mốc | 60 giây / 60 giây | ≤50 theo due/id, loại đã nhắc trước LIMIT; khóa/recheck từng đơn; source `order:<id>:payment_due`, chỉ Owner; không hủy/nhả kho |
| Nhắc tồn đọng | 1 giờ / 1 giờ | Bảy nhóm đơn, receipt needs_review, outbox exhausted; scan trang 100 với upper bound cố định; từng đối tượng TX riêng |
| Dọn session | 1 giờ / 1 giờ | Admin service xóa expires_at≤now; không xóa phiên còn hạn, chạy lặp an toàn |

Nhắc giờ lấy tuổi từ mốc snapshot/event tương ứng; sửa lý do issue không làm mới tuổi.
Ngưỡng `stale_alert_hours` đọc hiện hành. Source theo ngày Asia/Ho_Chi_Minh và cách
lần gần nhất ≥24h; không chỉ so ngày. Manual hourly chỉ sau nhắc đầu. Khi gửi
Owner alert, source loader recheck điều kiện hiện tại; hết điều kiện thì skipped.
Không cảnh báo đệ quy từ chính cảnh báo lỗi gửi exhausted.

`NOTIFICATIONS_ENABLED` chỉ bật/tắt **dispatcher gửi**; các job producer và cleanup
vẫn chạy khi worker hoạt động. Đừng dùng biến này như công tắc dừng toàn bộ job.
Muốn dừng job thì dừng đúng worker. Dispatcher giữ batch20/concurrency4/10 giây,
lease5 phút/max8/fence; ACK rồi crash vẫn có thể gửi trùng.

## Chạy local và xử lý sự cố

Đọc `scripts/Run-Local.ps1`, `docs/wp05-notifications.md` và runbook session/settings
trước chạy. API/worker cùng PUBLIC_ORIGIN và keyring, khác credential DB; worker
không có HTTP server. Không seed lại stock vào DB đã có đơn để chữa fixture.

Dev dùng Mailpit loopback capture và Telegram mock, capture directory tuyệt đối
ngoài repo. Chỉ nạp env vận chuyển đã rà rõ capture/mock; không dùng bot/SMTP thật
ngầm. Không in env/keyring/recipient/token/email body vào log/handoff. Khi đổi
settings dùng Owner API/UI; không sửa SQL/outbox thủ công để giả kết quả.

Chờ manual cần đơn bank manual có mốc snapshot đủ ngắn, worker chạy qua mốc và
thêm chu kỳ 60s + dispatcher. Sau kiểm khôi phục settings đã đổi qua Owner UI/API,
giữ đơn/event/ledger/audit fixture có nhãn mock. Nhắc giờ/clock/fairness/cleanup
được kiểm bằng IT điều khiển clock; không tuyên bố đã chờ runtime một giờ nếu chưa.

Mất response POST: dùng đúng key/body; đọc hiện trạng/lịch sử trước thao tác mới.
Conflict không retry mù. Worker log lỗi từng đối tượng chỉ giữ failureType/location/
sqlState; kiểm source/event/outbox bằng credential vận hành trong môi trường đúng.
Không mở lại dòng sent/skipped/exhausted. Resend link tạo thông báo mới qua API.

Runner chuẩn hỗ trợ `-EvidenceDirectory <ngoài-repo>` để inventory/biên nhận kiểm
không rơi vào repo. Bằng chứng local WP06: 205 unit +315 IT, không fail/error/skip;
chi tiết race, fixture ban đầu lỗi và giới hạn ở execution/audit. CI/demo Owner,
HTTPS/proxy, tải/concurrency staging và provider ACK vẫn phải kiểm riêng.
