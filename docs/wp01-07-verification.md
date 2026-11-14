# WP01-07 — Hồ sơ kiểm mật khẩu và script staff

Khởi đầu **14/11/2026**, branch `feat/wp01-07-staff-commands`.
Trạng thái: hoàn thành local/PR/main CI; đã merge [PR #8](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/8).

Phạm vi: encoder Argon2id dùng chung, prompt console kín/xác nhận, tạo Owner/Operator
không ghi đè khi chạy lại, đổi password/role/active thu hồi phiên nguyên tử và guard
Owner active cuối cùng qua khóa chung. [Runbook](staff-commands.md).

Kiểm tập trung ngày 14/11: **71 unit + 13 PostgreSQL integration đạt**, không lỗi/
bỏ qua; format/build JAR đạt, Maven 59,647 giây, kết thúc **09:51:43 GMT+7**.
Log ngoài Git `.local/wp01-07-focused.log`.

Đã gọi adapter `ConsolePasswordPrompt` thật bằng probe ngoài Git qua PTY Windows:
hai prompt không echo, xác nhận khớp và trả đúng buffer; probe kết thúc exit 0.
Lượt xác nhận không khớp bị từ chối, exit 1; output cũng không echo mật khẩu.
Đây là kiểm adapter console riêng, không tuyên bố đã nhập mật khẩu tương tác qua
wrapper vào database thật. Integration kiểm wrapper/JAR thật cho đổi trạng thái và
từ chối password qua redirected stdin; đường password CLI gọi service/encoder/DB
thật với prompt fixture và kiểm buffer được xóa cả khi validation thất bại.

Các ca còn gồm Argon2id/độ dài/code point/space/salt, tạo/chạy lại không đổi bất kỳ
cột/phiên, reset/role/active tăng version/thu hồi đúng phiên, no-op, Owner cuối,
hai lệnh đồng thời trên connection riêng/barrier, rollback sau xóa phiên và từ chối
credential runtime/argument password.

Runner chuẩn ngày 14/11 đạt **71 unit + 104 integration**, không failures/errors/
skipped; format/build JAR đạt, Maven 5 phút 07 giây, kết thúc **09:58:54 GMT+7**.
Log ngoài Git `.local/wp01-07-release.log`; XML đã đối chiếu. Project Compose
`cane-prep02-check-e45cf5316847` đã dọn container/network/volume, không còn
Testcontainers CANE hoặc image mới so inventory. JAR không có fixture/probe/lớp
thử. V001/grant runtime không đổi; diff và liên kết local đã kiểm.

Commit branch `4f951cd7eb997630becf8ac7bee02f2e4a5ae595`; merge main
`7d50084d97c6405828f818199b8b94a7b0ab5afc`. [PR CI](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43549352379)
và [main CI đúng SHA](https://github.com/dinhdev-nu/cane-ecommerce-api/actions/runs/43549751727)
đều thành công (2 phút 51 giây và 2 phút 34 giây). Đã đối chiếu Git local với main.
Tài liệu Cane-MVP commit local `b78d554fdb2948079a1a2a0df289da020752b593`, không push.

Login/session/quyền HTTP/CSRF và HTTPS staging thuộc các task tiếp theo; chưa tạo
tài khoản hoặc mật khẩu production.
