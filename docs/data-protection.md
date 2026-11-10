# Bảo vệ dữ liệu và quyền runtime

WP01-02 bám `cane-mvp/04-DU-LIEU-VA-HA-TANG.md` §2–4, thiết kế dữ liệu
§5/8, bảo mật §7.4 và kịch bản WP01 §3.3. V001 đã có đủ ba function/mười một
trigger, không sửa file/checksum đã phát hành. Quyền môi trường được cấp bằng
script vận hành riêng; tên role không được cố định trong migration nghiệp vụ.

## Phạm vi trigger

- Ba trigger `touch_updated_at` cập nhật timestamp của products/settings/orders,
  không tự điền actor hoặc sinh lịch sử.
- order_items/business_events/stock_movements chặn UPDATE/DELETE/TRUNCATE.
  Biên nhận thao tác trong business_events cũng bất biến.
- payments chặn DELETE/TRUNCATE và sửa dữ kiện thu gốc (ID, phương thức, số tiền,
  reference, bank receipt, người/mốc ghi nhận). Cash, khoản chính hoặc đã hoàn
  giữ liên kết; không bỏ cờ chính hoặc áp một khoản đã hoàn. Bank phụ chưa hoàn
  được đối soát sang đơn khác với liên kết đã xác minh trong cùng thao tác.
  Hoàn hợp lệ được phép; trạng thái và bằng chứng đã hoàn không bị đảo/sửa.

Trigger vi phạm báo `55000`; runtime thiếu quyền báo `42501`. Function cố định
search_path=pg_catalog và không SECURITY DEFINER. Service vẫn phải kiểm quyền,
quan hệ tiền–đơn, chuyển trạng thái, nguồn outbox và snapshot theo thiết kế.

## Ma trận quyền cho bản mở bán thủ công

`S/I/U/D` là SELECT/INSERT/UPDATE/DELETE. U dưới đây luôn giới hạn cột; không cấp
quyền toàn bảng UPDATE. Tất cả đối tượng thuộc schema cane.

| Bảng | API | Worker |
|---|---|---|
| staff_users | S; U failed_count/locked_until | Không |
| staff_sessions | S/I/D; không gia hạn U | S chỉ expires_at; D dọn hết hạn |
| products | S; U giá/tồn/bật bán/phạm vi, updated_at/updated_by | S |
| settings | S; U value, updated_at/updated_by | S |
| orders | S/I; U trạng thái tiền/đơn, mốc tiền, link, cờ/yêu cầu hủy, dữ kiện hủy, updated_at | S; U chỉ updated_at để khóa hàng |
| order_items | S/I | S |
| payments | S/I; U liên kết/xác minh/áp, trạng thái/bằng chứng hoàn, dữ kiện review/note | S; U chỉ id để khóa hàng, trigger chặn đổi ID |
| business_events | S/I | S/I |
| stock_movements | S/I | S |
| notification_outbox | S/I | S/I; U trạng thái/lượt/lịch/lease/lỗi/sent_at |

PostgreSQL đòi UPDATE trên ít nhất một cột để dùng SELECT FOR UPDATE, kể cả job chỉ
đọc/kiểm lại dưới khóa. Vì vậy worker được quyền khóa đơn/receipt mà không có quyền
đổi tiền, trạng thái nghiệp vụ hay snapshot. Cột timestamp đơn do trigger kiểm soát;
ID receipt được trigger bảo vệ. [Quyền SELECT/khóa hàng của PostgreSQL 18](https://www.postgresql.org/docs/18/sql-select.html).

History Flyway chỉ được SELECT để kiểm phiên bản/readiness; không cấp ghi history.
Không cấp DDL/ownership/TRUNCATE/TRIGGER/REFERENCES/MAINTAIN hoặc quyền sequence,
quyền tạo staff/SKU/settings hoặc quyền sửa nguồn outbox. Các lệnh seed/staff dùng
credential vận hành riêng, được hoàn thiện ở task 06/07, không mượn quyền runtime.
Nhánh auto cần rà/bổ sung quyền cho nghiệp vụ hủy/trả tồn/inbox khi triển khai;
worker bản thủ công hiện không có các quyền ghi đó.

## Cấp quyền sau migration

1. Bootstrap hạ tầng bằng quyền quản trị DB: tạo hai LOGIN role riêng không
   superuser/CREATEDB/CREATEROLE/REPLICATION/BYPASSRLS, không membership/ownership;
   chỉ cấp CONNECT, không CREATE/TEMP trên DB hoặc CREATE trên schema public.
   Local dùng `scripts/dev/init-roles.sql`; không chạy lại script tạo role để
   sửa một môi trường đã có dữ liệu. Credential thật nằm ngoài Git.
2. Chạy [migration lõi](core-migration.md) bằng role DDL riêng.
3. Chạy `scripts/database/Grant-Runtime.sql` bằng **chủ schema core**, với hai biến
   psql `cane_api_role` và `cane_worker_role`. Script từ chối role thiếu/trùng,
   ownership/membership/quyền hạ tầng không an toàn trước khi đổi grant.
4. Kiểm đúng role API/worker và readiness trước khi cho dịch vụ nhận việc.

Ví dụ tại máy vận hành đã có client PostgreSQL, dùng kết nối PGHOST/PGPORT/
PGDATABASE/PGUSER và TLS được cấu hình cho đúng môi trường. Password dùng cơ chế
credential được bảo vệ như PGPASSFILE, không truyền trong command line:

```powershell
psql -X --no-password -v ON_ERROR_STOP=1 `
  -v cane_api_role=cane_api -v cane_worker_role=cane_worker `
  -f scripts/database/Grant-Runtime.sql
```

Ở local, có thể dùng psql cùng image PostgreSQL đã khóa trong Compose thay vì cài
client mới. Sau khi dựng Compose theo README và migrate core:

```powershell
Get-Content -Raw scripts/database/Grant-Runtime.sql |
  docker compose --env-file .local/dev.env exec -T postgres `
    psql -X --no-password -U cane_migrator -d cane_local `
    -v cane_api_role=cane_api -v cane_worker_role=cane_worker -f /dev/stdin
```

Tên service `postgres` và truy cập socket này chỉ áp dụng Compose local hiện có;
không suy ra cấu hình xác thực/TLS của staging. Không dùng credential migration cho
tiến trình API/worker.

## Chạy lại và kiểm

Script dùng một transaction với ON_ERROR_STOP. Chạy lại thu hồi grant trực tiếp cũ
trên đối tượng/cột core, rồi cấp lại ma trận trên; dữ liệu và Flyway history giữ
nguyên. Không tự thay password, membership hoặc quyền quản trị sai của role; lỗi
preflight cần sửa bootstrap đúng môi trường rồi chạy lại. Chạy trong bước triển
khai có kiểm soát trước runtime; không gọi mỗi request/startup.

Không tự cấp quyền cho bảng/sequence/function mới. Default privileges được thu
hồi ở cả mức global của chủ migration và schema: REVOKE riêng schema không triệt
tiêu grant global mặc định. [Quy tắc default privileges PostgreSQL 18](https://www.postgresql.org/docs/18/sql-alterdefaultprivileges.html).
Mỗi thay đổi schema/năng lực sau này phải rà lại ma trận, không thêm ALL TABLES grant.

`DataProtectionIT` kiểm trigger bằng chủ schema có quyền DML, kiểm runtime bằng
credential riêng, chạy script psql thật, kiểm chạy lại/default privilege và nhánh
ghi hợp lệ. Không tắt trigger trong bộ kiểm. Kết quả tại
[hồ sơ WP01-02](wp01-02-verification.md); kiểm nền không thay nghiệm thu thu/hoàn/
đối soát/worker trong các WP sau.
