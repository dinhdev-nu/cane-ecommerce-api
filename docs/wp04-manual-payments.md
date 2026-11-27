# WP04 — Thanh toán thủ công

Cửa hàng nhận COD và chuyển khoản xác nhận thủ công. Owner hoặc Operator có quyền đối chiếu giao dịch thực rồi ghi nhận trong Dashboard. Các API dưới đây ghi nhận thu, đối soát và hoàn tiền. Worker gửi thông báo sau khi giao dịch đã commit. Trạng thái outbox `queued` cho biết thông báo đang chờ gửi. [Quyết định mở bán WP07](../../cane-mvp/evidence/wp07/wp07-execution.md#wp07-go-live-20261126)

## API và quyền

Tất cả đường dưới `/api/v1`, response `Cache-Control: no-store` và `X-Request-ID`.
POST dùng session cookie, Origin/CSRF hiện hành và một `Idempotency-Key` 16–200 ký tự.
Đăng nhập/bootstrap CSRF theo [runbook auth](admin-auth.md). Không đưa token/cookie vào Git/log.

| Route | Quyền / tác dụng |
|---|---|
| POST `/admin/orders/{id}/confirm-payment` | Staff thu cash COD; bank chỉ Owner hoặc Operator có cờ hiện tại. Áp đúng một khoản chính. |
| GET `/admin/payments` | Owner; `needsReview`, `orderId`, `page` từ 1, `pageSize` 1–100; mặc định 1/20. Items/total cùng snapshot RR; mới nhất trước. |
| POST `/admin/payments` | Owner ghi khoản phụ cần đối soát; chưa tự paid. Cash cần đơn COD và note bằng chứng; bank có thể chưa rõ đơn. |
| POST `/admin/payments/{id}/resolve` | Owner xác minh và áp/đóng review; chỉ khoản phụ succeeded. |
| POST `/admin/payments/{id}/confirm-refund` | Owner xác nhận đã hoàn **toàn bộ receipt** bên ngoài, có chứng từ thật. |

POST thành công HTTP 200 `{operationId,replayed,result}`. Retry cùng key/body trả nguyên
biên nhận cũ; quyền/session vẫn kiểm hiện tại. Key mới phải kiểm lại expected/guard.
`REQUEST_IN_PROGRESS` 409 có `Retry-After: 1`: đợi rồi retry **cùng key/body**.
`IDEMPOTENCY_MISMATCH` 409: cùng key nhưng request khác; không sửa body để retry mù.
Lỗi input cấu trúc 400/miền giá trị 422; đơn/receipt đúng định dạng nhưng không tồn tại 404;
`STATE_CONFLICT` 409 có `error.current`, sai phương thức `PAYMENT_METHOD_MISMATCH`,
sai tiền/receipt/guard `PAYMENT_CONFLICT` 409. Lỗi hạ tầng/bất nhất 503 không lộ SQL/bằng chứng.

## Payload mẫu — chỉ MOCK, không chuyển tiền

Thay UUID/mã đơn/total bằng dữ liệu của **đơn thử thật đã đặt**; bank/account phải lấy
từ snapshot của đơn, không tự lấy cấu hình shop mới khi xử lý đơn cũ. Ví dụ bên dưới
không là dữ liệu mở bán. [Nạp dữ liệu vận hành](operator-data.md) liệt kê trường thật,
lệnh nhập và checklist bắt buộc thay ngân hàng mock trước go-live.

Confirm cash: chỉ COD unpaid, status confirmed/preparing/ready_for_pickup/shipping/completed.
Cash giữ status, đặt paid; không nhận reference hoặc bankReceipt khác null.

```json
{"expectedStatus":"confirmed","method":"cash","amountVnd":40000,"reference":null,"bankReceipt":null,"note":null}
```

Confirm bank: chỉ awaiting_payment/unpaid, đúng toàn bộ total, snapshot tài khoản và
đúng một token mã đơn có ranh giới. Thành confirmed/paid, xóa payment_due_at.

```json
{"expectedStatus":"awaiting_payment","method":"bank","amountVnd":40000,"reference":"MOCK-BANK-TRANSACTION-001","bankReceipt":{"bankCode":"CTG","accountNo":"0338825982","transferContent":"MOCK CANEABC123","transactionAt":"2026-11-15T01:30:20Z"},"note":null}
```

POST payments nhận trường như confirm nhưng thay `expectedStatus` bằng `orderId` UUID/null.
Bank luôn verified=false lúc nhập, kể cả có orderId. Cash verified=true nhưng phải
ghi note bằng chứng và không được chuyển/gỡ đơn. Cả hai là succeeded, applied=false,
needsReview=true. Khoản thiếu/thừa được ghi đúng tiền thật, không ép khớp tổng.

```json
{"orderId":null,"method":"bank","amountVnd":10000,"reference":"MOCK-UNKNOWN-001","bankReceipt":{"bankCode":"CTG","accountNo":"0338825982","transferContent":"MOCK unknown transfer","transactionAt":"2026-11-15T01:30:20Z"},"note":"MOCK evidence awaiting Owner verification"}
```

Resolve yêu cầu `expectedPaymentStatus=succeeded`, `note` không trắng. `apply_to_order`
cần orderId, khớp phương thức/tổng/tài khoản và chưa có khoản chính. Owner có thể xác
minh mã chuyển sai bằng note, giữ nguyên bằng chứng. Cancelled chỉ sang refund_pending,
không mở lại/đổi tồn. `close_review` chỉ xác minh liên kết hoặc gỡ bank về null, không
paid/refunded. Cash giữ đúng đơn cũ. Khoản đã áp/đã hoàn không được relink.

```json
{"action":"close_review","orderId":null,"expectedPaymentStatus":"succeeded","note":"MOCK verified unrelated receipt; no customer link"}
```

Hoàn không nhận amount để hoàn một phần. Principal chỉ cancelled/refund_pending,
sang refunded cả receipt và trục tiền đơn; khoản phụ hoàn độc lập kể cả completed/unknown,
không đổi principal/đơn/tồn. Cùng chứng từ sau khi đã hoàn qua key mới là no-op có neo;
khác chứng từ conflict. Xác minh đã chi tiền thật trước khi gọi:

```json
{"expectedPaymentStatus":"succeeded","reference":"MOCK-REFUND-EVIDENCE-001"}
```

## Chuẩn hóa và chống trùng

- Mã bank trim, dùng mã canonical; account bỏ khoảng trắng, **giữ số 0 đầu**. Reference
  trim và giữ hoa/thường/ký tự; không dùng mã đơn làm reference giao dịch.
- Nội dung trim/CRLF→LF; transactionAt ISO UTC `Z` và chuẩn hóa giây theo parser nền.
  Không thay bằng thời gian nhập khi thiếu bằng chứng ngân hàng.
- `BankReceiptKey` encode provider_ref thành JSON compact `[bankCode,accountNo,reference]`.
  API/event chỉ lấy reference thật; không xuất khóa tổng hợp hoặc bankReceipt vào audit.
- Cùng khóa so bất biến method/amount/bank/account/ref/content/time. Khác dữ kiện conflict;
  note/orderId sau đối soát không tham gia dedup. Không ghi đè dữ kiện hoặc liên kết cũ.
- Nhập lại bank cùng giao dịch trả receipt cũ; key mới ghi `operation_noop`, không event
  tiền/outbox mới. Confirm có thể áp receipt cũ chưa áp/hoàn khi unknown hoặc đúng đơn.
  Cùng lần nhận cash chỉ dedup bằng key: nhân viên phải kiểm lịch sử trước khi tạo key mới.

## Transaction, ownership và bàn giao

`OrderMoneyService` điều phối order+receipt; Payments sở hữu policy/persistence, không
gọi ngược Orders. A-TX giữ advisory actor/key → đủ order ID tăng dần → receipt ID tăng dần.
Link chuyển ra ngoài tập đã khóa phải rollback và lấy lại từ đầu (tối đa ba lượt),
không khóa bổ sung order sau receipt. Tranh UNIQUE bank rollback trước khi đọc lại.
Event/neo/outbox nằm cùng transaction; không sửa V001, không gọi Redis/provider trong khóa tiền.

Kiểm invariant liên bảng dưới khóa: unpaid không có principal; paid/refund_pending có
principal succeeded khớp tổng/phương thức/tài khoản; refunded có principal refunded.
Không tự sửa dữ liệu hỏng hoặc sinh receipt bù để tiếp tục.

Khi bàn giao đơn lấy tại shop, `OrderLifecycleService` gọi `collectAtPickup(principal, orderId)` trong write A-TX. Caller gọi
trước khóa receipt/SKU khác; chỉ pickup ready_for_pickup. Đã paid dùng principal cũ;
COD unpaid ghi/áp cash đúng total trong transaction caller. Caller chuyển completed,
ghi **một** completed event/neo/outbox cùng transaction. Hàm không phát email cash riêng.
Lỗi completion phải rollback luôn tiền. [Luồng bàn giao trong Dashboard](wp06-admin-worker.md)

Event giữ paymentId/orderId/amount/method/reference/chính-phụ/verified và người/mốc review,
mốc hoàn/action tại sự kiện. Worker dựng thông báo từ dữ kiện của sự kiện để giữ đúng trạng thái tiền tại thời điểm phát sinh. Xác minh liên kết qua close_review không diễn đạt là khách
vừa trả thêm. Khách chỉ nhận bank payment_received/hoàn khi verified; unknown/unverified
chỉ Owner alert. Cash không có email nhận tiền riêng. No-op không xếp lại thông báo.

## Kiểm thử

`MoneyCommandsTest` và `MoneyPolicyTest` kiểm quy tắc tiền, chuẩn hóa, hash và quyền. `ManualPaymentsIT` kiểm HTTP, CSRF, quyền, SQL, đối soát, hoàn tiền, cạnh tranh và rollback. Ca kiểm pickup tại đây tập trung vào giao dịch tiền và hoàn tất đơn. Chạy `scripts/Test-Backend.ps1` từ clone BE. [Kết quả WP04](../../cane-mvp/evidence/wp04/wp04-execution.md) · [Gửi thông báo và thử lại](wp05-notifications.md) · [Kiểm luồng bàn giao](wp06-admin-worker.md)
