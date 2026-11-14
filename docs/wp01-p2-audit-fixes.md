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
lượt chạy rồi hồi phục khi được bật; stop thủ công phải giữ inactive. Probe
không chạy JAR, không chứng minh DB hoặc VM staging. Unit thử/drop-in/thư mục
`/run/cane-systemd-check-*` riêng được dọn qua trap. `.service` giữ LF khi checkout.

Tham chiếu: [systemd start limits](https://www.freedesktop.org/software/systemd/man/latest/systemd.unit.html#StartLimitIntervalSec=),
[restart policy](https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html#Restart=).

Kiểm local: Git diff/whitespace và shell syntax. Máy Windows hiện chỉ có WSL
docker-desktop, không có systemd manager để chạy probe; kết quả thực thi systemd
được ghi sau khi CI trên commit tương ứng chạy xong. HTTPS/VM vẫn Not run.

CI [run 43584929731](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43584929731),
commit `195cbe9`: bước systemd đạt trong 18 giây. Log xác nhận cả API và worker
vượt ba lần lỗi, hồi phục khi dependency được bật và giữ inactive sau stop.
Toàn bộ CI đạt, tổng 4 phút 58 giây, job verify 4 phút 55 giây; gồm runner nền
và kiểm template staging local. Cảnh báo Node 20 của action khóa vẫn là cảnh báo,
không có lỗi kiểm.

## P2 phân tách credential — runtime role cấu hình được

CLI nhập tồn không còn hardcode hai tên runtime. Tiến trình vận hành phải cung
cấp `CANE_API_DB_USER` và `CANE_WORKER_DB_USER` từ cùng cấu hình tin cậy đã cấp
quyền bằng `Grant-Runtime.sql`. Hai role phải tồn tại/khác nhau; thiếu hoặc sai
thì fail closed. Kiểm login/current role và membership trực tiếp/gián tiếp để
không dùng runtime hoặc login có thể nhận role runtime làm credential vận hành.
Không thêm bảng/migration, quyền DML/DDL hoặc buộc role vận hành tối thiểu làm
schema owner. Đây là guard chống dùng nhầm credential trong CLI tin cậy, không
phải cơ chế xác thực HTTP hoặc chống người đã có credential DB tự chạy SQL.

Kiểm hồi quy dùng PostgreSQL thật: rename API/worker, mapping thiếu/không tồn tại/
trùng nhau, credential tối thiểu riêng nhập/replay thành công; thêm membership
runtime thì phải từ chối ngay cả trước replay. Giữ ca Owner/schema owner hiện có.
Kết quả thực thi và giới hạn bên dưới; không dùng kết quả test cũ cho code mới.

Ca đổi tên chạy trên CLI cũ tại `195cbe9`: 2 ca, 1 failure đúng assertion API
đáng lẽ bị từ chối nhưng exit code là 0; 0 errors/skipped. Worker vốn thiếu quyền
nhập tồn nên đã bị từ chối. Bằng chứng `.local/wp01-p2-regression-before.log`;
CLI bản sửa được khôi phục qua finally ngay sau lượt kiểm âm tính này.

Lượt runner chuẩn đầu bị Windows chặn bind cổng loopback 55432 trước Maven;
project `cane-prep02-check-3270768ab12d` đã dọn cả container/network/volume. Không
thay dev.env hoặc cấu hình dự án; lượt tiếp dùng cổng do TCP listener cấp qua môi
trường process (PostgreSQL 52724, Redis 52725), giữ nguyên runner chuẩn/toolchain.
Lượt này làm Spring test ưu tiên cổng Redis process thay cho default property của
Testcontainers, gây WRONGPASS ở ba suite (6 failures/19 errors); không phải bằng
chứng code đạt. Project `cane-prep02-check-4a53a6214277` đã dọn. Không sửa logic
ứng dụng để che lỗi môi trường.

Lượt cuối dùng override Compose riêng `.local/wp01-p2-ports.yaml` cho host ports
ngẫu nhiên qua `COMPOSE_FILE`, không xuất cổng Redis/PG vào Java. `MAVEN_ARGS=-o`
chỉ dùng dependency đã cache; vẫn chạy nguyên runner chuẩn/clean/verify, không bỏ
kiểm. Toolchain đúng Temurin 25.0.5+8, Maven 3.10.0, Docker 29.9.2, Compose 5.6.1.

**159 unit + 232 integration đạt, 0 failures/errors/skipped**, XML 19 unit suite/
16 IT suite đã đối chiếu. `InitialStockIT` đủ 22 ca đạt. Format/build/JAR đạt;
Maven 4 phút 02 giây, kết thúc 17:41:37 GMT+7. Log
`.local/wp01-p2-final-verify-isolated.log`, JSON `target/foundation-verification.json`
ghi SHA nền `195cbe9`/dirty=true đúng thời điểm kiểm trước commit. Project
`cane-prep02-check-4c577bfbc4af` đã dọn container/network/volume. Hai diff cũ ở
checkout gốc giữ nguyên checksum, không sửa V001/grants/settings/POM.

CI của commit CLI cuối được truy vết tại [Checks PR #17](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/17/checks)
và RTM Cane-MVP §7. Run `195cbe9` ở trên chỉ xác nhận commit vận hành. Chưa merge,
chưa hoàn thành HTTPS/VM/demo, không coi probe systemd CI là nghiệm thu staging.
