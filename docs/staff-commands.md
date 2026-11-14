# Mật khẩu và script nhân viên — WP01-07

Nguồn: hợp đồng §3, thiết kế bảo mật §1/2.1, dữ liệu §9.2 và nhóm kiểm §3.5.
Chỉ có Owner/Operator; không có API quản lý nhân viên hoặc tài khoản/mật khẩu mặc định.

## Chuẩn bị và lệnh

Build JAR, migrate/validate core trước. Dùng credential vận hành riêng qua môi
trường tiến trình `CANE_OPERATIONS_DB_URL`, `CANE_OPERATIONS_DB_USER`,
`CANE_OPERATIONS_DB_PASSWORD`, cùng cơ chế secret với seed Catalog. Kiểm đúng DB
đích trước chạy; không đưa credential vào argument hoặc Git. CLI kiểm checksum/schema,
không tự migrate/repair. Cần SELECT/INSERT/UPDATE staff_users, DELETE staff_sessions
và quyền đọc history; không bổ sung quyền staff cho API/worker.

Chạy từ repo backend trong terminal tương tác. `-Jar` tùy chọn chỉ JAR build cụ thể:

```powershell
pwsh -NoProfile -File scripts/staff/Manage-Staff.ps1 -Action create-owner -Username owner
pwsh -NoProfile -File scripts/staff/Manage-Staff.ps1 -Action create-operator -Username operator
pwsh -NoProfile -File scripts/staff/Manage-Staff.ps1 -Action reset-password -Username operator
pwsh -NoProfile -File scripts/staff/Manage-Staff.ps1 -Action set-role -Username operator -Role owner
pwsh -NoProfile -File scripts/staff/Manage-Staff.ps1 -Action set-active -Username operator -Active false
```

Các tên trong ví dụ là lựa chọn của người vận hành, không được seed sẵn. Mật khẩu
mới được nhập hai lần bằng `Console.readPassword`, không echo. Bắt buộc có console;
pipe/redirect stdin không thay prompt. Không có tham số password, đọc password từ
env/file hoặc endpoint reset. Mật khẩu 15–128 Unicode code point, giữ khoảng trắng,
không trim/cắt. Buffer nhập được xóa sau dùng; không tuyên bố có thể xóa mọi bản
sao nội bộ của JVM/thư viện. Mật khẩu không được in hoặc lưu ra file.

Username trim/lowercase theo validator chung, dài 1–100; DB unique lower(username)
vẫn là ràng buộc cuối. ID UUID bất biến được trả trong kết quả, dùng cho audit sau
này; không lấy username làm khóa lịch sử.

## Hành vi và transaction

- `create-owner`/`create-operator`: tài khoản mới active, auth_version=1. Username
  đã tồn tại trả changed=false, giữ ID, password hash, role, active, bộ đếm khóa,
  created_at và phiên; không biến chạy lại thành reset hoặc nâng quyền.
- `reset-password`: thao tác có chủ đích, băm mật khẩu mới rồi tăng auth_version và
  xóa mọi phiên của đúng staff trong cùng transaction, kể cả mật khẩu nhập giống cũ.
- `set-role`/`set-active`: chỉ thay đổi thực mới tăng auth_version/xóa phiên; yêu
  cầu trùng trạng thái là no-op. Không sửa username/UUID hoặc xóa staff. Không tự
  reset bộ đếm/khóa login khi sửa mật khẩu/quyền.
- Mọi lệnh ghi lấy cùng advisory transaction lock trước khóa staff. Guard đếm Owner
  active dưới khóa chung; từ chối hạ role/vô hiệu Owner active cuối cùng. Hai lệnh
  đồng thời: bên tranh khóa dừng, người vận hành đọc kết quả rồi chạy lại; guard
  vẫn kiểm sau khi bên thắng commit. Lỗi rollback cả staff lẫn việc xóa phiên.
- Hash nằm ngoài transaction/khóa của lệnh. Bean `PasswordEncoder` duy nhất tại
  `PasswordConfiguration`: Spring Security Argon2id, memory 19 MiB, iterations 2,
  parallelism 1, salt ngẫu nhiên 16 byte, output 32 byte. Login ở task 11 dùng cùng
  bean, không tạo cấu hình hash khác. Đo tài nguyên trên máy triển khai trước phát hành.

CLI ghép context tối thiểu bằng PropertiesLauncher trong cùng JAR; không Boot
startup, HTTP, scheduler/worker hoặc Redis. Chỉ main CLI tắt log thư viện; output
cố định có staffId/role/active/authVersion/changed, không hash/password/URL/exception.
Exit code khác 0 thì dừng và rà argument, console, schema/quyền hoặc Owner cuối;
không tự sửa SQL để vượt guard. Nếu mất phản hồi sau commit, kiểm trạng thái trước
khi reset tiếp; reset là thao tác chủ đích, không có key chống lặp POST admin.

Không dùng runtime API/worker credential để vận hành staff. Service bảo vệ các
lệnh qua ứng dụng; tài khoản DDL/vận hành có quyền SQL trực tiếp phải được quản lý
ngoài dịch vụ. Thu hồi quyền hiện hành theo request và login sẽ kiểm tại task 09–11.

[Hồ sơ kiểm và giới hạn](wp01-07-verification.md).
