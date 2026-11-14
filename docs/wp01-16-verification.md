# WP01-16 — Hồ sơ kiểm tích hợp và chuẩn bị staging

Bắt đầu 14/11/2026 trên `feat/wp01-16-staging-verification`, từ main WP01-15
`ecf5744de1a5d58a64e685716d5c0f5090f1839c`. **Task/WP01 chưa hoàn thành**:
phần local đã kiểm, staging HTTPS và demo/chấp nhận thật chưa chạy. Theo chỉ dẫn
mới nhất, hoàn thiện phần độc lập rồi dừng, chờ chủ dự án tạo cloud/domain và cấp
cách truy cập; không giả lập tài khoản AWS/domain để đóng task.

## Phần đã bổ sung

- [Runbook](staging-foundation.md): ranh giới local/staging, file/lệnh dựng/migrate/
  grants/seed/staff/tồn/chạy/kiểm/cleanup, HTTPS/smoke/demo/quay lui và đầu vào còn
  thiếu theo thứ tự. AWS Lightsail 4 GB đã được chọn, chưa tạo/có tài khoản/máy/domain
  hoặc chi phí phát sinh bởi agent. Không suy retention/PITR/NFR hoặc dữ liệu mở bán.
- Template DB/Redis staging tách dev/test, image đúng khóa, role runtime tách, SQL
  logging không lộ bind, Redis ACL namespace staging/noeviction. Cổng chỉ loopback;
  unit systemd hai user/cùng JAR, secret ngoài Git và không có migrator trong runtime.
  Wrapper shell kiểm đúng năm secret hex 64 ký tự khác nhau trước tạo container,
  không source env hoặc nhận override process; chặn placeholder/secret trùng đã kiểm.
- Nginx template HTTPS/ACME, trusted forwarding từ proxy cùng host, no-store, CSP/
  Referrer-Policy/HSTS không includeSubDomains, edge rate thô độc lập. Không retry
  upstream POST; 413/429/upstream lỗi thành JSON/headers/ID do proxy sinh. Log chỉ
  fixed fields, không URI/body/cookie/Bearer. FE upstream giữ cùng origin, chưa demo UI.
- Smoke HTTPS mặc định không redirect/callback bỏ kiểm certificate hoặc HTTP
  fallback; prompt kín sau bootstrap, kiểm ba auth API/role/DTO/cookie/CSRF/hạn/
  logout và best-effort cleanup đúng phiên thử. Report allowlist không secret/PII,
  CreateNew không ghi đè. Nhánh thành công đầy đủ trên CA/domain thật **Not run**.
- Runner parse mọi script PowerShell, tổng hợp XML/counts/SHA Git/JAR/V001/dirty
  vào JSON và upload CI cùng evidence. Không dùng report local thay staging.
- Sửa một dòng log startup cũ còn nói fallback pending WP01-08; thông báo mới chỉ
  mô tả Redis suy giảm, dùng đúng cả API/worker. Cập nhật readiness doc yêu cầu HMAC.
  V001/grants/schema/settings/POM không đổi; hai diff checkout gốc giữ riêng.

## Ma trận 16 nhóm — bằng chứng nền local

Các suite chạy PostgreSQL/Redis/Tomcat thật; fixture/harness chỉ cho thành phần chưa
có API. Passed chỉ **phần nền trong suite**, không Verified toàn mã T hoặc phần
tiếp nối ở Test Cases §4. Mã T01–T118 giữ nguyên, không tạo mã nghiệm thu mới.

| Nhóm Test Cases §3 | Suite/bằng chứng nền | Giới hạn tiếp nối |
|---|---|---|
| 3.1 Migration | CoreMigrationIT, RuntimeReadinessIT | Migration môi trường dùng lâu dài chưa chạy |
| 3.2 Miền/settings/unique | CoreMigrationIT, DataProtectionIT, SettingsValidatorTest, EventOutboxIT | Guard nghiệp vụ nhiều bảng tại WP03–06 |
| 3.3 Lịch sử/receipt bất biến | DataProtectionIT | Resolve/refund API chưa có |
| 3.4 Seed/tồn đầu/retry | CatalogSeedIT, InitialStockIT | Chưa có manifest/giá/tồn thật |
| 3.5 Staff/Owner cuối | StaffCommandIT, StaffPasswordTest | Chưa tạo staff cloud |
| 3.6 CSRF/login/logout | AdminAuthIT, AdminLoginIT, StaffCsrfTest, AdminRequestOriginTest | Secure-cookie/browser/HTTPS thật chưa chạy |
| 3.7 Login lock | AdminLoginIT, StaffLoginServiceTest, LoginLockTest | Staging chưa chạy |
| 3.8 Session/current quyền | StaffSessionIT, AdminAuthIT, AdminOperationsIT, SessionTokenTest | Replay API nghiệp vụ và browser thật tiếp nối |
| 3.9 HMAC | HmacOrderLinkTokensTest, OrderLinksIT, RuntimeReadinessIT | Guest/resend/rotate/outbox HTTP WP05 |
| 3.10 Input/lỗi | InputValidationTest, SettingsValidatorTest, ApiInputIT | DTO nghiệp vụ WP02–06 |
| 3.11 Replay/neo | AdminOperationsIT, InitialStockIT, CanonicalOperationTest | POST đơn/tiền/kho/link thật tiếp nối |
| 3.12 Đồng thời/khóa/retry | TransactionsIT, AdminOperationsIT, InitialStockIT | Race nghiệp vụ WP03–06 |
| 3.13 Rollback/sau commit | TransactionsIT, EventOutboxIT, InitialStockIT | Full luồng tiền/đơn/outbox dispatch chưa có |
| 3.14 Redis/rate/fallback | RateLimitIT, RateLimiterTest, ClientIpResolverTest | Gắn route công khai tương ứng WP02–05 |
| 3.15 Runtime/tách vai trò | BackendSkeletonIT, RuntimeReadinessIT, lệnh cấu hình local | Mạng/proxy/restart/HTTPS trên VM thật chưa chạy |
| 3.16 Privacy/header/secret | ApiInputIT, auth/HMAC suites, proxy local và rà log/JAR | Browser/FE/CSP nonce/private DTO thật theo WP sau |

## Kết quả local ngày 14/11/2026

Lượt runner đầu: **159 unit + 224 integration**, 0 failures/errors/skipped;
Maven 7 phút 21 giây, kết thúc **14:50:10 GMT+7**. Log ngoài Git
`.local/wp01-16-release.log`; JSON 19 unit suite/16 IT suite. Compose
`cane-prep02-check-84064bd94db9` đã dọn. Đây là trước sửa dòng log cũ; lượt cuối
sau sửa được ghi riêng bên dưới, không tái dùng kết quả này cho code mới.

Kiểm template local bằng PostgreSQL/Redis thật: phát hiện tên input mật khẩu infra
trùng Flyway, làm cleanup không resolve được Compose sau restore env. Đã dọn đúng
project thử lỗi, tách input infra sang `CANE_STAGING_*`, và từ chối biến staging có
sẵn trong lệnh thử để không nhận nhầm credential khác. Chạy lại project
`cane-wp01-16-check-36ed3144da2e` **đạt toàn bộ**: template/loopback bindings,
migration trước/sau, grants, cùng JAR API/worker credential riêng, bootstrap 401,
login username fixture không tồn tại trả 401, pause Redis có transition process
fallback, stop PostgreSQL trả 503. Report `localOnly=true`, mọi phase và `cleaned=true`;
secret env thử đã xóa, hai process đã dừng, container/network/volume đã dọn.
Log/report ngoài Git `.local/<project>/`; không tạo staff/đơn/seed dữ liệu giả.
HTTP gửi CSRF cookie tường minh chỉ kiểm server, không nhận là browser HTTPS.

Nginx local **1.30.6**, image official resolve
`nginx@sha256:42c68eab6f7e22611786a9fe1d1c019e275c50185bd54b8f75dc2b9184322703`,
kiểm syntax offline đạt. TLS fixture local bind loopback, client Python dùng trust
fixture tường minh **vẫn verify certificate/hostname**: upstream vắng trả JSON 503,
body khai >64 KiB trả 413, 64 connection có barrier làm edge trả 429/Retry-After=1;
mọi response giữ header/no-store/ID/code. Access log không marker query/cookie/
Bearer. Đây là kiểm cấu hình proxy ở local, không CA công khai hay DNS/cloud thật.
Runtime package Nginx trên VM thật chưa chọn/kiểm; image này chỉ dùng kiểm local.

Smoke guard: năm origin HTTP/userinfo/path/query/fragment bị chặn trước network/
prompt; file report có sẵn giữ nguyên. Với TLS fixture không trust, default
HttpClient xác nhận AuthenticationException; script dừng `anonymous-me`, không hỏi
mật khẩu, không bỏ kiểm certificate. Container fixture đã dọn, không cài CA vào
trust store OS. Nhánh login/me/logout dương tính kiểm ở auth IT qua HTTP thật;
script HTTPS dương tính đầy đủ còn cần môi trường domain/CA thật.

Runner cuối sau sửa code: **159 unit + 224 integration đạt**, 0 failures/errors/
skipped, 19 unit suite/16 IT suite; Maven **6 phút 34 giây**, kết thúc **15:13:04
GMT+7**. Log `.local/wp01-16-release-final.log`, Compose
`cane-prep02-check-be7175aadef7` đã dọn. JSON ghi đúng revision nền/dirty=true vì
kiểm worktree trước commit; CI trên PR xác nhận commit cuối, không gọi đây là
bản build main đã phát hành.

SHA-256 JAR cuối `f6986c1339de1ace3963a0e29fd0c57fe5b05898073667b6e313932a5ae36f60`;
V001 `4419714155cb9570c1b7264a488561eba3407f34de14880bdbac2e7416d16761`.
Không sửa migration/grants/settings/POM. XML/format/JAR đạt, không test/probe/fixture
trong JAR; log cuối không marker nhạy cảm. Hai diff checkout gốc giữ nguyên SHA-256.
Lệnh cấu hình chạy lại trên **chính JAR cuối**, project
`cane-wp01-16-check-bb0a8bf65caa`, đạt validator secret đúng/placeholder/trùng và
mọi phase runtime/fault/cleanup; `cleaned=true`, không env secret còn lại. Container/
network/volume thử đã dọn, image Nginx mới tải và private key fixture đã dọn, không
prune image có sẵn. Diff/liên kết/PowerShell parse đạt. PR nháp/CI được truy vết ở
RTM Cane-MVP §7 sau tạo; chưa merge task 16 hoặc nghiệm thu HTTPS thật.

## Phần còn chặn và điều kiện hoàn tất

Chưa có AWS account/VM/static IP/region/OS đã cấp, domain/DNS/chứng chỉ công khai
hoặc quyền SSH. Chưa deploy cloud, smoke hai role qua HTTPS/browser, kiểm firewall/
renewal/restart/fault recovery trên VM, demo/chấp nhận của chủ dự án. Đầu vào theo
thứ tự tại [runbook §3](staging-foundation.md#3-đầu-vào-cần-cung-cấp-sau-khi-phần-local-hoàn-tất).
Chưa nghiệm thu NFR tải/email/PITR hoặc mở bán. WP01-16/WP01 giữ chưa hoàn thành;
Cane-MVP chỉ commit local, không push. Dừng sau bàn giao phần local theo yêu cầu.
