# Session nhân viên và quyền hiện hành

WP01-09 triển khai cơ chế session trong Admin, theo bảo mật §1–2, thiết kế database
§3.2 và kịch bản nền §3.8 của `cane-mvp/`. Chưa có API me/logout/login; các API này
tiếp nối ở WP01-10/11. Không tạo route thử trong JAR sản phẩm.

## Cấp, thay và thu hồi

`SessionToken` sinh 32 byte SecureRandom, base64url không padding (43 ký tự), kiểm
chuỗi canonical trước sử dụng. `staff_sessions` chỉ lưu SHA-256 token, staff UUID,
auth_version và hai mốc thời gian. Không lưu token thô/hash trong log, Redis, event,
biên nhận hoặc JSON API. Các kiểu chứa token/hash có `toString()` che dữ liệu.

Login phải xác minh password/trạng thái khóa rồi gọi `StaffSessionService.issue`
trong **transaction ghi của login**. Hàm không tự mở transaction riêng. INSERT
kiểm/khóa staff active và auth_version đã xác minh; dữ kiện đổi khi chờ khóa bị từ
chối. SQL dùng cùng mốc `statement_timestamp()` cho created_at và expires_at=+8h.
Xóa hash cookie phiên trình duyệt cũ trong cùng transaction; không xóa phiên của
trình duyệt khác. Rollback giữ phiên cũ và không để lại phiên mới.

Chỉ sau khi transaction ngoài commit thành công, HTTP adapter gọi
`SessionCookies.issue`. Helper từ chối khi còn transaction đang hoạt động. Cookie
`__Host-cane-session`: Secure, HttpOnly, SameSite=Lax, Path=/, không Domain;
Max-Age làm tròn xuống phần hạn còn lại, tối đa 28.800 giây. DB quyết định hạn,
GET/poll không UPDATE hay cấp lại cookie. Token/raw IssuedSession không phải DTO.

`revoke(token)` xóa đúng hash bằng transaction, gọi lại hoặc token sai định dạng
không tạo phiên. Logout kết hợp kiểm CSRF/origin và xóa cookie tại WP01-10. Job
dọn session hết hạn thuộc gói worker sau; không dựa vào job để quyết định hết hạn.

## Đọc và kiểm quyền

`StaffSessionFilter` nằm trong Spring Security chain sau CSRF, không là servlet
filter thứ hai. Nó chỉ xác thực cookie tại route admin; không dùng Bearer, JWT,
HttpSession, nhớ đăng nhập hoặc Redis làm nguồn xác thực. Duplicate cookie bị từ
chối. SecurityContext chỉ tồn tại trong request và được xóa sau chain.

Một SELECT JOIN đọc staff/session/setting bank cùng mốc DB kiểm hạn. Điều kiện:
staff active, auth_version khớp, expires_at > thời điểm kiểm. Bằng hạn đã hết;
locked_until do thử sai login không thu hồi phiên đang hợp lệ. Role lấy hiện tại;
Owner có canConfirmBank=true, Operator theo giá trị boolean hiện tại trong SQL.
Setting thiếu/sai kiểu làm lookup lỗi an toàn, không tự đoán quyền.

Service nhận `StaffPrincipal` từ SecurityContext, không bind actor/role từ body.
`StaffAuthorization.requireCurrent/requireOwner/requireBankConfirmation` đọc lại
phiên/quyền trước replay và guard nghiệp vụ; với thao tác ghi, gọi trong transaction
READ COMMITTED của use case. Không dùng ROLE trong context hoặc kết quả cũ để bỏ
kiểm quyền mới. Quyền bank thường không cấp quyền đối soát/hoàn tiền và vẫn phải
kiểm điều kiện của đơn/receipt ở gói tương ứng.

Thiếu/hết/thu hồi phiên tại route cần phiên trả 401 `AUTH_REQUIRED`, xóa cookie cùng
scope. Sai quyền trả 403 `FORBIDDEN`. Lookup DB lỗi trả 503 `SERVICE_UNAVAILABLE`,
không xóa phiên theo suy đoán hoặc dùng quyền cache. Log lỗi lookup chỉ thông báo cố
định. Me/logout có ngoại lệ thiếu phiên theo contract, được gắn handler ở WP01-10;
login tự xác minh và thay phiên tại WP01-11. Các route sản phẩm khác vẫn deny-all.

## Kiểm

```powershell
.\mvnw.cmd -B -ntp verify '-Dit.test=StaffSessionIT'
```

Unit kiểm entropy/định dạng/hash, biên hạn chính xác, thuộc tính/thời hạn cookie và
quyền thay đổi. Integration dùng API runtime credential thật, SQL/HTTP thật, phiên
fixture, thay/rollback/thu hồi, không sliding/HttpSession, active/version/quyền hiện
tại, mất DB bằng pause container riêng và tranh chấp staff lock. Probe/controller
chỉ trong test. [Hồ sơ kiểm](wp01-09-verification.md) ghi kết quả; auth API thật,
CSRF/origin và smoke HTTPS vẫn phải kiểm tiếp đúng task.
