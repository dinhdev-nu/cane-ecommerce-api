# WP01 — Sửa hai điểm P2 sau audit

Ngày 14/11/2026, chủ dự án yêu cầu sửa P2 vận hành và P2 phân tách credential.
Thực hiện trên nhánh `feat/wp01-16-staging-verification`, cập nhật PR nháp #17.
Không gộp P3 smoke clock, không merge hoặc đóng WP01-16 khi chưa có staging/demo.

## P2 vận hành — retry sau khi DB phục hồi

Hai unit API/worker bỏ giới hạn ba lần start trong 60 giây, dùng
`StartLimitIntervalSec=0` và `RestartSec=30` với `Restart=on-failure`. Startup Java
vẫn fail closed khi DB/schema/quyền chưa sẵn sàng. Retry tiếp tục sau lỗi kéo dài;
stop thủ công không kích hoạt restart. Cấu hình sai cần vận hành dừng/sửa/start,
không lấy số lần restart làm dấu hiệu healthy.

`Test-SystemdRecovery.sh` chạy trên Linux CI với systemd thật, dùng bản sao hai
unit và drop-in fixture: user/path/executable thử, retry delay 1 giây, giữ nguyên
start-limit/restart policy. Dependency giả chưa sẵn sàng phải vượt ít nhất năm
lần chạy lỗi rồi hồi phục khi được bật; stop thủ công phải giữ inactive. Probe
không chạy JAR, không chứng minh DB hoặc VM staging. Unit thử/drop-in/thư mục
`/run/cane-systemd-check-*` riêng được dọn qua trap. `.service` giữ LF khi checkout.

Tham chiếu: [systemd start limits](https://www.freedesktop.org/software/systemd/man/latest/systemd.unit.html#StartLimitIntervalSec=),
[restart policy](https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html#Restart=).

Kiểm local: Git diff/whitespace và shell syntax. Máy Windows hiện chỉ có WSL
docker-desktop, không có systemd manager để chạy probe; kết quả thực thi systemd
được ghi sau khi CI trên commit tương ứng chạy xong. HTTPS/VM vẫn Not run.
