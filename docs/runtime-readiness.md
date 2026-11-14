# Runtime và readiness — WP01-04

Một executable JAR chạy đúng một môi trường `local`, `test`, `staging` hoặc
`production` và đúng một vai trò `api` hoặc `worker`. Profile môi trường chỉ định
`cane.environment`; vai trò quyết định HTTP/scheduling/pool. Không trộn môi trường,
không bật Flyway auto hoặc SQL init. Chỉ credential DB/Redis hiện cần dùng là bắt
buộc. Từ WP01-15, cả API/worker phải có LINK_SECRETS/LINK_ACTIVE_KID hợp lệ theo
[runbook HMAC](order-links.md); chưa đòi email/Telegram/provider khi chưa triển khai.

## Thứ tự chạy

1. Tạo DB/role/mạng riêng theo môi trường. Dùng credential vận hành riêng chạy
   [migration](core-migration.md), rồi [grant runtime](data-protection.md).
2. Cấp biến `CANE_DB_URL`, `CANE_DB_USER`, `CANE_DB_PASSWORD`, `CANE_REDIS_HOST`,
   `CANE_REDIS_PORT`, `CANE_REDIS_USER`, `CANE_REDIS_PASSWORD` từ cơ chế secret của
   môi trường. API dùng role API, worker dùng role worker; không dùng migrator.
3. Chạy cùng JAR, ví dụ `java -jar target/cane-ecommerce-api-0.1.0-SNAPSHOT.jar
   --spring.profiles.active=staging,api` hoặc `staging,worker`. Xác nhận tên artifact
   từ build; không đặt secret trong tham số CLI. Local dùng `scripts/Run-Local.ps1`.
4. Chỉ nhận việc sau khi kiểm startup đạt. API có HTTP, không scheduler nghiệp vụ;
   worker không HTTP. Startup lỗi dừng context, không tự sửa DB hoặc hạ yêu cầu quyền.

`DatabaseReadiness` kiểm READ COMMITTED, search_path `cane,pg_catalog`, timeout SQL/
khóa hữu hạn; role không đặc quyền/membership/ownership/DDL; đủ 10 bảng, 16 khóa
settings và history; quyền đọc/ghi tối thiểu phù hợp vai trò. Đây không thay kiểm
toàn bộ grant matrix/trigger ở WP01-02. Sau đó Flyway `validateWithResult` kiểm
version/checksum/pending/future với cùng runtime credential chỉ được đọc history.
Không ignore migration, migrate, baseline, repair, clean hoặc callback SQL.

Validator dùng chế độ PostgreSQL một connection để không tự chờ connection thứ
hai khi pool tối đa 1; chỉ cấu hình riêng đối tượng validate, không đổi khóa lệnh
migration. API này được kiểm trên Flyway 12.4.0; tham khảo
[cấu hình PostgreSQL của Flyway](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-postgresql-namespace/flyway-postgresql-transactional-lock-setting).

## Chẩn đoán và phục hồi

`InfrastructureReadiness.inspect()` là diagnostics nội bộ theo yêu cầu, không
cache trạng thái, không poll hoặc mở endpoint. Kết quả gồm trạng thái DB và Redis:

| Trạng thái DB | Xử lý |
|---|---|
| `READY` | Đã đạt kiểm nền; không có nghĩa nghiệp vụ/dữ liệu mở bán đã sẵn sàng |
| `CONFIGURATION_UNSAFE` | Kiểm isolation/search_path/timeout của kết nối |
| `DATABASE_UNAVAILABLE` | Kiểm kết nối, credential và dịch vụ DB |
| `ROLE_UNSAFE` | Kiểm ownership/membership/DDL bằng tài khoản vận hành; không tăng quyền runtime |
| `SCHEMA_UNAVAILABLE` | Validate artifact/history/schema/grant; triển khai migration/grant riêng đúng quy trình |

DB không READY làm startup thất bại **trước HTTP nhận request hoặc job bắt đầu**.
Log chỉ mã trạng thái cố định; không đưa SQL, URL kết nối, mật khẩu hoặc stack của
Flyway vào log runtime. Lệnh Flyway vận hành vẫn giữ chẩn đoán riêng. Redis PING
lỗi/timeout chỉ `redis=DEGRADED` nếu DB vẫn READY; không giả thành DB sẵn sàng.
WP01-08 đã có [bộ đếm process fallback](rate-limits.md) ở API. Session/audit/neo
nằm ở PostgreSQL, không phụ thuộc Redis để giữ dữ kiện.

Giới hạn JDBC connect 3s/socket read 6s/cancel connect 1s bổ sung SQL 5s/lock 2s,
pool wait 3s. Redis connect/command 1s. Đây là trị số khởi điểm local/test và profile,
chưa phải số đo NFR hoặc xác nhận cấu hình phát hành trên máy chủ thật.

## Staging còn phụ thuộc

Ngày **10/11/2026**, chủ dự án xác nhận **chưa có máy chủ/nền tảng và domain**.
Profile staging/production chỉ kiểm bằng DB/Redis fixture local; không có triển
khai hoặc smoke HTTPS thật. Ngày 14/11 đã chọn AWS Lightsail 4 GB nhưng chưa có
tài khoản/máy/domain hoặc quyền truy cập; [runbook WP01-16](staging-foundation.md)
ghi cấu hình đề xuất, kiểm local và đầu vào cần cấp. Chưa xác nhận peer/CIDR thực tế.

Hiện HTTP bind loopback, `forward-headers-strategy=none`. Khi có hạ tầng, chốt
network/bind, proxy và IP tin cậy, origin/domain/TLS, secret riêng, DB/Redis riêng
và namespace ACL riêng; kiểm từ chối header giả cùng rate ở WP01-08 và cookie/CSRF
qua HTTPS ở task 09–10/16. Không copy secret/fixture local lên môi trường thật.
