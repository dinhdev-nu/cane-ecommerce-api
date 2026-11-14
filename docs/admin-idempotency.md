# POST quản trị chống thực hiện lặp

WP01-13 cung cấp hash POST (từ WP01-14 dùng factory `CanonicalOperation.adminPost`), `IdempotentOperations` và
`OperationReceipt` trong `support/idempotency`. Nguồn Cane-MVP API §1.2–1.3,
processing §1.1–1.2, security §1–3, test cases §3.8/3.11–3.13.
Không có bảng hoặc POST thử mới; các service nghiệp vụ dùng khung này khi được
triển khai. Login/logout, PATCH giá, PUT settings và hash đặt đơn không dùng A-TX.

Caller bind/validate/normalize **một command bất biến**, dùng chính command đó
cho hash và phần ghi. Tạo `CanonicalOperation.adminPost` bằng method POST, route thực do
server dựng từ constant + ID/SKU đã chuẩn hóa, body JSON đã chọn đủ trường DTO,
và Idempotency-Key. Không dùng raw JSON hay route template chứa `:id`, không đưa
cookie/CSRF vào body. Body phải chuẩn hóa optional null, chuỗi, thời gian và thứ
tự items theo hợp đồng endpoint trước; khung chỉ sort key object đệ quy, giữ thứ
tự array và mọi giá trị body, kể cả expected/kind/rotate. Route không query/fragment,
percent encoding hoặc dot segment; chỉ dùng nhóm orders/payments/stock-adjustments.

Hash SHA-256: lần lượt UTF-8 method uppercase, route chuẩn hóa, JSON canonical
compact; mỗi phần có prefix độ dài 4 byte big-endian. Key không thuộc hash. Writer
JSON riêng giữ định dạng ổn định trước thay đổi cấu hình serializer HTTP; giá trị
body chỉ object/array/string/bool/null/số nguyên đã validate. Không giữ body trong
command; hash/key/result có defensive copy hoặc redaction theo kiểu dữ liệu.
Golden UTF-8 test có reference Python độc lập để tránh đổi canonicalization vô ý.

Application service gọi ngoài transaction hiện hữu:

```java
operations.execute(
    () -> authorization.requireOwner(principal).id(),
    canonicalPost,
    operation -> {
        // Khóa order → payment → SKU, kiểm expected/guard; cập nhật nghiệp vụ.
        // Ghi event được chọn với actor operation.actor(), kèm operation.stamp(result).
        // Ghi ledger/outbox trong cùng callback trước commit.
    });
```

Hàm authorize thuộc service gọi, phải đọc SQL hiện hành mỗi lần và trả UUID nhân
viên đã xác thực. Chọn đúng requireCurrent/requireOwner/requireBankConfirmation
hoặc quyền nghiệp vụ đầy đủ; không truyền actor từ body hay callback chỉ trả ID
đã cache. HTTP origin/CSRF/session chạy ở production SecurityFilterChain trước
khi gọi service. CLI dùng xác thực Owner phù hợp, không giả cookie/CSRF HTTP.
`support` không import ngược Admin hoặc tự quyết định quyền nghiệp vụ.

Luồng: kiểm quyền → tra neo actor/key → nếu có so hash/replay; nếu chưa có, BEGIN
READ COMMITTED → kiểm lại quyền/actor → try advisory `admin-post` + actor/key →
tra lại → callback ghi → đọc neo đã ghi → COMMIT. Bận trả 409 REQUEST_IN_PROGRESS
với Retry-After: 1. Cùng actor/key khác hash trả 409 IDEMPOTENCY_MISMATCH, không
Retry-After. Lần đầu/replay đều trả OperationReceipt HTTP 200 khi caller có endpoint.
Replay giữ nguyên operationId/result, không đọc tồn/trạng thái hiện tại để sửa kết
quả, không chạy lại callback. UI cần GET để xem trạng thái mới nhất.

Callback phải ghi đúng một neo bằng event nghiệp vụ hoặc operation_noop hợp lệ.
Thiếu neo/sai hash làm rollback; key mới cho no-op có neo mới, không outbox. UNIQUE
actor/key được xử lý chỉ sau afterCompletion xác nhận rollback: kiểm quyền lại,
đọc/so kết quả thắng. Nếu không có kết quả thắng thì giữ lỗi gốc. Không bắt UNIQUE
source/bank ref/order number/PK làm replay; xử lý tranh chấp receipt ngân hàng
thuộc service Payments ở gói sau. Unknown commit/rollback lỗi không đọc đoán thắng.
Không gọi trong transaction cha vì không thể rollback toàn tác vụ trước fallback.

Deadlock dùng Transactions đã kiểm: tối đa ba transaction, giữ command/key/hash,
pause ngoài transaction. Callback chỉ làm việc DB có thể rollback; UUID mới trong
lượt đã rollback không phải tác dụng thành công. Non-DB effects dùng afterCommit;
lỗi sau commit không đảo kết quả. Không xóa neo hoặc sinh key mới để chữa timeout.

[Hồ sơ kiểm WP01-13](wp01-13-verification.md) phân biệt harness thành phần và
kiểm qua các POST nghiệp vụ thật ở WP03–WP06.

WP01-14 dùng thêm `CanonicalOperation.commandLine` với framing CLI riêng cho lệnh vận hành; không đổi hash POST hoặc phạm vi route HTTP. Xem [nhập tồn đầu](initial-stock.md).
