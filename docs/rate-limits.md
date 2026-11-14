# Redis và giới hạn tần suất

WP01-08 cung cấp bộ đếm cho năm nhóm, chưa triển khai cache prices hay các API
nghiệp vụ công khai. Nguồn: thiết kế kỹ thuật §6, bảo mật §6 và nhóm kiểm 3.14 trong
repo `cane-mvp/`.

| Nhóm | Ngưỡng/IP trong 60 giây |
|---|---:|
| login | 5 |
| quote | 30 |
| place | 10 |
| guest-read | 60 |
| cancel-request | 5 |

`RateLimiter.check(group, canonicalIp)` tăng đúng một lần cho mỗi lần gọi. Caller
phải gọi một lần/request, trước thao tác tốn tài nguyên. Hiện filter gắn duy nhất
POST `/api/v1/admin/auth/login`, trước Spring Security/CSRF; đăng nhập thật tiếp nối
ở WP01-11. Các nhóm khác được kiểm bằng bộ gọi nội bộ, gắn route ở gói tương ứng.
Worker không tạo filter/bộ đếm này.

Key `cane:<env>:limit:<group>:<sha256-ip>` tách môi trường/nhóm/IP. Lua INCR/PEXPIRE/
PTTL nguyên tử: cửa sổ bắt đầu từ lần gọi đầu, lần gọi sau không gia hạn. Script
khai báo `#!lua` để Redis từ chối cả script khi OOM, kể cả counter đã tồn tại;
không dùng `allow-oom`. Xem [quy tắc Lua/flags của Redis](https://redis.io/docs/latest/develop/interact/programmability/lua-api/).
Quá ngưỡng trả 429 `RATE_LIMITED`, `Retry-After` là TTL còn lại làm tròn lên, tối
thiểu một giây; lỗi giữ JSON/requestId/`Cache-Control: no-store` chung.

## Khi Redis lỗi

Timeout/kết nối/OOM chuyển sang `ProcessRateCounter`, cùng ngưỡng/cửa sổ, cập nhật
nguyên tử theo key và dọn key hết hạn. Không retry ở application sau kết quả Redis
không chắc chắn. Timeout/connect hiện một giây mỗi loại theo `application.yml`;
Lettuce có cơ chế kết nối lại, không có vòng lặp retry nghiệp vụ.

Chỉ log thông báo cố định khi chuyển fallback/phục hồi, không IP/hash/exception có
thể chứa dữ liệu. Bộ đếm là dữ liệu bỏ được: Redis rỗng, process restart hoặc đổi
backend đếm có thể mở cửa sổ mới, không cam kết giới hạn liên tục qua các lần đổi.
MVP chỉ một API instance và proxy giới hạn độc lập. Chưa được mở nhiều API instance
trước khi thiết kế lại giới hạn tổng trong chế độ Redis lỗi.

## Proxy và địa chỉ IP

Mặc định `CANE_TRUSTED_PROXIES` rỗng: dùng peer TCP, bỏ qua X-Forwarded-For. Khi có
proxy thật, khai báo **đúng IP/CIDR số của proxy**, cách nhau dấu phẩy, ví dụ cấu hình
thử `127.0.0.1/32,10.0.0.0/24`. Không chép dải ví dụ vào staging/production.
Giá trị sai làm API không khởi động. Không dùng hostname, zone IPv6 hoặc port.
Nếu dùng `Run-Local.ps1`, đặt biến trong process environment của terminal trước khi
gọi script; wrapper không đọc biến proxy từ `.local/dev.env`.

Resolver ghép các dòng X-Forwarded-For, duyệt từ phải sang trái qua các proxy đã
tin cậy rồi dừng ở peer đầu không tin cậy. Chuỗi lỗi/quá 32 phần hoặc 4096 ký tự
dùng peer TCP; IPv4/IPv6 được chuẩn hóa trước hash. Giữ `forward-headers-strategy:
none` để container không tự tin header trước resolver. Proxy phải xóa forwarding
do Internet tự khai và chỉ chuyển chuỗi mình kiểm soát; backend chỉ nhận traffic
từ proxy trên mạng đã giới hạn. Kiểm cấu hình proxy/HTTPS thật thuộc WP01-16.

## Redis và kiểm tra

Local/test dùng script hiện có: Redis riêng, namespace theo môi trường, ACL runtime
chỉ lệnh cần thiết, default user off, noeviction, maxmemory 64 MiB, không persistence.
Cổng Compose chỉ loopback; staging/production phải giữ PostgreSQL/Redis private.
Không thêm FLUSH/KEYS/SCAN/CONFIG hoặc mở namespace để kiểm thử.

Chạy tập trung từ gốc backend:

```powershell
.\mvnw.cmd -B -ntp verify '-Dit.test=RateLimitIT'
```

`RateLimitIT` chạy Redis/PostgreSQL/Tomcat thật, gồm concurrent requests, TTL, 429,
forwarding giả, timeout bằng pause container riêng, connection refused và đầy bộ
nhớ noeviction. Test không bỏ qua khi Docker thiếu. [Hồ sơ kiểm](wp01-08-verification.md)
ghi kết quả đúng commit; toàn bộ runner tại `scripts/Test-Backend.ps1`.
