# Đầu vào, lỗi HTTP và log nền

Nguồn: Cane-MVP API §1–2/9, bảo mật §5–7, kịch bản WP01 §3.10/3.16.
Đây là thành phần WP01-03; endpoint nghiệp vụ được mở theo các task/WP tương ứng.

## Ranh giới và cách dùng

- `bootstrap/JsonConfiguration`: Jackson 3 dùng chung, từ chối key JSON trùng,
  trường DTO lạ, JSON nối thêm, ép kiểu scalar, số lẻ sang integer, enum bằng số.
  UUID JSON phải dạng chuẩn; Instant xuất ISO UTC, giữ độ chính xác.
- `support/validation`: chuẩn hóa primitive và miền số/chuỗi/thời gian. DTO
  allowlist theo endpoint; gọi validator trước ghi dữ liệu. Nhận số bằng kiểu đủ
  rộng trước kiểm miền, không cắt/tràn sang `int`/`long`. Kết quả tiền cũng trong
  giới hạn JSON chính xác `9_007_199_254_740_991` VND.
- `InputNormalizer`: trim trừ password; email/username lowercase, phone giữ `+`
  và số 0 đầu; note trắng thành null; UUID, key ASCII và SKU duy nhất. Password
  tạo mới 15–128 ký tự; không áp miền tạo mới để phân biệt lỗi đăng nhập.
- `TimeInputs`: UTC hậu tố Z; chỉ receipt `transactionAt` chuẩn hóa tới giây.
  Session/link giữ Instant; thời lượng dương phải tính được hạn trong miền DB.
  Ngày nghiệp vụ dùng Asia/Ho_Chi_Minh.
- `admin/application/SettingsValidator`: allowlist đủ 16 khóa và cấu trúc lồng;
  chuẩn hóa patch, ghép trạng thái cuối, kiểm tương quan chuyển khoản/tài khoản.
  SQL/transaction không nằm trong validator. Use case PUT settings ở WP06 phải
  khóa/đọc **cả 16 dòng theo key tăng dần** trước `validateAndMerge` và chỉ cập
  nhật khóa được gửi. Không tự ghi đè cấu hình không gửi.

Giữ null/rỗng hợp lệ khi chuẩn bị cửa hàng; không giả thông tin mở bán. Nhánh manual
từ chối `bank_auto_confirm=true` đến khi nhánh tự động được triển khai/nghiệm thu.
Không tự kiểm ward bằng danh sách hành chính đoán hoặc tự đổi mã ngân hàng.
Ngoại lệ loại bỏ trường giả giá/phí/tổng chỉ áp dụng quote/place ở WP03, tại adapter
riêng trước DTO/hash; không nới unknown-field cho toàn mapper.

## HTTP và lỗi

`RequestTraceFilter` bao Security và MVC: sinh UUID requestId phía server, trả
X-Request-ID và no-store. Không dùng requestId do client đưa vào. `ApiInputBinding`
kiểm UUID path/query chuẩn, tránh editor mặc định chấp nhận UUID rút gọn.

`JsonBodyFilter` sau Security kiểm application/json khi có body, chặn Content-Length
trên 65.536 byte và giới hạn chính luồng đọc; không chỉ tin header và không cache
nguyên body. Hạn tính byte UTF-8, không phải số ký tự. Proxy phải áp cùng hạn body
khi triển khai; filter không thay giới hạn tài nguyên ở proxy.

`ApiErrors` và `ApiExceptionHandler` dùng chung shape:

```json
{"error":{"code":"INVALID_INPUT","message":"Dữ liệu gửi lên không hợp lệ.","fieldErrors":{},"itemErrors":[],"requestId":"<UUID>"}}
```

| Trường hợp nền | Status/code |
|---|---|
| JSON, kiểu, trường lạ, header/path/query, firewall sai | 400 / INVALID_INPUT |
| Miền số/chuỗi và Bean Validation | 422 / INVALID_INPUT |
| Body vượt hạn / media sai | 413 / 415, INVALID_INPUT |
| Tài nguyên MVC không có | 404 / NOT_FOUND |
| Phương thức không hỗ trợ | 405 / INVALID_INPUT, giữ Allow |
| Lỗi hạ tầng/bất biến ngoài dự kiến | 503 / SERVICE_UNAVAILABLE |

Thông báo cố định, không xuất parser exception, rejected value, SQL, stack hoặc
request body. FieldErrors chỉ dùng đường dẫn đã biết; key settings giữ snake_case,
trường lồng camelCase. Security dùng cùng writer kể cả firewall/CSRF.
Hiện production vẫn deny-all 403; auth/CSRF/session hoàn thiện ở task 09–11.

## Log và vận hành

Log console JSON hiện có giữ MDC requestId. Mỗi request ghi method allowlist,
route **mẫu của MVC** hoặc `unmapped`, status/errorCode, durationMs, component.
Không ghi URI/query, body, cookie, Authorization, CSRF, HMAC hay exception thô.
Lỗi bất ngờ ghi cảnh báo cố định gắn requestId; SQL bind logging tắt. Khi xử lý sự
cố, tra requestId và trạng thái hệ thống, không bật dump đầu vào/SQL để lấy dữ liệu
nhạy cảm. Cảnh báo kỹ thuật không thay audit nghiệp vụ ở task 12.

Kiểm nền dùng controller/rule chỉ nằm trong `src/test/`; không thêm endpoint thử
vào JAR. Xem [hồ sơ WP01-03](wp01-03-verification.md) để biết bằng chứng/phạm vi.
