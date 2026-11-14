# Link HMAC một đơn — WP01-15

Nguồn: hợp đồng §8; security §4; dữ liệu §4/9; test cases §3.9/3.16. Thành phần
nền trong Orders, chưa mở guest/resend/rotate HTTP hoặc gửi email WP05.

## Cấu hình và secret

Cả API và worker bắt buộc cùng `LINK_SECRETS` (JSON object kid → secret base64url
không padding) và `LINK_ACTIVE_KID` (một key đã có trong map). Kid [A-Za-z0-9_-],
1–32 ký tự. Mỗi secret giải mã ít nhất 32 byte ngẫu nhiên; tạo mới bằng CSPRNG,
khác session/CSRF/provider credential. Encoding base64url là lựa chọn cấu hình
triển khai; wire token/message HMAC giữ nguyên đặc tả. Map strict duplicate/trailing,
không trường lồng/giá trị không phải string, tổng cấu hình tối đa 64 KiB.
Không có key mặc định hoặc fallback. Sai/thiếu cấu hình dừng cả API/worker trước
HTTP/job; thông báo cố định, không giữ exception parser/decoder chứa secret.

Lưu key ring ngoài Git/SQL/FE/log, dùng secret manager hoặc file riêng được bảo vệ
quyền. Không đưa giá trị vào argument lệnh, ticket/PR hay ảnh chụp. `.env.example`
chỉ placeholder; `Run-Local.ps1` kế thừa hai biến từ môi trường tiến trình, không
đọc chúng từ dev.env. Script seed/staff/nhập tồn không cần HMAC vì là context vận
hành riêng, không khởi động runtime API/worker.

Ví dụ tạo **một map mới cho local**, không in secret và không ghi đè file có sẵn.
Đổi kid nếu tạo khóa khác; dữ liệu này không tự dùng cho staging/production:

```powershell
$kid = 'local-20261114'
$keyBytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
try {
    $encoded = [Convert]::ToBase64String($keyBytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    $keyMap = @{ $kid = $encoded } | ConvertTo-Json -Compress
    $path = Join-Path (Get-Location) '.local/link-keys-local.json'
    $stream = [IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {
        $writer = [IO.StreamWriter]::new($stream,[Text.UTF8Encoding]::new($false))
        try { $writer.Write($keyMap) } finally { $writer.Dispose() }
    } finally { $stream.Dispose() }
} finally { [Array]::Clear($keyBytes) }
$env:LINK_SECRETS = [IO.File]::ReadAllText($path)
$env:LINK_ACTIVE_KID = $kid
```

Tạo thư mục riêng/quyền file trước dùng; Unix chỉ user vận hành đọc/ghi, Windows
kiểm ACL theo tài khoản vận hành. Không dùng ví dụ một-key để thay ring đã có khi
xoay khóa: thao tác đó có thể làm mất các key cũ. Không chạy lại helper để chữa
runtime lỗi bằng cách thay secret ngẫu nhiên.

## Token, quyền và thời gian

Wire ASCII tối đa 512 byte, đúng sáu phần:
`v1.kid.orderId.linkVersion.expiresEpochMillis.macBase64url`.
MAC HMAC-SHA256 của đúng message security §4.1; thuật toán cố định, không đọc URL/
thuật toán do token khai. UUID lower-case canonical; version bigint dương và expiry
decimal không 0 đầu; MAC đúng 32 byte/43 ký tự base64url canonical không padding,
kể cả pad bits. So MAC dùng MessageDigest.isEqual. Không trim token hoặc chấp nhận
UUID/decimal tương đương nhưng khác biểu diễn.

`OrderLinkState.millisecondInstant` chuẩn hóa **lúc quyết định hạn mới**, trước
lưu và ký cùng một Instant. Repository giữ nguyên giá trị DB kể cả microseconds;
không làm tròn lúc authorize. Bộ ký từ chối hạn chưa chính xác milliseconds.
`OrderLinkService` kiểm chữ ký/hạn UTC trước SQL, đọc đúng ID và so version/Instant
chính xác, rồi kiểm lại Clock sau SQL/đợi khóa. Bằng hạn là hết; không tự gia hạn.
Mọi token sai, hết hạn, không có đơn, version/hạn DB lệch dùng một
InvalidOrderLinkException và HTTP 404 ORDER_LINK_INVALID/no-store/requestId.
DB kết nối/quyền/schema lỗi vẫn là lỗi kỹ thuật 503 theo formatter chung.

`authorizeRead` chỉ xác nhận quyền một đơn, không xuất DTO/PII. Khi ghi, caller sở
hữu write transaction và gọi `authorizeWrite` **trước khóa payment/SKU**; nó SELECT
FOR UPDATE đơn và kiểm lại token/version/hạn dưới khóa. Kết quả GET trước đó không
đủ cấp quyền ghi sau rotate. Không gọi trong transaction read-only hoặc ngoài TX.
Caller vẫn phải kiểm status/guard nghiệp vụ, DTO guest và tác dụng phụ của use case.

`mintCurrent(orderId)` chỉ dành caller server/worker đã có quyền: đọc version/hạn
hiện hành và ký active kid; không dùng orderId/mã đơn làm guest authentication.
Không dựng lại từ operation_result/event lịch sử. Hết hạn không mint; worker tương
lai xử lý thông tin sự kiện/hỗ trợ và báo Owner theo WP05. Token trả trong wrapper
Issued.toString redacted; chỉ đọc value khi cần truyền link theo contract. Thành
phần hiện không dựng/lưu URL, không gọi Redis hoặc ghi event/outbox/DB token.

## Xoay khóa và thu hồi

1. Sinh key mới bằng CSPRNG với kid mới, **thêm** vào ring và giữ nguyên byte key cũ.
2. Phân phối ring có cả key cũ/mới cho API và worker, kiểm config/readiness và cả hai
   xác minh được token ký bởi nhau. Chưa ký kid mới khi bên xác minh chưa có key đó.
3. Đổi active kid của cả hai tiến trình rồi triển khai lại. LinkVersion/hạn không đổi;
   link cũ còn hạn vẫn hợp lệ, token dựng lại có thể đổi kid/MAC.
4. Chỉ bỏ key cũ khi mọi link còn phục vụ ký bởi nó đã hết hạn; tính cả các lần Owner
   gia hạn. Không mặc định cứ chờ 90 ngày hoặc xóa ngay sau deploy. Kiểm kê và backup
   secret riêng có mã hóa/quyền, thử restore cùng DB theo kế hoạch vận hành.

Thu hồi link là tăng link_version và gia hạn theo thao tác Owner được phép; version
cũ bị từ chối sau commit, rollback không thu hồi. Gói WP05 sẽ ghi thay đổi cùng
event/neo/outbox và chống lặp; WP01 chỉ kiểm cơ chế bằng harness DB/transaction,
không mở lệnh cập nhật link bỏ qua audit. Lộ key cần quy trình sự cố: thay key,
thu hồi link ảnh hưởng/cấp lại; nếu buộc bỏ kid ngay thì link kid cũ không tương thích.
Không tự động xóa key, hot reload, lưu token hoặc sửa version vì đổi active kid.

Kiểm foundation bằng PostgreSQL/key ring fixture và GET probe formatter chỉ ở test;
chưa phải API guest, browser fragment, email worker, backup restore hoặc HTTPS thật.
