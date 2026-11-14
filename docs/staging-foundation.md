# WP01-16 — Kiểm local, smoke HTTPS và triển khai nền

Ngày 14/11/2026: **chỉ hoàn thiện/kiểm phần độc lập ở local** theo yêu cầu mới nhất
của chủ dự án. Chưa có tài khoản cloud, máy chủ hoặc domain thật. Đã chọn hướng
AWS Lightsail Linux 4 GB; chưa tạo tài nguyên hay phát sinh phí. Không coi file
cấu hình, profile staging hoặc TLS fixture local là nghiệm thu staging.

Ngân sách đã cho: staging 10–25 USD/tháng, production tối đa 100 USD/tháng. Gói
Lightsail 4 GB/2 vCPU/80 GB/IPv4 niêm yết 24 USD/tháng tại thời điểm tra cứu;
thuế/domain/backup/transfer vượt gói chưa nằm trong con số này. Kiểm lại tổng giá
theo vùng trước tạo máy. [Nguồn AWS](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-bundles.html).
Chưa chọn hạ tầng production hoặc xác nhận RPO/RTO từ phương án staging này.

## 1. Phạm vi và file thực thi

Một API và một worker dùng **cùng JAR**, profile loại trừ nhau; PostgreSQL/Redis
chỉ loopback trên VM dự kiến. Nginx trên cùng host nhận HTTPS, chuyển `/api/` sang
API loopback và dành `/` cho FE. FE/UI và các route nghiệp vụ chưa được nghiệm thu
ở WP01; khi FE chưa triển khai, không dùng trang `/` làm tiêu chí smoke nền.

| File | Vai trò |
|---|---|
| [runner](../scripts/Test-Backend.ps1) | Parse mọi script PowerShell, toolchain/Compose, clean verify, cleanup riêng |
| [báo cáo](../scripts/operations/Export-Verification.ps1) | XML unit/IT, SHA Git/JAR/V001, worktree dirty; không raw test output/secret |
| [kiểm cấu hình local](../scripts/operations/Test-StagingConfiguration.ps1) | DB/Redis thật từ template staging, cổng loopback ngẫu nhiên, migrate/grants/JAR và fault checks; tự dọn project riêng |
| [smoke HTTPS](../scripts/operations/Smoke-Https.ps1) | Ba auth API, role/DTO, CSRF/origin, cookie/hạn/logout và header; chứng chỉ phải được trust mặc định |
| [Compose](../scripts/operations/staging/compose.yaml), [role init](../scripts/operations/staging/init-roles.sql), [Redis](../scripts/operations/staging/redis-start.sh) | Chỉ hạ tầng staging mới; không migrate/seed trong startup |
| [khởi động infra](../scripts/operations/staging/Start-Infrastructure.sh) | Chặn thiếu/trùng key, placeholder/secret sai định dạng/trùng nhau trước tạo container; không source env |
| [infra env](../scripts/operations/staging/infrastructure.env.example), [API env](../scripts/operations/staging/api.env.example), [worker env](../scripts/operations/staging/worker.env.example) | Placeholder, ba loại file khác nhau; không chứa secret thật |
| [API unit](../scripts/operations/staging/cane-api.service), [worker unit](../scripts/operations/staging/cane-worker.service) | systemd user riêng, retry sau 30 giây không hết lượt, cùng artifact, không root |
| [Nginx](../scripts/operations/staging/nginx.conf.template), [API headers](../scripts/operations/staging/api-security-headers.conf) | TLS/origin/forwarding/no-cache/header, edge rate và JSON lỗi proxy |

Không dùng `compose.yaml`, `.local/dev.env` hoặc `scripts/dev/init-roles.sql` cho
staging: chúng phục vụ local/test và DB `cane_local`. Biến infra staging có prefix
`CANE_STAGING_`, tách biến Flyway `CANE_MIGRATION_*`; Compose ánh xạ sang tên bên
trong container. API/worker chỉ nhận credential riêng của mình, không infra/admin/
migrator. File systemd dùng cú pháp EnvironmentFile; JSON key ring phải giữ dấu
quote như ví dụ. Không source file hoặc xuất `docker inspect/config` có secret.

## 2. Chạy lại phần không cần cloud

Từ repo backend, với JDK/Maven/Docker/Compose đúng bộ khóa và PowerShell 7:

```powershell
pwsh -NoProfile -File scripts/Test-Backend.ps1
pwsh -NoProfile -File scripts/operations/Test-StagingConfiguration.ps1
```

Runner đầu dùng Compose riêng và Testcontainers thật; nếu cổng local 55432 bị dành
trước, đặt `CANE_POSTGRES_PORT` thành cổng loopback còn trống trước chạy. Không đổi
image/toolchain hoặc bỏ IT để làm xanh. Báo cáo `target/foundation-verification.json`
phân biệt revision và worktree dirty; CI lưu cùng XML/effective POM/dependency tree
và chỉ result.json đã che dữ liệu của lượt kiểm cấu hình, không env/log bí mật.
Các nhánh/phạm vi nền tại [hồ sơ WP01-16](wp01-16-verification.md).

Lệnh cấu hình tạo project `cane-wp01-16-check-*`, secret fixture mới trong `.local`,
không dùng biến `CANE_STAGING_*` có sẵn của người vận hành. Nó kiểm migration rồi
grant đúng script, chạy JAR hai mode bằng DB role riêng; Redis pause phải chuyển
process fallback, PostgreSQL stop phải trả 503 khi login chạm SQL. Fixture username
không tồn tại, không tạo staff/đơn hoặc nạp giá/tồn giả. HTTP loopback gửi CSRF cookie
tường minh để kiểm server; **không** chứng minh browser chấp nhận Secure cookie.
Cuối lượt dừng đúng hai process đã tạo, dọn đúng project/volume và xóa file env thử;
giữ report/log đã che dữ liệu. Không prune Docker hoặc xóa volume khác.

## 3. Đầu vào cần cung cấp sau khi phần local hoàn tất

Theo thứ tự; không gửi mật khẩu, access key, private key hoặc mã MFA trong chat:

1. Tự tạo tài khoản AWS, hoàn tất billing/MFA; chọn Lightsail 4 GB Linux/Ubuntu
   cùng region. Kiểm tổng giá/thuế và budget alert trước xác nhận tài nguyên tính phí.
2. Tạo máy, gắn static IPv4; ghi region, OS/version, IP. Chỉ mở SSH từ IP vận hành
   cụ thể; 80/443 cho HTTPS/ACME. Không mở 8080/3000/55432/16379 hoặc DB/Redis ra ngoài.
3. Mua/chọn domain thật, chốt **một** hostname staging; trỏ A về static IP. Nếu
   chưa cấu hình IPv6, không để AAAA trỏ máy khác. Cung cấp hostname đã chốt.
4. Cấp cách truy cập tối thiểu phục vụ triển khai; cung cấp username SSH, port và
   đường dẫn key lưu an toàn trên máy làm việc hoặc cơ chế truy cập đã cấp. Xác minh
   host fingerprint ngoài kênh SSH trước lần kết nối đầu; không bỏ host-key checking.
5. Có nơi giữ secret riêng và bản sao HMAC ngoài máy VM; chuẩn bị hai staff thử
   Owner/Operator bằng prompt kín. Chưa cần dữ liệu mở bán thật để smoke auth.

Sau đó mới có thể cấp chứng chỉ công khai, deploy, smoke thực và ghi quyết định
nghiệm thu phần nền. `__STAGING_HOST__`, `tenmien.com` và `cane.local.test` trong
template/fixture không phải domain đã được cấp hoặc mua.

## 4. Trình tự triển khai khi đã có quyền/máy/domain

Đây là lệnh/quy trình chuẩn bị, **chưa thực thi trên cloud**. Ghi OS, JDK 25.0.5+8,
Docker 29.9.2/Compose 5.6.1 và package Nginx/chứng chỉ thực tế. Lấy JDK theo URL và
SHA-256 đã khóa trong [CI](../.github/workflows/ci.yml), đặt `/opt/cane/jdk`; không
build Maven trên VM nhỏ hoặc đổi Java để chạy artifact. Nginx được kiểm syntax
local; phiên bản package trên máy thật còn phải ghi/kiểm trước bật.

1. Build/CI đúng commit; giữ JAR/checksum, V001/checksum và bản tương thích trước.
   Chép artifact vào `/opt/cane/releases/<commit>/cane-api.jar`, code/template tương
   ứng cùng release; không chép `target/test-classes`, `.local/dev.env` hoặc fixture.
2. Tạo system user `cane-api`/`cane-worker` không shell; root giữ quyền ghi release,
   `/opt/cane/current` trỏ release đã chọn. Tạo `/etc/cane/staging`, file env root
   0600, không có secret trong unit/argument. Mỗi mật khẩu infra là CSPRNG 32 byte
   hex khác nhau; HMAC là key ring CSPRNG riêng, giữ các kid cũ theo [runbook](order-links.md).
3. Chép thư mục template staging vào release và tạo infrastructure.env ngoài Git.
   Wrapper kiểm đúng năm secret hex 64 ký tự khác nhau, không source env hoặc nhận
   override process vô tình; entropy vẫn do CSPRNG, validator không tự chứng minh.
   Không xuất cấu hình đã resolve:

   ```bash
   sh /opt/cane/current/scripts/operations/staging/Start-Infrastructure.sh check /etc/cane/staging/infrastructure.env
   sh /opt/cane/current/scripts/operations/staging/Start-Infrastructure.sh start /etc/cane/staging/infrastructure.env
   ```

   Init role chỉ chạy trên volume mới. Có volume/schema lạ thì dừng, không xóa để
   chạy lại init. Kiểm firewall và loopback bindings trên host trước phục vụ.
4. Giữ API/worker dừng khi migrate. Có thể dùng SSH tunnel local 55433 → VM
   127.0.0.1:55432 từ terminal vận hành; credential qua prompt/môi trường process,
   không argument. Với `CANE_MIGRATION_URL=jdbc:postgresql://127.0.0.1:55433/cane_staging`,
   user `cane_migrator` và password riêng, chạy `scripts/database/Migrate-Core.ps1`.
   Pre-validate `*:pending` chỉ versioned core; post-validate đầy đủ, không repair/
   baseline/clean. Sau đó qua tunnel:

   ```powershell
   psql --host 127.0.0.1 --port 55433 --username cane_migrator --dbname cane_staging --password -v ON_ERROR_STOP=1 -v cane_api_role=cane_api -v cane_worker_role=cane_worker --file scripts/database/Grant-Runtime.sql
   ```

5. Staff CLI từ terminal tương tác dùng cùng JAR và `CANE_OPERATIONS_DB_*` trỏ
   tunnel/DB đích; xem [staff](staff-commands.md). Tạo Owner/Operator thử, không
   password mặc định/argument/env/file. Không nhập catalog/tồn giả vào staging:
   seed/release manifest thật khi đã được xác nhận theo [seed](catalog-seed.md) và
   [nhập tồn](initial-stock.md); WP01 đã kiểm hai cơ chế này bằng fixture local.
   Khi dùng CLI nhập tồn, đặt `CANE_API_DB_USER`/`CANE_WORKER_DB_USER` bằng đúng
   tên hai role đã truyền cho `Grant-Runtime.sql`; đây là tên role không bí mật,
   không cấp credential runtime cho tiến trình vận hành. Thiếu hoặc sai mapping
   phải sửa cấu hình trước chạy, không đổi tên để né kiểm phân tách credential.
6. Điền API/worker EnvironmentFile, cùng LINK_SECRETS/LINK_ACTIVE_KID; API origin
   chính xác `https://<hostname>`. Chỉ khi proxy thật ở cùng host mới dùng
   `127.0.0.1/32` trong template; topology khác phải xác định lại peer/network trước
   chạy. Chép hai unit, `systemctl daemon-reload`, rồi enable/start hai service.
   Check startup `database=READY`, mode đúng và worker không có listener HTTP.
   Hai unit dùng `StartLimitIntervalSec=0`, `Restart=on-failure`, `RestartSec=30`:
   DB chưa sẵn sàng vẫn làm startup thất bại, systemd tiếp tục thử sau 30 giây kể
   cả khi lỗi kéo dài; không mất lượt retry trước khi DB phục hồi. `systemctl stop`
   vẫn dừng service. Nếu cấu hình/schema/quyền sai, dừng service, sửa nguyên nhân
   rồi start lại; theo dõi lỗi lặp, không coi việc process restart là readiness.
   Linux CI kiểm chính sách này bằng
   [probe systemd](../scripts/operations/Test-SystemdRecovery.sh): thay executable/
   đường dẫn/user bằng fixture, rút delay còn 1 giây, vượt ba lần lỗi rồi phục hồi
   và kiểm stop thủ công. Probe không chạy JAR và không chứng minh VM staging.
7. Trỏ DNS, cấp chứng chỉ từ CA công khai bằng ACME với webroot `/var/www/cane-acme`;
   bật site HTTP challenge trước lấy cert, sau đó mới site HTTPS. Thay hostname
   trong Nginx template, đặt API snippet đúng đường dẫn. Kiểm `nginx -t` trước
   reload; kiểm renewal timer và dry-run. Không vượt cảnh báo trình duyệt hoặc dùng
   `-k`/SkipCertificateCheck. HSTS chỉ hostname này, không includeSubDomains.
8. Proxy xóa forwarding client tự khai, giữ Origin/CSRF, không cache, không retry
   upstream POST. Edge 20 request/giây/burst 20 là guard thô, không thay ngưỡng
   login 5/60s trong API. Proxy lỗi trả JSON/no-store/headers; ID proxy là random
   hex của Nginx, ID ứng dụng là UUID. Log proxy chỉ status/upstreamStatus/ID/duration;
   native error log tắt vì có thể chứa URL thô. Dùng log app/ID và systemd để chẩn
   đoán; theo dõi tỷ lệ 429/503, không chỉ xem process đang chạy.

Tham chiếu kỹ thuật: [Nginx proxy](https://nginx.org/en/docs/http/ngx_http_proxy_module.html),
[limit_req](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html),
[systemd execution](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html).
Pool/timeout hiện là trị số hữu hạn đã kiểm, chưa có đo tải trên Lightsail hoặc SLA.

## 5. Smoke/demo và ghi nhận sau deploy

Từ máy vận hành, mỗi lần dùng CookieContainer mới; chỉ tài khoản staging thử:

```powershell
pwsh -NoProfile -File scripts/operations/Smoke-Https.ps1 -Origin https://<hostname> -Username <test-owner> -ExpectedRole owner
pwsh -NoProfile -File scripts/operations/Smoke-Https.ps1 -Origin https://<hostname> -Username <test-operator> -ExpectedRole operator
```

Mật khẩu nhập kín sau khi anonymous me đã xác nhận TLS/headers. Không transcript/
debug dump. Không retry login tự động; sai credential dừng để tránh tăng khóa.
Script từ chối HTTP/userinfo/path/query/fragment, redirect, chứng chỉ không trust;
report mới không ghi đè file cũ. Chỉ lưu role, step/status/requestId/thời gian/kết
quả; không username/response/password/cookie/CSRF. Nó kiểm bootstrap 401, origin/
CSRF 403, login, DTO/me/hạn không gia hạn, token CSRF cũ bị chặn, logout/401/logout
lặp. Lỗi sau cấp phiên có cleanup best-effort đúng cookie của lần thử; cleanup lỗi
phải xử lý phiên thử, không tự tuyên bố logout thành công.

Demo với chủ dự án: đối chiếu report hai role và requestId; qua browser thật kiểm
__Host/Secure/HttpOnly/SameSite/Path/no Domain, CSRF JS đọc được và cookie phiên không
JS đọc được. Không chụp token/password/Network dump. Browser test vẫn cần ghi riêng,
HttpClient không chứng minh browser đã chấp nhận cookie. Quyền hiện hành/thu hồi
giữa phiên và trước replay, lỗi SQL/Redis, rate/forwarding giả, API-worker cùng JAR,
restart và readiness phải kiểm trên staging cô lập; **không gây lỗi trên production**.
Giữ đúng key/body khi demo replay nhập tồn, không cộng bằng key mới.

Ghi SHA FE/BE/JAR/V001, config không bí mật, resource/pool/timeout, DNS/TLS/renewal,
bindings/roles, kết quả từng nhánh/requestId và cleanup; kiểm log proxy/app không
marker nhạy cảm. Mới có CI/local hoặc script thì các dòng staging để Not run.
Đặt/thu/hoàn/gửi email/UI/tải và PITR đủ RPO/RTO tiếp tục WP02–WP07.

## 6. Quay lui và điều kiện dừng

Lỗi schema/readiness/role/TLS/smoke: dừng phát hành, không nhận ghi; giữ bằng chứng
đã che dữ liệu. Dừng API/worker, chọn artifact trước đã xác nhận tương thích schema/
key ring, đổi `/opt/cane/current`, start rồi chạy lại smoke. Không sửa checksum,
rollback bằng drop/clean, xóa volume/DB staging hoặc bỏ kid còn link hợp lệ.
WP01-15 yêu cầu key ring; quay lui xa hơn chỉ sau rà tương thích, không suy từ JAR
vẫn khởi động. Hai service phải cùng release sau đổi pointer/restart.

Backup/restore DB/HMAC theo phương án đã chốt và kiểm riêng trước mở bán; snapshot
VM hay local fixture không chứng minh PITR/RPO≤5 phút/RTO≤4 giờ. Chỉ xóa project
`cane-wp01-16-check-*` do lệnh local vừa tạo; không chạy `down --volumes` hoặc prune
trên deployment staging/production để chữa lỗi.

**WP01-16/WP01 chưa hoàn thành** đến khi có staging HTTPS thật, demo/chấp nhận và
bằng chứng còn thiếu. Theo chỉ dẫn mới nhất, sau bàn giao phần local thì dừng chờ
đầu vào §3; không giả lập cloud/domain để đóng task.
