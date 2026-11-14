# cane-ecommerce-api

Backend CANE: một dự án Maven, một bản build chạy hai chế độ `api` và `worker`.
Đang triển khai WP01 trên khung PREP-02. Có V001, lệnh migration lõi và script cấp
quyền API/worker theo thao tác/cột; trigger giữ nguyên V001 và được kiểm riêng với quyền runtime.
WP01-03 bổ sung [validation, lỗi HTTP và log/requestId](docs/input-errors.md),
JSON nghiêm ngặt và giới hạn body 64 KiB.
WP01-04 kiểm [runtime/readiness](docs/runtime-readiness.md) trước HTTP/scheduler,
thêm profile staging/production và diagnostics nội bộ; chưa có hạ tầng staging.
WP01-05 bổ sung [transaction/khóa dùng chung](docs/transactions.md), đọc snapshot,
phân loại UNIQUE và hook sau commit; chưa triển khai guard nghiệp vụ thay các WP sau.
WP01-07 có [lệnh tạo/quản lý nhân viên](docs/staff-commands.md), encoder Argon2id chung,
prompt mật khẩu kín và bảo vệ Owner active cuối cùng.
WP01-08 có [Redis/rate limit và process fallback](docs/rate-limits.md), năm nhóm
đếm và filter login trước Security; proxy thật/HTTPS kiểm khi triển khai staging.
WP01-09 có [session/cookie và kiểm quyền SQL hiện hành](docs/staff-sessions.md).
WP01-10 có [CSRF/origin, me và logout](docs/admin-auth.md); API yêu cầu `PUBLIC_ORIGIN`
tin cậy. WP01-11 thêm login/khóa tài khoản 5 lần/15 phút và xoay phiên/CSRF sau
commit; các route nghiệp vụ chưa triển khai vẫn deny-all.
WP01-12 có [writer event, biên nhận và outbox](docs/event-receipts-outbox.md)
trên transaction caller, giữ nguồn lịch sử và ưu tiên người nhận khách khi trùng.
WP01-13 có [khung POST admin chống lặp](docs/admin-idempotency.md): canonical hash,
quyền trước replay, advisory actor/key và biên nhận cũ trước guard biến động.
WP01-14 có [lệnh nhập tồn đầu](docs/initial-stock.md) qua service/CLI, Owner hiện
hành và key cố định; tồn/event/ledger/neo cùng transaction, lượng 0 chỉ neo no-op.
WP01-15 có [HMAC link một đơn](docs/order-links.md), kiểm version/hạn SQL và kiểm
lại dưới khóa. API/worker cần cùng LINK_SECRETS/LINK_ACTIVE_KID; xem runbook trước chạy.
WP01-16 đang hoàn thiện [kiểm local và chuẩn bị staging](docs/staging-foundation.md),
[hồ sơ kiểm](docs/wp01-16-verification.md); chưa có cloud/domain thật hoặc nghiệm thu HTTPS.
WP01-06 có [lệnh seed chín SKU](docs/catalog-seed.md) chạy riêng bằng credential
vận hành; manifest thật chưa điền sẽ bị từ chối, fixture chỉ local/test.
API nghiệp vụ, chống lặp và job còn lại được triển khai/kiểm tuần tự theo WP01–WP07.

Đặc tả và quyết định nằm trong repo local `../cane-mvp/`: đọc
[thiết kế kỹ thuật](../cane-mvp/docs/03-technical-design.md),
[bộ khóa công cụ](../cane-mvp/docs/pm/toolchain-lock.md)
và [backlog](../cane-mvp/docs/pm/backlog.md).
Theo chỉ dẫn chủ dự án, tài liệu Cane-MVP chỉ commit local, không push;
backend vẫn tạo PR/merge GitHub. Các liên kết sang tài liệu giả định workspace
có ba thư mục cùng cấp như thiết kế.

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

Init không tạo schema nghiệp vụ hoặc dữ liệu mở bán. V001 chạy riêng bằng role DDL;
quyền từng bảng/thao tác được cấp bằng [script runtime](docs/data-protection.md),
không cấp blanket CRUD cho ledger/event.
Init chỉ chạy trên volume mới; đổi mật khẩu trong env không tự đổi mật khẩu của DB đã khởi tạo.

## Chạy API và worker

Trước lần chạy đầu, thực hiện [migration lõi](docs/core-migration.md) và
[cấp quyền runtime](docs/data-protection.md#cấp-quyền-sau-migration) bằng credential
vận hành riêng. Runtime từ chối schema trống/thiếu history hoặc thiếu quyền; không
tự dựng database khi chạy. Volume đã có V001 cần validate/cấp quyền đúng phiên bản,
không chạy lại init role hoặc sửa checksum để bỏ qua lỗi.

Mở hai terminal, mỗi terminal chạy một lệnh:

```powershell
$env:PUBLIC_ORIGIN = 'http://localhost:3000'
pwsh -File scripts/Run-Local.ps1 -Mode api
pwsh -File scripts/Run-Local.ps1 -Mode worker
```

API bind `127.0.0.1:8080`, có GET me và POST logout; chưa có login hoặc endpoint
nghiệp vụ. Me chưa có phiên trả 401 JSON kèm CSRF cookie/header. Admin ghi phải qua
origin/CSRF; route cần phiên trả 401 khi phiên không hợp lệ. Cookie vẫn Secure ở
local; HTTP test không thay bằng chứng browser qua HTTPS. Xem [luồng auth](docs/admin-auth.md).
Worker không có HTTP, chỉ worker bật scheduling; chưa có job nghiệp vụ.
Worker giữ tiến trình sống và dừng bằng Ctrl+C. Cả hai cùng dùng executable JAR.

Phải chọn đúng một môi trường `local`/`test`/`staging`/`production` và một vai trò `api`/`worker`.
Thiếu vai trò, bật cả hai, thiếu connection/secret hoặc bật lại SQL init/Flyway sẽ bị từ chối.
Profile staging/production đã kiểm bằng fixture local, chưa triển khai môi trường thật.
DB/schema/history/checksum/quyền không đúng làm khởi động thất bại trước nhận việc;
Redis lỗi có trạng thái suy giảm. Bộ đếm fallback thuộc WP01-08.
Log không dump request/body/bind SQL; dữ liệu kết nối không được chủ động ghi ra log khởi động.

Các trị số local/test đã được chủ dự án xác nhận ngày 07/11/2026:

| Cấu hình | Giá trị |
|---|---|
| DB pool tối đa API / worker; tối thiểu | 5 / 3; 1 |
| Chờ DB connection / validation | 3 giây / 1 giây |
| SQL / chờ khóa | 5 giây / 2 giây |
| JDBC connect / socket read / cancel connect | 3 giây / 6 giây / 1 giây |
| Redis connect / command | 1 giây / 1 giây |
| Thời gian graceful shutdown mỗi phase / scheduler chờ kết thúc | 20 giây / 20 giây |

Đây là cấu hình kiểm khung, chưa phải số đo đạt tải hoặc cấu hình phát hành.
WP01-04 bổ sung giới hạn mạng JDBC để tránh chờ vô hạn; đây là lựa chọn kỹ thuật
khởi điểm cần đo trên hạ tầng thật. `forward-headers-strategy=none`; cấu hình proxy/IP
tin cậy và HTTPS phải gắn với nền tảng/domain được chốt trước smoke staging.
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
fixture khung không có migration, Redis ACL/TTL, Redis lỗi và PostgreSQL lỗi.
WP01-01 bổ sung kiểm V001/schema/constraint, checksum, rollback và lệnh migrate;
xem [hồ sơ WP01-01](docs/wp01-01-verification.md). WP01-02 bổ sung kiểm trigger,
grant chạy lại và các nhánh hợp lệ bằng role runtime; xem [hồ sơ WP01-02](docs/wp01-02-verification.md).
WP01-03 kiểm chuẩn hóa primitive/16 settings, HTTP thật, lỗi trước/sau Security,
giới hạn byte và log không lộ đầu vào; xem [hồ sơ WP01-03](docs/wp01-03-verification.md).
Chưa nghiệm thu nghiệp vụ tiền/tồn,
auth/session hoặc toàn bộ T01–T118 từ các kiểm nền này.
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

`src/main/resources/db/migration/core/V001__cane_core.sql` tạo 10 bảng/122 cột lõi,
khóa/check/index, 3 function/11 trigger và 16 settings. Runtime không migrate;
không chạy `schema.sql`/`data.sql`. Chưa có inbox ngân hàng, SKU hoặc staff seed.

Xác minh đúng DB/môi trường, cấp `CANE_MIGRATION_URL`, `CANE_MIGRATION_USER`,
`CANE_MIGRATION_PASSWORD` cho tiến trình bằng credential DDL riêng rồi chạy:

```powershell
pwsh -NoProfile -File scripts/database/Migrate-Core.ps1
```

Lệnh từ chối migration repeatable trong core; kiểm trước migrate chỉ bỏ qua
`*:pending`, sau migrate validate đầy đủ. Core chỉ dùng migration versioned.
Không truyền password bằng tham số `-D`, không tự baseline/repair/clean.
Sau migration, cấp/kiểm [quyền runtime](docs/data-protection.md) bằng chủ schema,
không dùng role migration chạy dịch vụ. Xem [hướng dẫn migration và phục hồi](docs/core-migration.md).

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

Package nghiệp vụ thêm `api/application/domain/infrastructure`
khi có nhiệm vụ cụ thể; Admin đã có validator settings ở application. API và worker gọi cùng application service; quyền sở hữu bảng,
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
