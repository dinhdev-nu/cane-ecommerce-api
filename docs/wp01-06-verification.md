# WP01-06 — Hồ sơ kiểm seed chín SKU

Khởi đầu **10/11/2026**, tiếp tục **14/11/2026**, branch `feat/wp01-06-catalog-seed`.
Trạng thái: **hoàn thành ngày 14/11/2026**. [PR #7](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/7)
đã squash merge tại `5e8809ef2cf822419f011e9701b73437ae30b309`; cây local trùng bản merge.
[CI PR](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43547251715) và
[CI main đúng commit](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43547527199)
đều đạt. Cane-MVP cập nhật bằng chứng/quyết định SKU tại commit local `bc3b4c2`, không push.

Nguồn: dữ liệu/hạ tầng gốc, thiết kế dữ liệu §9.1, Storefront SCR-02, backlog
WP01-06 và nhóm kiểm §3.4. Chủ dự án đồng ý chín ID frontend làm SKU ngày 10/11;
giá/quy cách thật chưa chốt. [Runbook/lệnh/giới hạn](catalog-seed.md).

## Phạm vi

Manifest chính xác chín SKU; template không chạy được khi chưa điền dữ liệu thật,
fixture tách riêng local/test. Seed qua Catalog application service/transaction
chung, repository bind SQL; CLI riêng không HTTP/worker/Redis. Chỉ thêm thiếu, tồn
0; không cập nhật dòng hiện hữu/settings. Không thay V001 hoặc grant runtime.

## Kiểm

Kiểm tập trung ngày 10/11 đạt **64 unit + 10 integration**, không failures/errors/
skipped; Maven 1 phút 04 giây, kết thúc 16:50:53 GMT+7. Log ngoài Git
`.local/wp01-06-focused-final.log`. Lượt runner toàn bộ ngày 10/11 bị dừng theo yêu
cầu chủ dự án, không được tính là kết quả đạt.

Ngày 14/11 đọc lại tài liệu/skill, đối chiếu Git, giữ nguyên hai diff có sẵn ở
checkout backend gốc. Docker Desktop khởi động lại đúng bộ khóa. Windows dành
riêng cổng PostgreSQL local mặc định; lượt kiểm dùng cổng loopback trống qua biến
môi trường tiến trình, không sửa Compose, secret hoặc cấu hình đã commit.

Runner chuẩn ngày 14/11 đạt **64 unit + 91 integration**, không failures/errors/
skipped; format/compile/build JAR đạt. Maven 5 phút 13 giây, kết thúc **09:24:33
GMT+7**. Log ngoài Git `.local/wp01-06-resumed-verify.log`; tổng từng XML Surefire/
Failsafe đã đối chiếu. Compose project `cane-prep02-check-95beb9bd200a` đã dọn hết
container/network/volume; không còn Testcontainers CANE, không có image mới so với
inventory trước chạy. JAR không có fixture seed hoặc lớp/controller thử.

Tập chín SKU fixture đã so trực tiếp với ID catalog frontend, không khác biệt;
wrapper PowerShell parse đạt, V001 và grant runtime không thay đổi.

Ca kiểm gồm typed/unknown/duplicate/
giới hạn manifest; PostgreSQL tạo/chạy lại, giữ giá/tồn/config/timestamp, rollback
giữa chừng, tiếp tục phần dở, tranh khóa, từ chối runtime, checksum/schema chưa
chuẩn bị và chạy wrapper PowerShell thật qua packaged JAR.

## Giới hạn

Đây là phần seed của §3.4/T93/T94, chưa Verified toàn BR-006/011/014/NFR-005/009.
Nhập tồn Owner có event/ledger/neo và chống cộng lặp thuộc task 14. Dữ liệu thử
không trở thành dữ liệu mở bán; chưa seed production, chưa có staging/HTTPS.
