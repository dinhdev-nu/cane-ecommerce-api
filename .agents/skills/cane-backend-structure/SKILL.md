---
name: cane-backend-structure
description: "Tổ chức và kiểm soát cấu trúc backend Java/Spring Boot CANE trong cane-api-springboot: chọn mô-đun, package, file, tên lớp/hàm/biến và ranh giới transaction, dữ liệu, bảo mật, kiểm thử. Dùng khi thêm, sửa, refactor hoặc review backend CANE; frontend có skill riêng."
---

# CANE Backend Structure

Trạng thái: **chủ dự án cho phép bắt đầu WP01 ngày 10/11/2026, từng task tuần tự**.
Quyền sở hữu và ràng buộc theo thiết kế; cây/tên lớp là ví dụ để tổ chức code, không
phải scaffold bắt buộc.

Skill là tài liệu hỗ trợ và có thể còn thiếu sót; không ép code theo hướng dẫn chưa
phù hợp. Khi có ý tưởng cải tiến mà skill chưa đề cập, trình bày vấn đề, lợi ích và
tác động, rồi hỏi chủ dự án trước khi áp dụng. Sau khi thống nhất và có kết quả thực
tế, cải thiện skill tương ứng; yêu cầu và đặc tả hiện hành vẫn được giữ đúng.

## Nguồn và phạm vi

Đọc [cấu trúc tham chiếu](references/backend-structure.md) trước khi chọn vị trí code.
Đây là hướng dẫn của repo backend, không phải snapshot chứng minh tính năng đã có.
Các đường dẫn Java trong skill được tính từ gốc `cane-api-springboot/`.

Repo tài liệu `cane-mvp/` nằm cùng cấp với backend trong workspace thông thường.
Trong worktree, tìm lại repo tài liệu tại workspace gốc. Đọc phần tương ứng của:

- `01-HOP-DONG-NGHIEP-VU.md`: thẩm quyền nghiệp vụ cao nhất.
- `docs/03-technical-design.md`: mô-đun, hướng gọi, stack và cấu trúc.
- `docs/03-database-design.md`, `03-processing-design.md`, `03-api-design.md`,
  `03-security-design.md`, `03-operations-design.md`: hợp đồng của phần đang sửa.
- `docs/pm/backlog.md`, `docs/05-test-cases.md`, `docs/04-test-plan.md` và
  `docs/pm/milestone-plan.md`: đầu ra, kiểm thử, phụ thuộc và DoD.
- `docs/pm/toolchain-lock.md`: phiên bản; POM/BOM, Wrapper và image của repo thực thi.

Skill hướng dẫn cách triển khai, không tự thay đổi hợp đồng. Không bịa hoặc coi suy
đoán là sự thật. Phân biệt yêu cầu có nguồn, hiện trạng code và đề xuất. Yêu cầu
nghiệp vụ, schema, API, quyền hoặc quyết định kiến trúc không được tài liệu nhắc,
chưa rõ hay xung đột phải hỏi chủ dự án trước khi áp dụng. Theo chỉ dẫn người dùng,
chủ động tổ chức code trong thiết kế hiện hành; không cần duyệt từng tên lớp/hàm/
biến/package con. Lựa chọn tổ chức code không được bổ sung hành vi hoặc yêu cầu mới.

## Chất lượng code và cách cân nhắc pattern

Theo chỉ dẫn chủ dự án ngày 10/11/2026, cây mẫu chỉ để tham khảo. Giữ cấu trúc phù
hợp đang có; chỉ đổi khi có lý do cụ thể và không tạo đủ cây để khớp hình mẫu.
Ưu tiên code đúng nghiệp vụ, rõ trách nhiệm, dễ đọc, kiểm tra và bảo trì. Dùng design
pattern khi giải quyết vấn đề thực tế và giải thích được lợi ích/chi phí. Giữ phân
lớp và interface có trách nhiệm rõ; không áp công thức interface/Impl, port/adapter
hay lớp trung gian cho mọi lớp. Độ phức tạp cần thiết để bảo đảm tính đúng đắn được
chấp nhận; giữ đầy đủ transaction, khóa, bảo mật
và kiểm thử theo đặc tả. Nếu quy tắc trong skill làm code khó đọc mà không bảo vệ
một ràng buộc cụ thể, nêu điểm chưa phù hợp và đề xuất điều chỉnh với chủ dự án.

## Chọn mô-đun, lớp và file

Xác định dữ liệu/nghiệp vụ sở hữu code, lớp thực hiện nhiệm vụ gì và caller là ai.
Tìm phần có sẵn trước khi tạo một abstraction mới.

Quyền sở hữu mô-đun và bốn lớp theo thiết kế kỹ thuật §4.1–4.2. Tên lớp mẫu và cách
chia package con chi tiết trong skill/tham chiếu là ví dụ; chọn theo trách nhiệm và
code đang có, không đổi cấu trúc đang phù hợp chỉ để khớp ví dụ.

| Trách nhiệm | Vị trí |
|---|---|
| Ghép bean, profile, runtime, startup/readiness | `vn.cane.bootstrap` |
| SKU, giá, tồn, ledger, seed SKU | `vn.cane.catalog` |
| Quote, vùng giao, phí và tổng | `vn.cane.checkout` |
| Đơn/items, snapshot, vòng đời, quyền link một đơn | `vn.cane.orders` |
| Khoản thu, đối soát, hoàn và quy tắc tiền | `vn.cane.payments` |
| Staff, password/session, quyền và settings | `vn.cane.admin` |
| Transaction, audit, biên nhận, outbox, lỗi, HTTP/rate nền | `vn.cane.support` theo cơ chế cụ thể |
| Scheduler, claim/gửi và adapter provider | `vn.cane.worker` |

Trong mô-đun nghiệp vụ, dùng `api`, `application`, `domain`, `infrastructure` khi có
nội dung thực. Có thể chia tiếp theo năng lực như `admin/.../session` hoặc
`infrastructure/jdbc` nếu cần, không tạo đồng thời hai cách chia cho cùng trách nhiệm.

- `api`: controller, request/response DTO và mapping HTTP; không viết SQL hay điều
  phối nhiều bước ghi dữ liệu tại controller.
- `application`: use case/service, transaction, quyền/guard tại ranh giới dùng chung,
  contract cần để gọi hạ tầng. HTTP, worker và CLI dùng cùng service phù hợp.
- `domain`: kiểu/enum, policy và tính toán thuần của nghiệp vụ; không có Servlet,
  controller, JDBC, Redis, provider hoặc Spring bean phụ thuộc hạ tầng.
- `infrastructure`: repository JDBC/RowMapper và adapter của mô-đun. Vị trí CLI và
  cách bootstrap chọn khi làm task liên quan; không sao chép quy tắc nghiệp vụ vào
  script để bỏ qua thành phần dùng chung.

Cross-module caller đi qua contract/service công khai của mô-đun sở hữu. Không import
controller hoặc repository triển khai của mô-đun khác để cập nhật chéo bảng. Orders
điều phối thay đổi đơn kèm tiền/kho trong transaction chung; Payments giữ quy tắc tiền.
`support` không import ngược mô-đun nghiệp vụ; `bootstrap` ghép các phần; tránh vòng phụ thuộc.

## Quy ước Java và ví dụ đặt tên

Tài liệu quy định camelCase cho JSON và snake_case cho SQL. Với Java, bám code và
formatter hiện có; các ví dụ dưới đây hướng dẫn đặt tên, không cố định danh sách lớp.

- Package lower-case theo năng lực; Java file trùng tên type chính `PascalCase`.
  Một type công khai chính/file; DTO nhỏ gắn chặt một endpoint có thể cùng nhóm khi rõ.
- Đặt tên có nghĩa nghiệp vụ: `StaffSessionService`, `JdbcStaffSessionRepository`,
  `AdminAuthController`, `HmacOrderLinkSigner`. Đây là ví dụ, không phải scaffold bắt buộc.
- Hàm `lowerCamelCase` với động từ và mục đích: `findActiveSession`,
  `recordFailedLogin`, `verifyOrderLink`; boolean diễn đạt điều kiện như `isActive`,
  `canConfirmBank`. Tránh `process`, `handleData`, `doWork` khi có tên cụ thể hơn.
- Biến phân biệt đơn vị/ý nghĩa: `amountVnd`, `stockQty`, `expiresAt`, `staffId`,
  `linkVersion`, `requestHash`, `tokenHash`; không dùng chung `id`/`value` qua nhiều thực thể.
  Constant dùng `UPPER_SNAKE_CASE`, DTO HTTP camelCase và SQL snake_case đúng hợp đồng.
- Constructor injection, field phụ thuộc bất biến khi phù hợp; visibility hẹp nhất
  cần thiết. Không dùng singleton mutable state cho actor, request hoặc phiên.
- Không gom nhiều nghiệp vụ vào `CommonService`, `Utils`, `BaseRepository` hoặc một
  file lớn. Helper chỉ có một chủ quản ở lại chủ quản; tách khi trách nhiệm thay đổi
  độc lập hoặc cần kiểm riêng, không tách theo hạn mức số dòng máy móc.

## Ràng buộc dữ liệu và transaction

- Spring JDBC/SQL và mapping tường minh; bind giá trị, allowlist định danh động.
  Không thêm ORM, schema tự sinh hoặc repository tổng quát che mất SQL/khóa.
- Application service dùng `TransactionTemplate`, READ COMMITTED và cùng connection
  cho mọi ghi liên quan. Đọc nhiều bảng cần snapshot nhất quán theo thiết kế.
- Advisory actor/key trước khóa hàng; đơn theo ID tăng dần → receipt theo ID → SKU
  tăng dần. Settings có quy tắc khóa riêng; không giữ khóa settings rồi đi vào đơn/kho.
- UNIQUE làm transaction lỗi phải rollback trước khi đọc kết quả thắng. Phân biệt
  constraint idempotency, receipt, source_key và outbox; không coi tất cả là replay.
- Retry deadlock tối đa ba lần bằng transaction mới, jitter ngoài transaction, giữ
  key/body; không retry mù lỗi nghiệp vụ. Event, ledger, biên nhận và outbox dùng
  transaction của caller. Đọc nhiều bảng: một SELECT/CTE hoặc read-only REPEATABLE
  READ khi tách câu đọc; nhiều SELECT READ COMMITTED không tự có cùng snapshot.
- Không gọi Redis/email/Telegram/ngân hàng trong transaction giữ khóa. Tác vụ sau
  commit lỗi không được đảo kết quả nghiệp vụ đã commit.
- Tiền `long` nguyên VND với kiểm tràn/giới hạn JSON; số lượng/tồn nguyên có miền.
  Dùng `Instant`/`timestamptz`, UTC khi truyền; ngày nghiệp vụ Asia/Ho_Chi_Minh.
  Dùng Clock kiểm soát được; mốc DB dùng chung khi contract đòi một thời điểm.

## Migration, auth và thông tin nhạy cảm

- Migration ở `src/main/resources/db/migration/core/`, Flyway chạy riêng bằng role DDL.
  Runtime không migrate; không chạy schema.sql/data.sql. Không sửa version/checksum đã
  phát hành; thay đổi tiếp dùng version mới. Cách tách WP01-01/02 xem kế hoạch WP01.
- Session/role/settings/idempotency/outbox và dữ liệu nghiệp vụ nằm ở PostgreSQL.
  Redis chỉ public prices và rate; WP01 chưa làm cache prices.
- Auth/CSRF/quyền hiện tại trước replay; actor không lấy từ body. Bộ chống lặp admin
  khác hash đặt đơn; login/logout/PATCH giá/PUT settings không dùng neo POST admin.
- Cookie phiên và HMAC giữ đúng đặc tả; không tự thay bằng JWT/HttpSession hoặc dùng
  mã đơn cấp quyền. Script nhân viên dùng chung encoder, prompt kín và bảo vệ Owner
  active cuối cùng; không tạo endpoint quản trị nhân viên mới. Việc dùng Java CLI
  gọi cùng application service là đề xuất cần chốt, không phải cách triển khai script
  đã được tài liệu quyết định.
- Không log body, mật khẩu, cookie, CSRF, token/link, bind SQL hoặc PII thừa. DTO,
  error, audit và biên nhận dùng allowlist; HTTP API no-store kể cả lỗi. Secret thật
  ngoài Git; test fixture tách dữ liệu mở bán.

## Kiểm và bàn giao

Đặt test theo package chủ quản trong `src/test/java/vn/cane/`; `*Test` chạy Surefire,
`*IT` chạy Failsafe. Dùng fixture chung có mục đích, không bê toàn bộ smoke khung vào
mọi test. Constraint/khóa/rollback/đồng thời dùng PostgreSQL thật; hành vi Redis dùng
Redis thật. Hai tác vụ cạnh tranh dùng connection/transaction riêng và barrier.

Kiểm cả nhánh hợp lệ lẫn từ chối; đối chiếu response và DB/event/ledger/neo/outbox
khi có ghi. Không tắt trigger, bọc mọi request trong transaction test chung hoặc bỏ
integration khi thiếu Docker để có kết quả xanh. Dùng test harness nội bộ cho thành
phần chưa có endpoint; không mở endpoint thử ra sản phẩm.

Chạy unit/format và runner chuẩn `pwsh -File scripts/Test-Backend.ps1` khi thay đổi
code cần integration/build; giữ đúng toolchain, không kế thừa ngoại lệ cũ. Rà cấu trúc,
import, secret, migration/quyền, diff và bằng chứng đúng commit. Thay đổi chỉ gồm tài
liệu/skill kiểm frontmatter, liên kết và nội dung; không bắt buộc dựng lại hạ tầng.

Khi hoàn thành task, cập nhật tài liệu/ca/RTM đúng bằng chứng, giữ mã T01–T118 và
phân biệt kiểm nền với kiểm nghiệp vụ ở các WP sau. Không tự nâng toàn yêu cầu thành
Verified hoặc coi CI xanh là nghiệm thu staging. Dọn đúng tài nguyên thử CANE đã tạo,
không prune Docker toàn máy.
