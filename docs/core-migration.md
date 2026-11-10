# Migration lõi — WP01-01

Nguồn: `04-DU-LIEU-VA-HA-TANG.md` §1–3, §5 và điều kiện nghiệm thu tại file 05
ở gốc `cane-mvp/`; thiết kế dữ liệu 1.3 §3–6, §8; SQL tham chiếu
`cane-mvp.sql`. V001 giữ nguyên các đối tượng SQL được duyệt, chỉ bỏ transaction
bao ngoài và `CREATE SCHEMA` vì Flyway quản lý hai phần này. Giữ `SET LOCAL
search_path = cane, pg_catalog`. Chỉ location `core`, chưa tạo inbox ngân hàng.

## Phạm vi

V001 tạo 10 bảng/122 cột lõi, PK/FK/UNIQUE/CHECK/index, 3 function, 11 trigger và
16 settings ban đầu. Metadata `cane.flyway_schema_history` không tính vào bảng lõi.
Không tạo SKU, staff hoặc nhập tồn; không cấp quyền bảng runtime trong task này.
WP01-02 kiểm sâu trigger và cấp/kiểm quyền trước khi áp vào môi trường dùng lâu dài.

## Lệnh vận hành

Xác nhận đúng môi trường, database và role DDL trước chạy. Với database có dữ liệu,
kiểm bản sao lưu và khả năng phục hồi trước phát hành. Cấp ba biến cho tiến trình
qua môi trường an toàn, không đưa password vào tham số, Git hoặc lịch sử terminal:

- `CANE_MIGRATION_URL`: JDBC URL PostgreSQL của database đích.
- `CANE_MIGRATION_USER`: role migration riêng, có quyền tạo schema/đối tượng.
- `CANE_MIGRATION_PASSWORD`: secret của role đó; không dùng credential API/worker.

Tại gốc repo, trên PowerShell 7:

```powershell
pwsh -NoProfile -File scripts/database/Migrate-Core.ps1
```

Script từ chối mọi `R__...sql` trong core, kể cả thư mục con, trước khi gọi Maven
hoặc kết nối DB. Core chỉ dùng migration versioned. Script dừng ngay khi bất kỳ
bước nào thất bại:

1. Validate trước migrate với `*:pending`: core chỉ có migration versioned.
2. Migrate với `validateOnMigrate=true`, transaction cho mỗi migration PostgreSQL.
3. Validate sau migrate, không cấu hình bỏ qua migration thiếu/future/pending.

Chủ dự án chấp thuận ngày **10/11/2026** cách kiểm trước/sau và chặn repeatable.
Test thực trên Flyway 12.4.0 cho thấy `versioned:pending` vẫn từ chối schema chưa
tồn tại; `*:pending` cho phép Flyway tạo schema ở bước migrate. Việc chặn repeatable
giữ phạm vi ngoại lệ chỉ cho versioned pending. Checksum sai, migration đã áp bị
thiếu hoặc future vẫn phải dừng; không bỏ qua các trạng thái này.
Tham khảo [validate](https://documentation.red-gate.com/flyway/reference/commands/validate)
và [ignoreMigrationPatterns](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-ignore-migration-patterns-setting).
Hành vi schema chưa tồn tại được đối chiếu với
[DbValidate của Flyway 12.4.0](https://github.com/flyway/flyway/blob/flyway-12.4.0/flyway-core/src/main/java/org/flywaydb/core/internal/command/DbValidate.java)
và kiểm bằng PostgreSQL thật trong `CoreMigrationIT`.

POM giữ `defaultSchema=schemas=cane`, `createSchemas=true`,
`baselineOnMigrate=false`, `outOfOrder=false`, `cleanDisabled=true`,
`failOnMissingLocations=true`. API/worker tắt Flyway và SQL init; không tự chạy lệnh
vận hành khi khởi động. Bản chạy cần schema readiness/quyền được hoàn thiện ở task sau.

## Lỗi và phục hồi

- Schema không rỗng nhưng thiếu history: dừng; sao lưu, đối chiếu dữ liệu/cấu trúc,
  chỉ baseline tường minh sau khi chủ dự án xác nhận phương án. Không chạy V001 đè lên.
- Checksum sai/migration đã áp bị thiếu: dừng, kiểm artifact phát hành và history.
  Không tự sửa history, `repair`, `clean` hoặc sửa file đã phát hành để vượt kiểm.
- Migration lỗi: dừng phát hành, kiểm DDL/dữ liệu và history đã rollback; không mở
  API/worker với schema dở. Không xóa database/history để chữa lỗi môi trường thật.
- Sau phát hành, V001 bất biến. Sửa tiếp bằng version mới; quay lại bản ứng dụng
  tương thích hoặc theo kế hoạch phục hồi đã xác nhận, không tự drop dữ liệu.

## Kiểm và giới hạn

```powershell
pwsh -NoProfile -File scripts/Test-Backend.ps1
```

`CoreMigrationIT` tạo database thử riêng cho từng tình huống trên PostgreSQL theo
image khóa; container bị dọn khi kết thúc. Bản đối chiếu cột trong test được lấy từ
thiết kế dữ liệu §3.1–3.10, không đọc schema migration để tự tạo giá trị mong đợi.
Fixture ràng buộc chuyển từ bộ kiểm SQL tham chiếu, dùng transaction rollback và
không tắt trigger. Các ca kiểm migration lỗi chỉ sửa bản sao trong thư mục tạm.

Kết quả và truy vết tại [hồ sơ WP01-01](wp01-01-verification.md). Kiểm nền SQL chưa
nghiệm thu nghiệp vụ đặt đơn, thu/hoàn, seed, auth hoặc staging HTTPS.
