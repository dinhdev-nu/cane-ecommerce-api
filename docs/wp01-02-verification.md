# WP01-02 — Hồ sơ kiểm trigger và quyền runtime

Ngày: **10/11/2026**. Branch: `feat/wp01-02-data-protection`.
Trạng thái: **local đạt: 20 unit + 32 integration, 0 lỗi/bỏ qua; chờ PR/CI/merge**.

## Nguồn và thay đổi

- Root Cane-MVP: `04-DU-LIEU-VA-HA-TANG.md` §2–4, SQL/bộ kiểm schema tham chiếu;
  thiết kế dữ liệu 1.3 §5/8, bảo mật §7.4, xử lý §8 và kịch bản WP01 §3.3.
- V001 đã có đủ ba function/mười một trigger đúng thiết kế; kiểm sâu đạt không
  cần thay SQL nghiệp vụ. V001 không đổi so với commit phát hành `6a0e5b6`.
- Thêm `Grant-Runtime.sql` dùng psql, role theo biến định danh có quote, một
  transaction, preflight role/hạ tầng và cấp quyền trực tiếp theo thao tác/cột.
  Chạy lại thu hồi quyền cũ dư; không cấp mặc định cho đối tượng mới. History chỉ
  được SELECT để kiểm phiên bản/readiness, không ghi/sửa/xóa history.
  Không tự tạo/đổi credential, sửa membership hay bật auto inbox/job.
- [Runbook/ma trận quyền](data-protection.md) ghi phạm vi manual; quyền khóa hàng
  worker không cấp sửa tiền/tồn. Runtime không có quyền vận hành seed/staff.

## Kiểm thực tế

```powershell
.\mvnw.cmd -B -ntp spotless:apply test-compile failsafe:integration-test failsafe:verify '-Dit.test=DataProtectionIT'
pwsh -NoProfile -File scripts/Test-Backend.ps1
```

Lượt tập trung: **9 integration đạt, 0 lỗi, 0 bỏ qua**, PostgreSQL 18.6 theo image
khóa; thời gian Maven 46 giây, kết thúc 14:03:48 GMT+7. Sau đó bổ sung assertion
review metadata vẫn sửa được sau hoàn, quyền SELECT history cho readiness cùng
ca từ chối ghi/sửa/xóa history và chạy lại toàn bộ runner.

Runner cuối đạt **20 unit + 32 integration, 0 failures/errors/skipped**, build JAR
thành công lúc **14:18:09 GMT+7**, Maven **4 phút 02 giây**. Đã đối chiếu XML
Surefire/Failsafe. Project `cane-prep02-check-dc3ebbd80db8` đã dọn container/network/
volume; không còn container Testcontainers mang nhãn thử, không có image mới so
với inventory trước runner. Log local nằm ngoài Git.

Đã tự review diff, kiểm liên kết file/whitespace, xác nhận V001 không đổi. Phần
PR/check/merge main ghi tiếp theo kết quả GitHub; chưa xem CI xanh là smoke staging.

Các nhóm kiểm:

1. UPDATE/DELETE/TRUNCATE của items/event/ledger và TRUNCATE receipt bị `55000`
   bằng chủ schema thực sự có DML; dữ liệu/biên nhận giữ nguyên.
2. ID/phương thức/tiền/reference/bank receipt/người/mốc gốc và DELETE receipt bị
   chặn; cờ chính/liên kết cash/applied/refunded giữ đúng bất biến.
3. Bank phụ chưa hoàn đối soát sang đơn khác/xóa liên kết/xác minh–áp hợp lệ;
   thiếu xác minh hoặc áp khoản đã hoàn bị chặn.
4. Hoàn principal/supplemental/cash/bank/chưa rõ đơn hợp lệ; không đảo hoặc sửa
   mốc/chứng từ đã hoàn. Review metadata không bị khóa nhầm.
5. Ba timestamp được trigger đặt lại, không bịa actor hoặc event. Không tắt trigger.
6. Chạy script psql thật, chạy lại loại bỏ table/column grant dư; API/worker không
   có DDL/TRUNCATE/ownership/quyền ghi history/function/sequence hoặc quyền đối tượng mới.
7. SQL runtime có quyền thiếu báo `42501`, tách với lỗi trigger. Không đổi password/
   role/active/auth_version hoặc gia hạn phiên qua API role; worker không đọc hash
   staff/session và không ghi tiền/tồn/settings hoặc nguồn outbox.
8. API ghi các bảng được phép; worker khóa đơn/receipt, xếp cảnh báo, cập nhật
   lease/gửi và dọn phiên hết hạn được, với trigger bật và credential riêng.
9. Role có membership, role thiếu/trùng bị từ chối trước khi đổi grant; không
   để thay đổi grant dở dang hoặc để membership thử tồn tại sau test.

## Giới hạn

Đây là kiểm cơ chế SQL/quyền, chưa nghiệm thu nghiệp vụ resolve/refund hay worker
gửi provider. Service còn phải kiểm guard/quan hệ chéo, snapshot và quyền hiện hành;
trigger không tự ghi lịch sử/outbox. Nhánh auto cần quyền riêng khi triển khai.
Seed/staff/auth/transaction nền còn tiếp nối theo task; staging HTTPS chưa kiểm.
