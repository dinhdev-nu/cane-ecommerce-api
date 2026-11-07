# Kiểm khung backend PREP-02 — 07/11/2026

Trạng thái: **Khung đạt kiểm local và CI GitHub; local dùng ngoại lệ Docker được chủ dự án chấp thuận, CI dùng đúng bộ khóa; chưa xét Gate 3**.
Phạm vi: repo server và cập nhật tài liệu; frontend giữ nguyên.

## Nguồn thiết kế và quyết định

Đã đối chiếu thiết kế kỹ thuật/module, dữ liệu/migration/quyền, API, bảo mật,
luồng xử lý, vận hành, Test Plan, kịch bản WP01, backlog và bộ khóa công cụ trong `cane-mvp`.
Giữ năm mô-đun, JDBC/SQL, cùng bản build API/worker và migration riêng.
Các trị số pool/timeout local/test tại README được chủ dự án xác nhận trong phiên làm việc ngày 07/11.

Commit đầu tiên `580fff9` (`first commit`) chỉ thêm README và đã push lên
`dinhdev-nu/cane-ecommerce-api`, nhánh `main`, trước khi tạo khung.
Khung đã được rà diff, kiểm lại và push trong commit `b3428f9`; bằng chứng CI bên dưới gắn với commit này.

## Rà soát trước push

- Giữ kiến trúc, bộ phiên bản, pool/timeout và phạm vi PREP-02; chưa có nghiệp vụ WP01 hoặc cấu hình production.
- Không đưa `target/`, `.tools/`, `.local/`, log, env thật, image hoặc gói Maven tải về vào Git.
- Script chạy local chọn đúng wrapper Windows/Linux; env sinh trên Linux chỉ cho chủ file đọc/ghi.
- Kiểm quyền runtime phân biệt đúng tiền tố hệ thống `pg_`; schema thường bắt đầu bằng `pg` vẫn được kiểm quyền sở hữu/DDL, có ca hồi quy riêng.
- README dùng link repo tài liệu đọc được trên GitHub; không thay đổi frontend.

## Kết quả đã chạy

| Kiểm | Kết quả / bằng chứng |
|---|---|
| JDK | Temurin 25.0.5+8, `java -version` / `javac -version` |
| Maven Wrapper | 3.3.4, distribution Maven 3.10.0; tải và đối chiếu SHA-512 distribution, khóa SHA-256 trong properties; `mvnw.cmd --version` đạt |
| Dependency | Resolve thành công; BOM Spring Boot 4.1.2 khớp bộ khóa; effective POM/dependency tree đã xuất vào `.tools/` |
| Format / compile / package | `clean verify` đạt với Java release 25; executable JAR đã được tạo |
| Compose | `config --quiet`, `up --detach --wait` đạt trên PostgreSQL 18.6 / Redis 8.10.3 theo digest khóa; script init role/ACL thực thi trên container riêng |
| Unit | Surefire: 20 test, 0 lỗi/thất bại, 0 bỏ qua; báo cáo `target/surefire-reports/` |
| Integration | Failsafe: 10 test, 0 lỗi/thất bại, 0 bỏ qua trên PostgreSQL/Redis Testcontainers thật; báo cáo `target/failsafe-reports/` |
| Runtime | API có HTTP, không scheduler, chặn GET/POST thử bằng 403/no-store, không JSESSIONID; worker có scheduling nhưng không HTTP/servlet security. Cả hai không có Flyway runtime hoặc tài khoản mặc định |
| Quyền / lỗi | Runtime không tạo schema, migration role tạo/drop schema probe được; DB owner hoặc runtime bị cấp nhầm quyền CREATE trong schema đều bị từ chối khởi động. Sai password DB làm khởi động thất bại; Redis mất kết nối không dừng khung |
| Redis | Kết nối chưa xác thực bị NOAUTH, truy cập ngoài namespace bị NOPERM; đọc/ghi key thử trong namespace và TTL hữu hạn đạt |
| Flyway | Validate core rỗng qua PostgreSQL module đạt; không tạo schema nghiệp vụ. Chưa chạy V001 hoặc kiểm bất biến dữ liệu WP01 |
| Kiểm tĩnh | 7 file YAML hợp lệ/không trùng key, cú pháp 4 PowerShell và shell đạt; 38 Markdown/1.220 liên kết nội bộ và anchor hợp lệ |

`.tools/` và `target/` được Git bỏ qua; đây là kết quả local, không thay kết quả CI.
Maven có cảnh báo timeout khi truy vấn metadata ở Apache snapshots; lệnh trên vẫn thành công.
Dependency/plugin sử dụng release đã khóa hoặc parent/BOM; không dùng dependency snapshot.

Lệnh kiểm cuối: `pwsh -File scripts/Test-Backend.ps1 -AllowCurrentLocalDocker`;
Maven hoàn tất lúc **12:33:08 UTC ngày 07/11/2026**, `BUILD SUCCESS`.
Bản build local tương ứng mã nguồn khung trong commit `b3428f9`:

- JAR: `target/cane-ecommerce-api-0.1.0-SNAPSHOT.jar`, SHA-256 `d4c195ea5f76f8261cd9dec54cf443f447e98e977e0396c4f7513a6d0801e30f`.
- Manifest POM/wrapper/src: `.tools/prepush-build-inputs.sha256`, SHA-256 `48214141ae834cc51566cf5fa44cd1ba5600314028dc9b2ddd74ef1395f9eda8`.
- Log đầy đủ: `.tools/prepush-verify.log`; inventory `.local/last-docker-check.json` và biên nhận `.local/prepush-cleanup.json`.

## Kết quả CI GitHub

[Workflow Backend foundation — run 42373305195](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/42373305195)
đã **success** trên `ubuntu-24.04`, commit `b3428f9e071aa714cdd5693f6518b0d22c0e2ffe`.
Lượt này được kích hoạt bằng `workflow_dispatch`; workflow cũng khai báo trigger push `main` và pull request.
Hoàn tất ngày 07/11/2026 lúc **12:38:51 UTC**; không có bước lỗi cần sửa sau khi chạy CI.

| Kiểm trên runner Linux | Kết quả |
|---|---|
| Bộ khóa | Temurin 25.0.5+8, Maven 3.10.0, Docker Client/Engine 29.9.2/29.9.2, Compose 5.6.1; không dùng switch ngoại lệ |
| Format / build / test | `clean verify` đạt; 20 unit + 10 integration, 0 lỗi/thất bại/bỏ qua; PostgreSQL/Redis thật theo digest khóa |
| Dependency | Xuất effective POM và dependency tree thành công |
| Bằng chứng | Artifact `backend-foundation-evidence`, ID `13574866368`; đã tải và đọc báo cáo XML, kiểm đủ 30 test và hai file dependency |
| Dọn | Compose project kiểm đã down cùng volume/network; các bước post-action Docker/Compose và job hoàn tất thành công trên runner dùng riêng |

CI này chứng minh khung backend trên bộ công cụ mục tiêu. Chưa kiểm FE, nghiệp vụ WP01,
NFR/tải, staging/production hoặc các điều kiện còn lại của PREP-02/Gate 3.

## Ngoại lệ Docker và dọn tài nguyên

Docker thực tế: **Client/Engine 27.3.1; Compose 2.30.3-desktop.1**.
Sau khi xem sai khác, chủ dự án cho phép dùng bộ hiện có để hoàn thiện kiểm local;
chủ dự án sẽ cập nhật bộ mục tiêu **29.9.2 / 5.6.1** sau.
Switch ngoại lệ chỉ chấp nhận bộ local này, không được áp trong GitHub Actions.
Kết quả local không thay kiểm Docker mục tiêu; bằng chứng bộ mục tiêu đã có trên CI Linux ở trên.

Đã dọn Compose project của lượt rà trước push `cane-prep02-check-a9a057b75d2b`:
container/network/volume được xóa sau lượt kiểm.
Testcontainers đã đóng container thử và helper; không còn tài nguyên mang label phạm vi CANE.

Đã đối chiếu inventory trước kiểm, kiểm không có container sử dụng rồi xóa đúng ba image tải mới:
PostgreSQL, Redis và `testcontainers/ryuk:0.14.0`. Không dùng force/prune.
**Cả 7 image có trước lượt này vẫn còn nguyên ID**; các container dự án khác được giữ nguyên.
Cấu hình và bằng chứng dựng lại được giữ; `.local/dev.env` không được Git theo dõi.

## Phần tiếp nối

- Sau khi chủ dự án cập nhật Docker, chạy lại lệnh chuẩn không có switch ngoại lệ để kiểm đúng bộ khóa.
- Khung server đã push; CI đã chạy đạt trên bộ khóa. Các lượt CI tiếp theo được theo dõi tại [Actions](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/workflows/ci.yml).
- Frontend không thay đổi, chưa áp hoặc kiểm bộ công cụ FE trong lượt này.
- Chưa triển khai WP01 hoặc nghiệm thu nghiệp vụ; PREP-02 tổng thể và Gate 3 còn các điều kiện riêng.
