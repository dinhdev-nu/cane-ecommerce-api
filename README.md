# cane-ecommerce-api

Backend CANE: một dự án Maven, một bản build chạy hai chế độ `api` và `worker`.
Phạm vi hiện tại là khung PREP-02. Các API nghiệp vụ, migration lõi, seed, đăng nhập/session,
chống lặp và job được triển khai theo WP01–WP07.

Đặc tả và quyết định nằm trong [repo tài liệu](https://github.com/nuservice13579-dot/cane-docs).
Đọc [thiết kế kỹ thuật](https://github.com/nuservice13579-dot/cane-docs/blob/main/docs/03-technical-design.md),
[bộ khóa công cụ](https://github.com/nuservice13579-dot/cane-docs/blob/main/docs/pm/toolchain-lock.md)
và [backlog](https://github.com/nuservice13579-dot/cane-docs/blob/main/docs/pm/backlog.md).
Trong workspace ba thư mục cùng cấp, các tài liệu này nằm tại `../cane-mvp/docs/`.

## Công cụ

Temurin **25.0.5+8**, Maven **3.10.0** qua Wrapper **3.3.4**, Spring Boot **4.1.2**,
Docker Client/Engine **29.9.2**, Compose **5.6.1**, PostgreSQL **18.6**, Redis **8.10.3**.
PowerShell **7** dùng cho các script local/CI. Dependency được quản lý theo parent/BOM;
khóa formatter, Bouncy Castle và image theo bộ công cụ. Wrapper kiểm SHA-256 distribution.

```powershell
.\mvnw.cmd --version
pwsh -File scripts/Test-Toolchain.ps1
```

Trên Linux/macOS chạy `chmod +x mvnw` khi checkout lần đầu, dùng `./mvnw`;
các script kiểm chạy bằng `pwsh`.

## Dựng môi trường local

Chạy tại gốc repo. `.env.example` chỉ chứa placeholder; không phải cấu hình để chạy.
Lệnh sau tạo bí mật ngẫu nhiên trong `.local/dev.env`, được Git bỏ qua và không ghi đè khi đã có.

```powershell
pwsh -File scripts/New-LocalEnvironment.ps1
docker compose --env-file .local/dev.env config --quiet
docker compose --env-file .local/dev.env up --detach --wait
```

PostgreSQL và Redis chỉ publish cổng loopback `55432`/`16379`; sửa các giá trị port
trong `.local/dev.env` nếu trùng dịch vụ khác. PostgreSQL dùng volume tại `/var/lib/postgresql`
theo image 18. Redis không lưu bền, đặt `maxmemory 64 MiB`, `noeviction`, tắt user mặc định và chỉ
cho user runtime truy cập namespace `cane:local:*` với các lệnh cần thiết.
`maxmemory` không phải giới hạn tổng RAM của tiến trình Redis.

Script init PostgreSQL chỉ tạo các role local và quyền kết nối:

| Role | Mục đích |
|---|---|
| `cane_prep_admin` | Quản trị DB thử; không dùng chạy ứng dụng |
| `cane_migrator` | Tạo schema/migration riêng |
| `cane_api`, `cane_worker` | Runtime, không sở hữu DB/schema hoặc có quyền DDL |

Chưa có schema/bảng nghiệp vụ hoặc dữ liệu mở bán. WP01 thêm migration và cấp quyền
theo từng bảng/thao tác; không cấp blanket CRUD cho ledger/event.
Init chỉ chạy trên volume mới; đổi mật khẩu trong env không tự đổi mật khẩu của DB đã khởi tạo.

## Chạy API và worker

Mở hai terminal, mỗi terminal chạy một lệnh:

```powershell
pwsh -File scripts/Run-Local.ps1 -Mode api
pwsh -File scripts/Run-Local.ps1 -Mode worker
```

API bind `127.0.0.1:8080`, chặn mọi route bằng 403; chưa có login hoặc endpoint nghiệp vụ.
CSRF dùng cookie để tránh tạo HttpSession, nhưng bootstrap/login/logout của CANE chưa triển khai.
Worker không có HTTP, chỉ worker bật scheduling; chưa có job nghiệp vụ.
Worker giữ tiến trình sống và dừng bằng Ctrl+C. Cả hai cùng dùng executable JAR.

Phải chọn đúng một môi trường `local`/`test` và một vai trò `api`/`worker`.
Thiếu vai trò, bật cả hai, thiếu connection/secret hoặc bật lại SQL init/Flyway sẽ bị từ chối.
Chưa cấu hình staging/production trong khung này.
PostgreSQL lỗi hoặc role runtime có quyền DDL làm khởi động thất bại; Redis lỗi chỉ ghi cảnh báo
suy giảm. Cơ chế fallback nghiệp vụ thuộc WP01/WP02, chưa được tuyên bố hoạt động từ cảnh báo đó.
Log không dump request/body/bind SQL; dữ liệu kết nối không được chủ động ghi ra log khởi động.

Các trị số local/test đã được chủ dự án xác nhận ngày 07/11/2026:

| Cấu hình | Giá trị |
|---|---|
| DB pool tối đa API / worker; tối thiểu | 5 / 3; 1 |
| Chờ DB connection / validation | 3 giây / 1 giây |
| SQL / chờ khóa | 5 giây / 2 giây |
| Redis connect / command | 1 giây / 1 giây |
| Thời gian graceful shutdown mỗi phase / scheduler chờ kết thúc | 20 giây / 20 giây |

Đây là cấu hình kiểm khung, chưa phải số đo đạt tải hoặc cấu hình phát hành.
Không đưa bí mật HMAC/email/ngân hàng vào khung khi chưa có tính năng cần dùng.

## Build và kiểm tra

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd test
pwsh -File scripts/Test-Backend.ps1
```

`test`: format check, compile và unit qua Surefire, không tạo container.
`Test-Backend.ps1`: kiểm đúng bộ công cụ → Compose project có ID riêng → `clean verify`
→ dọn container/network/volume của project đó kể cả khi kiểm lỗi. Failsafe chạy `*IT`
trên Testcontainers PostgreSQL/Redis thật, không tự bỏ qua khi thiếu Docker.
Hai container Testcontainers dùng cùng script quyền/ACL với Compose, namespace Redis là `cane:test:*`.
Đóng container do thư viện quản lý sau lượt kiểm; không dùng chế độ reuse.

Ngoại lệ Docker local được chủ dự án chấp thuận cho lượt kiểm PREP-02 dùng
`pwsh -File scripts/Test-Backend.ps1 -AllowCurrentLocalDocker`.
Lệnh ghi phiên bản thực tế và sai khác; CI vẫn yêu cầu đúng bộ khóa.
Sau khi cập nhật Docker, chạy lại lệnh chuẩn để xác minh cấu hình mục tiêu.

Test hiện kiểm vai trò chạy, HTTP/scheduler, cấu hình thiếu/sai, quyền DDL, timeout,
Flyway validate thư mục rỗng, Redis ACL/TTL, Redis lỗi và PostgreSQL lỗi.
Không có kiểm tiền/tồn, migration lõi, auth/session hay nghiệm thu T01–T118 ở PREP-02.
Báo cáo ở `target/surefire-reports/` và `target/failsafe-reports/`.

Để chỉ build executable JAR trước khi Docker sẵn sàng:

```powershell
.\mvnw.cmd package
```

Lệnh này chạy unit nhưng chưa chạy integration. Chạy JAR bằng `java -jar target/cane-ecommerce-api-0.1.0-SNAPSHOT.jar`
với `--spring.profiles.active=local,api` hoặc `local,worker`, đồng thời cấp các biến runtime
ghi trong `.env.example` qua môi trường tiến trình. Spring Boot không tự đọc `.env`.
Các lệnh local có sẵn thực hiện việc cấp biến này và dùng role riêng cho từng chế độ.

## Flyway và WP01

`src/main/resources/db/migration/core/` dành cho `V001__cane_core.sql`; khung chưa có migration SQL.
Runtime không migrate; không chạy `schema.sql`/`data.sql`.
Plugin dùng `CANE_MIGRATION_URL`, `CANE_MIGRATION_USER`, `CANE_MIGRATION_PASSWORD` riêng,
schema/history `cane`, `createSchemas=true`, `baselineOnMigrate=false`, `outOfOrder=false`,
`cleanDisabled=true`. Không truyền mật khẩu bằng tham số `-D`.

Sau khi WP01 có migration và xác minh đúng DB/môi trường, cấp các biến migration qua môi trường rồi chạy:

```powershell
.\mvnw.cmd flyway:validate flyway:migrate
```

PREP-02 chỉ validate thư mục rỗng trong test; chưa coi migration nghiệp vụ hoặc quyền bảng đã đạt.
Nhánh ngân hàng tự động và location tùy chọn được thêm khi triển khai nhánh đó.

## Cấu trúc

```text
src/main/java/vn/cane/
  CaneApplication.java
  bootstrap/                 # cấu hình, ranh giới runtime, kiểm connection lúc khởi động
  catalog/                   # SKU, giá, tồn và ledger
  checkout/                  # quote, vùng giao, phí/tổng
  orders/                    # đơn và điều phối transaction nghiệp vụ
  payments/                  # quy tắc tiền, đối soát, xác nhận hoàn
  admin/                     # staff, session, settings
  support/                   # cơ chế dùng chung
  worker/                    # job và adapter gửi
src/main/resources/          # cấu hình và vị trí migration
src/test/                    # unit và integration, fixture không đưa vào production
scripts/                     # lệnh local/kiểm và init hạ tầng thử
.github/workflows/ci.yml     # kiểm trên Linux với công cụ đã khóa
```

Package nghiệp vụ hiện chỉ ghi trách nhiệm; thêm `api/application/domain/infrastructure`
khi có nhiệm vụ cụ thể. API và worker gọi cùng application service; quyền sở hữu bảng,
hướng gọi và transaction tuân theo thiết kế trong repo tài liệu.

## CI và dọn tài nguyên

Workflow kiểm toolchain/Compose/format/unit/integration/build, xuất effective POM và dependency tree,
lưu test evidence. [CI trên commit khung `b3428f9`](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/42373305195)
đã đạt trên Linux với đúng bộ khóa: 20 unit + 10 integration, không lỗi hoặc bỏ qua.
Kết quả chỉ áp dụng khung backend PREP-02; chưa phải nghiệm thu FE, nghiệp vụ hoặc production.

Sau khi dừng API/worker, dọn môi trường local do bạn chủ động dựng:

```powershell
docker compose --env-file .local/dev.env down --volumes --remove-orphans
```

Lệnh xóa cả dữ liệu thử của project `cane-prep02-local`.
Lượt `Test-Backend.ps1` tự dọn project kiểm riêng và lưu inventory trước kiểm ở
`.local/last-docker-check.json`. Sau lượt kiểm, đối chiếu image ID trước/sau và container đang sử dụng;
chỉ xóa image PostgreSQL/Redis/helper tải mới cho lượt CANE khi không còn được sử dụng.
Không xóa image đã có trước hoặc prune tài nguyên toàn máy. Giữ cấu hình và bằng chứng dựng lại.

Kết quả và phần còn chờ được ghi tại [hồ sơ kiểm PREP-02](docs/prep02-verification.md).
