# CANE — API và worker

Một bản build Spring Boot chạy ở hai chế độ. API phục vụ catalog, checkout, xem đơn và Dashboard. Worker xử lý thông báo và các tác vụ nền. PostgreSQL lưu dữ liệu nghiệp vụ. Redis phục vụ cache giá và giới hạn tần suất. [Kiến trúc hệ thống](../cane-mvp/docs/03-technical-design.md) · [Bản BE mở bán](../cane-mvp/evidence/wp07/wp07-execution.md#wp07-go-live-20261126)

## Công cụ

PowerShell 7, Temurin **25.0.5+8**, Maven **3.10.0** qua Wrapper, Docker và Compose. Image PostgreSQL và Redis đã ghim trong [compose.yaml](compose.yaml). Kiểm phiên bản bằng `pwsh -File scripts/Test-Toolchain.ps1`. Trên Windows dùng `.\mvnw.cmd`, trên Linux dùng `./mvnw`. [Bộ phiên bản của dự án](../cane-mvp/docs/pm/toolchain-lock.md)

## Dựng môi trường local

Chạy ở gốc repo bằng PowerShell 7:

```powershell
if (-not (Test-Path .local/dev.env)) { ./scripts/New-LocalEnvironment.ps1 }
docker compose --env-file .local/dev.env up --detach --wait
```

Script tạo mật khẩu riêng cho DB và Redis trong `.local/dev.env`. Compose khởi tạo các role trên volume mới và chỉ xuất cổng trên loopback. [Mẫu cấu hình](.env.example) liệt kê các biến của tiến trình.

<a id="flyway-và-wp01"></a>
## Chuẩn bị database lần đầu

1. Đọc file cấu hình **local vừa tạo** và cấp credential migration cho phiên PowerShell hiện tại:

   ```powershell
   $localConfig = ConvertFrom-StringData (Get-Content -Raw -LiteralPath .local/dev.env)
   $env:CANE_MIGRATION_URL = "jdbc:postgresql://127.0.0.1:$($localConfig.CANE_POSTGRES_PORT)/cane_local"
   $env:CANE_MIGRATION_USER = 'cane_migrator'
   $env:CANE_MIGRATION_PASSWORD = $localConfig.CANE_MIGRATION_PASSWORD
   ./scripts/database/Migrate-Core.ps1
   ```

2. Cấp quyền API và worker trên schema đã migrate:

   ```powershell
   Get-Content -Raw scripts/database/Grant-Runtime.sql |
     docker compose --env-file .local/dev.env exec -T postgres psql -X --no-password -U cane_migrator -d cane_local -v cane_api_role=cane_api -v cane_worker_role=cane_worker -f /dev/stdin
   ```

3. Để thử storefront trên **DB local mới**, build JAR và nạp chín SKU, tồn và cài đặt mẫu:

   ```powershell
   $env:CANE_OPERATIONS_DB_URL = $env:CANE_MIGRATION_URL
   $env:CANE_OPERATIONS_DB_USER = $env:CANE_MIGRATION_USER
   $env:CANE_OPERATIONS_DB_PASSWORD = $env:CANE_MIGRATION_PASSWORD
   $env:CANE_API_DB_USER = 'cane_api'
   $env:CANE_WORKER_DB_USER = 'cane_worker'
   $env:CANE_ENVIRONMENT = 'local'
   $env:CANE_REDIS_HOST = '127.0.0.1'
   $env:CANE_REDIS_PORT = $localConfig.CANE_REDIS_PORT
   $env:CANE_REDIS_USER = 'cane_runtime'
   $env:CANE_REDIS_PASSWORD = $localConfig.CANE_REDIS_PASSWORD
   .\mvnw.cmd -B -ntp package
   if ($LASTEXITCODE -eq 0) { ./scripts/dev/Initialize-Wp02.ps1 -Environment local }
   ```

Migration phát hành nằm trong [db/migration/core](src/main/resources/db/migration/core/). V001 tạo schema lõi, V002 bổ sung chính sách dữ liệu giao nhận. Xem [migration và phục hồi](docs/core-migration.md), [quyền runtime](docs/data-protection.md), [dữ liệu và seed](docs/operator-data.md) và [tạo Owner local khi kiểm Dashboard](docs/staff-commands.md).

## Chạy API và worker

Tạo key ring local một lần theo [hướng dẫn HMAC](docs/order-links.md#cấu-hình-và-secret). Ví dụ sau dùng file và kid trong hướng dẫn đó. Mở hai terminal PowerShell ở gốc repo, nạp các biến sau trong **cả hai**:

```powershell
$env:PUBLIC_ORIGIN = 'http://localhost:3000'
$env:LINK_SECRETS = Get-Content -Raw -LiteralPath .local/link-keys-local.json
$env:LINK_ACTIVE_KID = 'local-20261114'
```

Terminal thứ nhất chạy `./scripts/Run-Local.ps1 -Mode api`. Terminal thứ hai chạy `./scripts/Run-Local.ps1 -Mode worker`. API mặc định ở `http://127.0.0.1:8080`, worker chạy nền và không mở HTTP. Kiểm API bằng `Invoke-RestMethod http://127.0.0.1:8080/api/v1/prices` rồi chạy [frontend](../cane-ui-nextjs/README.md). Thông báo mặc định tắt. [Cấu hình gửi thử trên local](docs/wp05-notifications.md)

Để dừng, dùng Ctrl+C ở từng terminal rồi chạy `docker compose --env-file .local/dev.env stop`. Dữ liệu thử được giữ trong volume.

## Build và kiểm tra

| Việc | Lệnh tại gốc repo |
| --- | --- |
| Unit test | `.\mvnw.cmd test` |
| Đóng gói JAR gồm unit test | `.\mvnw.cmd package` |
| Kiểm toàn bộ với Docker, PostgreSQL và Redis | `pwsh -File scripts/Test-Backend.ps1` |

JAR và báo cáo Surefire/Failsafe nằm trong `target/`. Runner dùng môi trường thử riêng. Xem [quy trình CI](.github/workflows/ci.yml).

## Bảo trì và triển khai

- [Checkout](docs/wp03-checkout.md), [thanh toán thủ công](docs/wp04-manual-payments.md), [thông báo](docs/wp05-notifications.md), [Dashboard và worker](docs/wp06-admin-worker.md).
- [Cấu hình, giám sát, backup và rollback](../cane-mvp/docs/03-operations-design.md). [Runbook staging](docs/staging-foundation.md) mô tả proxy và systemd. [Hồ sơ WP07](../cane-mvp/evidence/wp07/wp07-execution.md#wp07-go-live-20261126) xác định bản đã triển khai.
- [Tệp vận hành backup production](scripts/operations/production/) gồm script pgBackRest, giám sát/cảnh báo và năm unit systemd. Xem [vị trí cài, cấu hình và lịch chạy](../cane-mvp/docs/pm/wp07-s3-pitr-plan.md#backup-production-files) trước khi phục hồi máy.
- Các thông số local/test dùng để đối chiếu pool và timeout nằm trong [cấu hình ứng dụng](src/main/resources/application.yml), [profile API](src/main/resources/application-api.yml) và [profile worker](src/main/resources/application-worker.yml).
