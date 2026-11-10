# WP01-01 — Hồ sơ kiểm migration lõi

Ngày thực hiện: **10/11/2026**. Branch: `feat/wp01-01-core-migration`.
Trạng thái: **hoàn thành — local, CI PR và CI main đạt; đã merge ngày 10/11/2026**.

[PR #2](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/2) đã squash merge tại
`6a0e5b6c53c36800b0b5c15c2df8b42e4c700717`.
[CI PR](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/42770452980)
và [CI main](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/42770755235)
đều đạt; cây local được đối chiếu khớp commit merge. Truy vết Cane-MVP đã commit local
theo chỉ dẫn chủ dự án, không push.

## Nguồn và phạm vi

- `04-DU-LIEU-VA-HA-TANG.md` §1–3, §5 và file 05 ở gốc `cane-mvp/`;
  thiết kế dữ liệu 1.3 §3–6, §8; `cane-mvp.sql` và bộ kiểm schema tham chiếu
  trong repo `cane-mvp`.
- Backlog WP01-01 và kịch bản WP01 §3.1–3.2; không xác nhận toàn bộ mã T nghiệp vụ.
- V001 chỉ location `core`: 10 bảng/122 cột, 14 FK, 28 CHECK có tên,
  24 index khai báo riêng, 3 function/11 trigger và 16 settings.
- V001 giữ nguyên thân SQL tham chiếu sau khi bỏ transaction bao ngoài và tạo schema
  do Flyway quản lý. Chưa có SKU/staff fixture hoặc inbox ngân hàng trong migration.

## Môi trường thực

Windows, Temurin 25.0.5+8, Maven Wrapper 3.10.0, Spring Boot 4.1.2,
Flyway 12.4.0, Docker client/server 29.9.2, Compose 5.6.1.
PostgreSQL 18.6 và Redis 8.10.3 theo digest đã khóa tại repo.
Không dùng ngoại lệ phiên bản Docker của lượt PREP-02.

Runner tạo Compose project riêng; integration dùng Testcontainers. Mỗi tình huống
migration có database mới do role `cane_migrator` sở hữu. Dữ liệu thử không dùng làm
dữ liệu mở bán; fixture constraint luôn rollback, không tắt trigger.

## Lệnh và kết quả

```powershell
.\mvnw.cmd -B -ntp spotless:apply test
pwsh -NoProfile -File scripts/Test-Backend.ps1
```

- Format/unit đầu tiên: **20 unit đạt**, không lỗi hoặc bỏ qua.
- Runner tích hợp đầu tiên: **22 integration, 20 đạt, 2 thất bại**, không bỏ qua.
  Hai ca thất bại đều liên quan validate trước migrate trên schema chưa tồn tại.
  Không coi lượt này là kết quả đạt của task.
- Kiểm PowerShell parser cho `Migrate-Core.ps1`: đạt.
- Đối chiếu thân V001 với SQL tham chiếu sau chuyển wrapper: khớp.
- Kiểm tập trung sau khi thêm đối chiếu tên constraint: **1 integration đạt**,
  không lỗi hoặc bỏ qua. Toàn bộ 28 CHECK có tên đều được kích hoạt thực tế.
  Lệnh: `./mvnw.cmd -B -ntp test-compile failsafe:integration-test failsafe:verify
  '-Dit.test=CoreMigrationIT#enforcesStorageDomainsAndUniqueKeysWithoutLeavingFixtures'`.
- Runner chuẩn sau điều chỉnh được chấp thuận: **20 unit + 23 integration đạt**,
  **0 lỗi, 0 bỏ qua**; executable JAR được build. Compose project
  `cane-prep02-check-2db3573f0eb0` đã dọn container/network/volume trong `finally`.
- Sau đó mở rộng ca future để chạy trực tiếp lệnh Maven với
  `-Dflyway.ignoreMigrationPatterns=`: **1 integration đạt**, không lỗi/bỏ qua.
  Cờ rỗng thực sự từ chối migration future, không rơi về mặc định bỏ qua future.
  Lệnh kiểm tập trung dùng cùng lifecycle như trên, với
  `-Dit.test=CoreMigrationIT#strictValidationRejectsAFutureVersionMissingFromThisRelease`.
- Runner cuối trên toàn bộ code sau khi mở rộng ca CLI: **20 unit + 23 integration
  đạt, 0 lỗi, 0 bỏ qua**; build hoàn tất lúc **13:41:39 GMT+7**, thời gian Maven
  **4 phút 03 giây**. XML Surefire/Failsafe đã đối chiếu đúng số này.
  Project `cane-prep02-check-ab133cbe7794` đã dọn container/network/volume; không
  còn container Testcontainers, không có image mới so với inventory trước chạy.

`CoreMigrationIT` kiểm cấu trúc/cột/khóa/index/đối tượng, giá trị đúng của 16 settings,
constraint và các nhánh hợp lệ, chạy lại giữ dữ liệu, checksum sai, migration đã áp
bị thiếu/future, schema có dữ liệu không history, rollback V001/nâng cấp và dừng
migration tiếp theo, role runtime không migrate, lệnh vận hành thực.
Fixture SQL còn đối chiếu tên constraint thực sự từ chối với toàn bộ 28 CHECK có tên.

## Vấn đề đã xử lý

Chủ dự án đã đồng ý bước trước chỉ bỏ qua `versioned:pending`, sau migrate validate
đầy đủ. Test thực cho thấy Flyway 12.4.0 vẫn trả `SCHEMA_DOES_NOT_EXIST` với cấu hình
này khi schema chưa được tạo; lượt kiểm lỗi đã dừng để xử lý trước khi phát hành.
Chủ dự án đã đồng ý chặn repeatable trong core và dùng `*:pending` cho bước trước.
Script đã thêm guard trước Maven/kết nối DB và ca kiểm từ chối repeatable trong
thư mục con. Runner chạy lại đạt; strict validation còn được kiểm trực tiếp bằng
Maven như ghi ở trên. Không tạo schema thủ công, baseline hoặc sửa history.

## Giới hạn và tiếp nối

Kiểm sâu trigger và quyền bảng runtime thuộc WP01-02;
không áp schema này vào môi trường dùng lâu dài trước khi cả hai task đạt.
Chưa nghiệm thu seed, staff/auth, tiền/tồn qua service, staging HTTPS hoặc mở bán.
Không chạy `repair`, `baseline`, `clean` hoặc sửa history để làm kiểm đạt.

Hướng dẫn vận hành và phục hồi: [migration lõi](core-migration.md).
