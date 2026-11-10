# WP01-03 — Hồ sơ kiểm validation, lỗi và log

Ngày: **10/11/2026**. Branch: `feat/wp01-03-validation-errors`.
Trạng thái: **hoàn thành**, [PR #4](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/4)
merge vào main `2d9e340`; CI PR `42780618953` và main `42781075337` đạt.
Local: **32 unit + 40 integration, 0 lỗi/bỏ qua**. Truy vết Cane-MVP commit local
`da32da5`, không push theo chỉ dẫn chủ dự án.

## Nguồn và thay đổi

API §1–2/9; bảo mật §5–7; thiết kế dữ liệu §4; kịch bản WP01 §3.10/3.16.
Đối chiếu 16 defaults của V001 và miền DB trước khi viết fixture/validator.

- Primitive/string/money/Instant, JSON nghiêm ngặt, UUID chuẩn, validator settings
  đúng chủ quản Admin; không thêm SQL/transaction/settings endpoint sớm.
- Lỗi chung MVC/Security/firewall, requestId server và no-store, body 65.536 byte
  trên cả header lẫn luồng. Log route mẫu và MDC, không exception/đầu vào thô.
- Giữ production deny-all cho đến task auth; probe/controller/rule thử chỉ trong
  test classes. V001, grant và stack không đổi.
- [Hướng dẫn sử dụng](input-errors.md) ghi ranh giới và phần caller phải thực hiện.

## Kiểm thực tế

```powershell
.\mvnw.cmd -B -ntp spotless:apply test failsafe:integration-test failsafe:verify '-Dit.test=ApiInputIT'
pwsh -NoProfile -File scripts/Test-Backend.ps1
```

Lượt tập trung cuối đạt **32 unit + 8 HTTP integration**, 0 failures/errors/skipped;
Maven 40 giây, kết thúc **14:57:16 GMT+7**. PostgreSQL 18.6/Redis 8.10.3 thật,
phiên bản/image theo bộ khóa. Các lỗi phát hiện trong khi làm đã sửa trước lượt này:
assert JSON khác nội bộ IntNode/LongNode; UUID rút gọn được editor mặc định nhận;
API feature enum của Jackson 3 nằm ở EnumFeature.

Sau rà soát log, chuyển method/route/status/errorCode/durationMs/component sang
thuộc tính JSON riêng bằng SLF4J fluent, giữ MDC requestId. Chạy lại tập trung
đạt **32 unit + 8 HTTP integration**, 0 lỗi/bỏ qua, **15:04:40 GMT+7**, Maven
42,6 giây. Kiểm sentinel cả message, key-value và throwable; thêm body SQL injection
thử và chờ request log hoàn tất bằng latch, không phụ thuộc thời điểm response đến.
Đã parse console JSON thực để xác nhận các thuộc tính không nằm gộp trong message.

Runner cuối sau thay đổi log đạt **32 unit + 40 integration, 0 failures/errors/skipped** và build
executable JAR, Maven **4 phút 13 giây**, kết thúc **15:09:22 GMT+7**. Đã đối chiếu
XML Surefire/Failsafe; JAR không có probe/test controller. Project
`cane-prep02-check-eab24eed3283` đã dọn container/network/volume; không còn container
Testcontainers mang nhãn thử hoặc image mới so inventory. Log ngoài Git tại
`.local/wp01-03-verified.log`. V001 không đổi so main.

Nhóm kiểm:

1. Password không trim và miền Unicode; username/email/phone/note/UUID/key/SKU.
   Tiền/int ở biên, số âm/lẻ/quá lớn, cộng/nhân vượt miền, UTC/ngày nghiệp vụ/hạn.
2. Đủ 16 settings; null/rỗng chuẩn bị; nested normalize/length/type/allowlist,
   ward/method trùng, enum lạ; bank_transfer/bank_details ghép cuối ở hai chiều;
   không sửa input/khóa bỏ qua. HTTPS, notify tối thiểu một kênh, manual không auto.
3. HTTP qua Tomcat thật: duplicate ở nhiều mức, trailing JSON, scalar coercion,
   enum số/lạ, UUID rút gọn/base64, Bean Validation, path/type và body rỗng.
4. Đúng 65.536 byte được nhận; thêm một byte bị 413; chunked UTF-8 vượt hạn bị
   413. Content-Type sai/thiếu bị 415; không chỉ kiểm số ký tự/header.
5. Status/body/header đúng cho 400/422/413/415/404/405/503 và Security/CSRF 403;
   firewall 400; Accept HTML vẫn lỗi JSON, giữ Allow; không JSESSIONID.
6. RequestId UUID do server sinh, header khớp body; log MDC khớp phản hồi, route
   mẫu không URL/query; capture root log cả throwable không có sentinel riêng của
   cookie/CSRF/Bearer/địa chỉ/query hoặc SQL trong lỗi giả lập. Lỗi không echo input.

## Giới hạn

Đây là cơ chế nền, không nghiệm thu toàn bộ T72 hoặc NFR-005/006/009. DTO/SQL và
nghiệp vụ tiền/tồn, login/session/CSRF thật, settings API/khóa 16 dòng, proxy/HTTPS,
audit/neo/outbox và privacy trên các route dùng nền còn kiểm tại task/WP tương ứng.
Không suy CI hoặc HTTP local thành smoke staging.
