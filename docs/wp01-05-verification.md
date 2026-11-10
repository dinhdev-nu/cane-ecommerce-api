# WP01-05 — Hồ sơ kiểm JDBC/transaction/khóa

Ngày **10/11/2026**, branch `feat/wp01-05-transactions`.
Trạng thái: local đạt; chờ PR/CI/merge.

## Nguồn và phạm vi

Thiết kế kỹ thuật §5.2, luồng §1, dữ liệu §7, ADR-002; dữ liệu/hạ tầng gốc;
backlog WP01-05 và nhóm kiểm §3.12/3.13. [Runbook](transactions.md).

- Một manager/DataSource, write READ COMMITTED, nested write join, read snapshot
  REPEATABLE READ/READ ONLY thực, từ chối isolation không tương thích.
- Advisory theo namespace/actor/key không chờ, SQL khóa ở Orders/Payments/Catalog/
  Admin. Không sửa V001 hoặc grant matrix. Driver PostgreSQL compile scope để dùng
  metadata constraint có cấu trúc, phiên bản/đóng gói runtime không đổi.
- Chỉ 40P01 retry tối đa 3 lượt tổng cộng, transaction mới sau rollback và jitter
  ngoài transaction. UNIQUE/unknown commit/business lỗi không tự retry. Theo dõi
  completion để callback Spring lỗi sau commit không thể chạy lại dữ liệu đã ghi.
- Hook sau commit cho tác dụng không ghi DB; lỗi callback không đổi kết quả commit,
  không retry hoặc log exception/input. Không thêm route sản phẩm/job nghiệp vụ.

## Kiểm cuối bằng runner chuẩn

`pwsh -NoProfile -File scripts/Test-Backend.ps1` đạt **43 unit + 81 integration**,
không failures/errors/skipped, format và build JAR thành công. Maven **4 phút 48
giây**, kết thúc **16:30:22 GMT+7**. Log ngoài Git `.local/wp01-05-release.log`.
XML từng suite đã đối chiếu; JAR không chứa controller/probe/test thử.

Compose project `cane-prep02-check-93d74adb280e` đã dọn container/network/volume;
không còn container Testcontainers CANE và không có image mới so inventory trước
chạy. Không prune tài nguyên khác. V001 và ma trận grant giữ nguyên.

## Phạm vi ca kiểm

**43 unit + 30 integration, 0 failures/errors/skipped**, Maven 59.380 giây, kết
thúc 16:25:15 GMT+7. Log ngoài Git `.local/wp01-05-privacy.log`.

Kiểm tập trung và runner cuối gồm completion chưa xác định, callback Spring sau commit mang cause
40P01 và nhánh rollback lỗi không lộ ngoại lệ SQL qua log/HTTP.
7 unit mới kiểm budget retry, không retry SQLSTATE khác/nghiệp vụ/unknown commit và
hash tách namespace/actor/phần ghép. 21 PostgreSQL integration mới kiểm:

- Hai lời gọi service cùng pid/transaction, WRITE READ COMMITTED; read-only snapshot
  giữ nhất quán khi writer khác commit, SQL ghi bị từ chối 25006 và connection dùng
  tiếp đúng isolation. Nested failure dù caller catch vẫn rollback outer.
- Rollback ở 7 biên sau stock, receipt, event, neo, ledger, outbox và trước commit;
  so dữ liệu sáu bảng trước/sau, không chạy hook khi rollback. Trigger bật bình thường.
- Hai connection/barrier dùng tập ID/SKU đảo thứ tự vẫn khóa đơn → receipt → SKU
  đúng thứ tự DB, không deadlock/retry; có UUID vượt biên signed và SKU giống SQL.
  Settings khóa đủ 16 dòng riêng, từ chối khi thiếu; lock/hook ngoài write bị chặn.
- Advisory cùng actor/key bận bị từ chối; actor/namespace khác độc lập; commit và
  rollback đều nhả khóa. Full key/hash còn do caller ở task 13 kiểm.
- UNIQUE key đơn/bank reference/source giữ tên constraint; toàn transaction rollback
  xong mới đọc winner bằng connection dùng được, không nhầm constraint thành replay.
- Deadlock 40P01 thật: 2 caller cố ý đảo thứ tự trong fixture; có 3 transaction ID,
  1 retry sau cleanup, key/neo giữ nguyên, 2 kết quả/hook commit và không ghi lặp.
- Hook lỗi có cause 40P01 vẫn không retry commit; connection độc lập lấy được khóa
  chứng minh đã nhả trước callback. Log cảnh báo cố định, không throwable riêng.
  Callback Spring đăng ký ngoài helper lỗi sau commit cũng không làm chạy lại;
  fault trước commit được tiêm ở `beforeCommit` thực, không chỉ trước return callback.

Thêm một HTTP case cho 409 REQUEST_IN_PROGRESS/Retry-After=1 dùng cùng error/no-store.
Các endpoint/rule thử chỉ trong test, production vẫn deny-all.

Runtime chặn log ngoại lệ thô của TransactionTemplate/JdbcTransactionManager,
giữ log cảnh báo cố định và requestId; không ghi nội dung lỗi SQL khi rollback
ghi đè lỗi ban đầu. Ca HTTP kiểm cả message, key-value và throwable.

## Giới hạn

Đây là cơ chế nền; không Verified toàn BR-011/NFR-005/T95/T96/T116/T117. Phân loại
và đọc lại UNIQUE không tự cấp quyền replay. Service nghiệp vụ phải kiểm full hash,
auth/guard, tập liên kết receipt và tiền/tồn/event/outbox ở các đường dùng thật.
Chưa có staging/HTTPS theo xác nhận chủ dự án; không suy local/CI thành nghiệm thu đó.
