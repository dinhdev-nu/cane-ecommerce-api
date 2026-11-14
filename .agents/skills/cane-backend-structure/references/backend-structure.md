# Cấu trúc tham chiếu backend CANE

## Cách đọc cây

Gốc là repo `cane-api-springboot/`, package gốc `vn.cane`. Cây dưới đây biểu diễn
trách nhiệm, không yêu cầu tạo sẵn mọi package/file. Tại PREP-02 chỉ có lớp bootstrap,
package-info nghiệp vụ và test khung. Tên lớp minh họa phải được đối chiếu mã hiện có
trước khi dùng. Không đặt code backend vào `cane-mvp/src/` hoặc repo frontend.

Chủ dự án xác nhận ngày 10/11/2026: cây mẫu gửi trong chat chỉ để tham khảo, có thể
giữ hoặc điều chỉnh theo nhu cầu thực tế. Không đổi tên/di chuyển code đang phù hợp
chỉ để khớp cây; không tạo interface, package hay pattern chỉ vì ví dụ có chúng.
Chất lượng và tính đúng đắn quyết định mức độ phức tạp cần thiết.

Nguồn của repo/package gốc, mô-đun và bốn lớp là thiết kế kỹ thuật §4.1–4.2. Package
con và tên lớp dưới đây là ví dụ, không phải cây đã có hoặc được đặc tả nguyên tên.
Chủ động chọn cách tổ chức trong ranh giới thiết kế, bám code hiện có. Nếu cần đổi
hành vi, API, schema, quyền hoặc kiến trúc mà tài liệu chưa nêu, hỏi chủ dự án trước.

```text
cane-api-springboot/
├── AGENTS.md
├── .agents/skills/cane-backend-structure/
├── pom.xml, mvnw, mvnw.cmd, .mvn/
├── .github/workflows/ci.yml
├── src/main/java/vn/cane/
│   ├── CaneApplication.java
│   ├── bootstrap/                  # ghép bean, cấu hình, runtime/readiness
│   ├── catalog/                    # products, stock_movements, seed SKU
│   ├── checkout/                   # quote, vùng giao, phí/tổng
│   ├── orders/                     # orders, order_items, link/vòng đời
│   ├── payments/                   # payments; inbox chỉ khi có nhánh auto
│   ├── admin/                      # staff_users, staff_sessions, settings
│   ├── support/                    # cơ chế nền, không sở hữu quy tắc tiền/đơn
│   │   ├── transaction/            # transaction/retry/khóa dùng chung khi cần
│   │   ├── idempotency/            # actor/key, hash, operation receipt
│   │   ├── audit/                  # business_events
│   │   ├── outbox/                 # ghi notification_outbox trong TX caller
│   │   ├── web/                    # requestId, error/header/parser chung
│   │   ├── validation/             # primitive validation thực sự dùng chung
│   │   └── ratelimit/              # contract, Redis/process fallback
│   └── worker/                     # scheduler và adapter gửi, không HTTP
├── src/main/resources/
│   ├── application*.yml
│   ├── META-INF/spring.factories
│   └── db/migration/core/          # versioned SQL, không chứa secret/fixture
├── src/test/java/vn/cane/          # package tương ứng; *Test và *IT
│   └── fixture/                    # hạ tầng và fixture dùng lại thật sự
├── src/test/resources/             # test config, fixture/migration lỗi có kiểm soát
├── scripts/
│   ├── dev/                       # init role/ACL hạ tầng thử hiện có
│   ├── database/                  # wrapper migrate/quyền khi được triển khai
│   └── ...                        # lệnh staff/seed/tồn: wrapper gọi Java service
├── docs/                          # lệnh, hướng dẫn và bằng chứng repo thực thi
└── .local/, target/                # env và kết quả thử ngoài Git
```

Trong từng mô-đun nghiệp vụ, chọn lớp phù hợp:

```text
admin/
  api/                             # AdminAuthController + DTO HTTP
  application/                     # StaffSessionService, StaffCommandService...
  domain/                          # staff/session/settings policy và value types
  infrastructure/
    ...                            # JDBC/RowMapper; chỉ chia jdbc/ nếu thực sự cần
    security/                      # session filter, password/cookie/CSRF adapter
```

Không tạo tất cả tên minh họa. Orders giữ quyền/vòng đời link; bộ ký/key ring là
cơ chế kỹ thuật, có thể đặt tại Orders hoặc tách sang support khi có nhu cầu dùng
chung thực tế. Cây mẫu `support/hmac` không buộc tạo thêm một abstraction. Bộ đếm
rate là cơ chế dùng chung. Validation JSON lồng của settings thuộc Admin; support
chỉ giữ parser/miền primitive dùng chung.

## Hướng gọi và quyền sở hữu

| Caller | Hướng gọi được dùng |
|---|---|
| HTTP controller | Application service của mô-đun; ánh xạ actor đáng tin cậy và DTO |
| Application service | Domain + contract IO của mô-đun; service công khai mô-đun khác theo thiết kế |
| JDBC repository | PostgreSQL, SQL bind, RowMapper; tham gia transaction caller |
| Worker job/CLI | Application service; không tự cập nhật bảng để vượt guard |
| Bootstrap | Ghép cấu hình và adapter; không biến thành nơi chứa toàn bộ nghiệp vụ |
| Support | Cơ chế trung tính; không gọi ngược Orders/Payments/Admin |

Orders điều phối transaction đơn/tiền/kho qua service chuyên trách; Payments giữ quy
tắc tiền, Catalog giữ thao tác kho, Admin giữ staff/settings. Tránh phụ thuộc vòng
Orders ↔ Payments bằng việc để Orders sở hữu điều phối, không cho Payments gọi ngược
controller/repository Orders. Nếu cần đổi contract, cập nhật thiết kế trước phần phụ thuộc.

`business_events` và `notification_outbox` là hạ tầng dùng chung. Writer nhận dữ kiện
đã xác định bởi service nghiệp vụ; không tự suy người nhận hoặc quan hệ tiền–đơn từ
trạng thái hiện tại. Dispatcher/lease/provider ở Worker là phần khác với writer WP01.

## Ví dụ phân chia file theo trách nhiệm

| Việc | Chủ quản / nơi đặt |
|---|---|
| JSON duplicate-key/body limit, ErrorResponse, requestId | `support/web` |
| Auth endpoint request/response | `admin/api`; không trả entity/row DB trực tiếp |
| Password encoder, session filter/cookie/CSRF | `admin/infrastructure/security`; wiring ở bootstrap |
| Quy tắc login lock, Owner cuối, session/auth_version | `admin/domain` và `admin/application` |
| Allowlist/validator 16 settings | `admin/domain` hoặc application tùy phụ thuộc; SQL ở `admin/infrastructure` |
| Readiness schema/runtime và environment properties | `bootstrap`; có thể tách class khi nhiệm vụ độc lập |
| Transaction runner/retry/advisory lock | `support/transaction`; SQL khóa entity ở repository chủ quản |
| Hash canonical và receipt actor/key | `support/idempotency`; hash đặt đơn riêng theo Orders |
| Audit/outbox JDBC writer | `support/audit`, `support/outbox`; cùng connection caller |
| Manifest/seed SKU, nhập tồn ban đầu | `catalog/application` + adapter JDBC/CLI; không để seed ghi đè startup |
| Link authorization; bộ ký/key ring | Nghiệp vụ ở Orders; crypto ở infrastructure hoặc support khi dùng chung thực tế |
| Lua rate/process fallback/trusted IP | `support/ratelimit`; HTTP adapter gắn route đúng phạm vi |
| Migrate/cấp quyền vận hành | SQL versioned hoặc script được thiết kế riêng; role/secret lấy cấu hình, không ghi password vào SQL |
| Lệnh tạo/reset/vô hiệu staff | `scripts/`, dùng chung encoder/thành phần nghiệp vụ; vị trí CLI chọn khi triển khai |

Tài liệu quy định script staff, encoder dùng chung và hai vai trò dịch vụ API/worker;
chưa quyết định cơ chế Java CLI/bootstrap cụ thể. Đề xuất lệnh staff/seed là tiến
trình một lần gọi application service, không mở HTTP/scheduler nghiệp vụ. Cơ chế
bootstrap và package CLI được chủ động chọn theo chỉ dẫn mới nhất ngày 10/11/2026.
Chỉ hỏi khi quyền DB/yêu cầu hệ thống còn thiếu hoặc chưa rõ; không xin duyệt lựa
chọn triển khai nội bộ. Không tự thêm endpoint quản trị nhân viên.

## Nguồn thực tế cần kiểm khi thay đổi

- `pom.xml`: Spring Boot/BOM, JDBC/Security/Redis/Flyway, formatter, Surefire/Failsafe.
- `RuntimeModeInitializer`: nhận local/test/staging/production + api/worker; profile
  staging/production kiểm bằng fixture local, chưa phải bằng chứng triển khai thật.
- `SecurityConfiguration`: hiện deny-all PREP-02; WP01 phải mở đúng ba auth route,
  giữ deny mặc định cho phần chưa triển khai và bảo vệ login/logout bằng CSRF.
- `InfrastructureStartupCheck`: kiểm trước HTTP/job qua `InfrastructureReadiness` và
  `DatabaseReadiness` (DB/role/schema/history/checksum/quyền tối thiểu, Redis degraded).
  Flyway runtime chỉ validate read-only, không migrate/repair hoặc callback SQL.
- `TestInfrastructure`, `BackendSkeletonIT`, `RuntimeReadinessIT`: fixture migrate +
  grant trước runtime; kiểm schema thiếu không tự migrate và readiness drift riêng.
- `scripts/dev/init-roles.sql`: hiện tạo role, chưa cấp quyền bảng nghiệp vụ.
- `StaffCommandService`/`JdbcStaffCommandRepository` sở hữu lệnh staff và guard Owner
  cuối; `PasswordConfiguration` cấp cùng Argon2id encoder cho CLI/login. CLI riêng
  dùng `ConsolePasswordPrompt`, không đọc password từ argument/env/file; wrapper
  `scripts/staff/Manage-Staff.ps1`. Xem `docs/staff-commands.md`.
- `CatalogSeedService` và `JdbcCatalogSeedRepository`: seed chỉ thêm SKU thiếu;
  `CatalogSeedCommand` ghép context tối thiểu qua `bootstrap/cli/OperationalDatabase`,
  không khởi động Boot/HTTP/worker/Redis. `scripts/catalog/Seed-Catalog.ps1` gọi JAR;
  fixture chỉ ở test, template release chưa có dữ liệu thật. Xem `docs/catalog-seed.md`.
- `support.ratelimit`: Redis Lua counter và process fallback dùng chung; bootstrap
  ghép bean ở API, `RateLimitFilter` gắn login một lần trước Security. Chỉ tin proxy
  allowlist; không đưa session/key nghiệp vụ vào Redis. Xem `docs/rate-limits.md`.
- Admin `StaffSessionService`/`JdbcStaffSessionRepository` cấp/thay phiên trong TX
  login, tra JOIN hiện hành; `StaffAuthorization` đọc lại trước replay/guard ghi.
  `infrastructure.security` giữ cookie/filter và ánh xạ lỗi quyền; raw token chỉ cho
  cookie sau commit, principal không chứa token thô. Xem `docs/staff-sessions.md`.
- `TransactionConfiguration`/`support.transaction`: cùng manager/DataSource, write
  READ COMMITTED, snapshot read-only REPEATABLE READ, chỉ outer boundary retry
  deadlock sau rollback; khóa SQL ở module sở hữu. Xem `docs/transactions.md`.
  Repository dùng proxy Spring không để class/method cần proxy là final; không
  đổi cấu hình proxy toàn cục để che lỗi tổ chức lớp.
- `scripts/Test-Backend.ps1`: runner chuẩn, dọn Compose riêng; kiểm image tải thêm sau
  đối chiếu inventory, không dùng prune toàn máy.
- `.editorconfig`: UTF-8, LF chung/Java; script PowerShell và CMD theo CRLF.

## Rà cấu trúc trước commit

Mỗi file có chủ quản/nhiệm vụ rõ; type chính trùng filename/package; không import chéo
infrastructure hoặc vòng mô-đun; caller không bỏ service/transaction; test gắn hành vi
và ràng buộc thật. Giữ secret/env/report/container thử ngoài thay đổi cần commit.
Nếu chỉ thay skill/tài liệu, kiểm frontmatter, link, ví dụ và phạm vi hướng dẫn thay
vì thêm test hình thức. Nếu thay code, kiểm unit/integration/build phù hợp DoD.
