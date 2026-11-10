# CANE — Kế hoạch thực hiện WP01

Ngày lập: **10/11/2026** · Phiên bản: **1.0**.
Trạng thái: **Chủ dự án cho phép bắt đầu ngày 10/11/2026; triển khai/kiểm tuần tự**.
Đây là kế hoạch thực thi trong repo backend, dẫn tới yêu cầu/thiết kế chính thức;
không thay backlog, DoD hoặc kết quả nghiệm thu trong repo tài liệu.

Theo yêu cầu chủ dự án: **không bịa; hỏi khi yêu cầu nghiệp vụ, hệ thống hoặc kiến
trúc còn thiếu, chưa rõ hay mâu thuẫn**. Chủ động chọn giải pháp kỹ thuật phù hợp.
Kế hoạch phân biệt hiện trạng có bằng chứng, yêu cầu có
nguồn và đề xuất chờ chốt; không dùng đề xuất để tự mở rộng phạm vi.

Đã ghi nhận chỉ dẫn ngày 10/11/2026: cây mẫu là tham khảo; giữ cấu trúc phù hợp,
chỉ thay khi có lý do. Ưu tiên code chuẩn, đúng và dễ bảo trì; cân nhắc design pattern
theo vấn đề thực tế, chấp nhận độ phức tạp cần thiết để bảo đảm chất lượng. Không ép
code theo skill hoặc giản lược các ràng buộc đã đặc tả. Ngày 10/11/2026, chủ dự án đã yêu cầu bắt đầu WP01, đọc lại tài liệu/skill trước
khi code và tiếp tục hỏi khi có yêu cầu còn thiếu hoặc chưa rõ.

Người dùng cho phép chủ động tổ chức code theo thiết kế phân lớp/interface hiện
hành; không cần duyệt từng tên lớp/hàm/biến/package con. Quyết định nghiệp vụ,
schema, API, quyền hoặc kiến trúc chưa có trong tài liệu vẫn phải hỏi trước.

Skill là hướng dẫn hỗ trợ, có thể còn thiếu sót và được cải thiện trong quá trình
làm. Chỉ dẫn mới nhất ngày 10/11/2026 cho phép chủ động chọn cách giải quyết kỹ
thuật và báo khi có điểm đáng lưu ý; không cần xin duyệt vì skill chưa đề cập.
Cập nhật skill theo thực tế, không tự thay yêu cầu/đặc tả bằng một quy tắc mới.

## 1. Kết quả khảo sát và phạm vi lượt này

Đây là snapshot tại lượt khảo sát ban đầu; Git/GitHub, toolchain và kết nối hạ tầng
phải kiểm lại ngay trước triển khai, không coi các quan sát này là trạng thái live.

- Backend local ở `main`, HEAD `dc7d0c4`, remote
  [dinhdev-nu/cane-ecommerce-api](https://github.com/dinhdev-nu/cane-ecommerce-api).
  Đã đọc POM, toàn bộ lớp Java/test khung, YAML/profile, CI, Compose, script local,
  init role/ACL và hướng dẫn migration. Chưa có controller/service/repository nghiệp vụ.
- Maven một module, Java 25/Spring Boot 4.1.2, JDBC/TransactionTemplate; đã có
  Security, Redis, Validation, Flyway PostgreSQL và Bouncy Castle. Không cần dựng
  lại dự án hoặc thêm ORM/framework khác. Phiên bản theo bộ khóa, không nâng tự phát.
- Có `CaneApplication`, `RuntimeModeInitializer`, `InfrastructureStartupCheck`,
  `SecurityConfiguration`, `WorkerConfiguration`, `ClockConfiguration`; các mô-đun
  nghiệp vụ chỉ có package-info. Không có V001 SQL, seed, staff CLI hoặc auth API.
- `RuntimeModeInitializer` chỉ hỗ trợ local/test; API deny-all, worker có scheduler
  nhưng chưa có job. Kiểm kết nối/quyền DDL chưa phải readiness schema hoặc fallback rate.
- CI và test PREP-02 đã có runner, báo cáo lịch sử 20 unit + 10 integration. Lượt
  chuẩn bị này chưa chạy lại Maven/CI/integration và không sử dụng số cũ làm kết quả mới.
- `README.md`, `docs/prep02-verification.md` có diff từ trước. Giữ nguyên; phân loại
  riêng trước commit, không tự stage hoặc viết đè. Một số câu trong hồ sơ PREP-02 là
  trạng thái lịch sử; quyết định Gate 3 hiện hành đọc ở repo tài liệu.
- Đã kiểm repo bằng tab GitHub đang mở trong Chrome: main ở `dc7d0c4`, có phiên chủ
  repo, 0 PR mở/0 PR đóng tại lúc quan sát. Chưa tạo branch/commit/push/PR/merge.
- Kiểm nhẹ Docker: Client 29.9.2, chưa kết nối được pipe Docker Desktop Linux Engine.
  Chưa khởi động Docker hay dựng container trong lượt chuẩn bị; kiểm lại khi triển khai.
- Đầu ra lượt này chỉ gồm AGENTS, skill cấu trúc/tham chiếu/metadata và kế hoạch này.

## 2. Nguồn phải đọc theo phần việc

| Nội dung | Nguồn chính |
|---|---|
| Thẩm quyền nghiệp vụ, phạm vi | [Hợp đồng nghiệp vụ](../../cane-mvp/01-HOP-DONG-NGHIEP-VU.md), [SRS](../../cane-mvp/docs/01-srs.md), [Use Case](../../cane-mvp/docs/01-use-case.md) |
| Kiến trúc, mô-đun, hướng gọi | [Thiết kế kỹ thuật](../../cane-mvp/docs/03-technical-design.md), [ADR-001](../../cane-mvp/docs/adr/0001-kien-truc-va-java-spring-boot.md), [ADR-002](../../cane-mvp/docs/adr/0002-spring-jdbc-va-transaction-sql.md) |
| Bảng/cột/check/index/trigger/migration/seed | [Dữ liệu và hạ tầng gốc](../../cane-mvp/04-DU-LIEU-VA-HA-TANG.md), [nghiệm thu/triển khai gốc](../../cane-mvp/05-NGHIEM-THU-VA-TRIEN-KHAI.md), [thiết kế database](../../cane-mvp/docs/03-database-design.md), [SQL tham chiếu](../../cane-mvp/cane-mvp.sql), [kiểm schema tham chiếu](../../cane-mvp/tests/cane-schema-check.sql) |
| Transaction/replay/nguồn event và outbox | [Luồng xử lý](../../cane-mvp/docs/03-processing-design.md), [API chung](../../cane-mvp/docs/03-api-design.md#common) |
| Auth/CSRF/HMAC/rate/secret | [Bảo mật](../../cane-mvp/docs/03-security-design.md), [API auth](../../cane-mvp/docs/03-api-design.md#auth), [ADR-003](../../cane-mvp/docs/adr/0003-redis-cache-prices-va-gioi-han-tan-suat.md) |
| Task và điều kiện đạt | [Backlog WP01](../../cane-mvp/docs/pm/backlog.md#wp01-tasks), [16 nhóm kiểm](../../cane-mvp/docs/05-test-cases.md), [Test Plan](../../cane-mvp/docs/04-test-plan.md), [DoD](../../cane-mvp/docs/pm/milestone-plan.md#dod-task) |
| Runtime/staging/toolchain | [Vận hành](../../cane-mvp/docs/03-operations-design.md), [bộ khóa](../../cane-mvp/docs/pm/toolchain-lock.md), [Gate 3](../../cane-mvp/docs/pm/gate-3-review.md), [README backend](../README.md) |
| Truy vết và thay đổi | [RTM](../../cane-mvp/docs/06-rtm.md), [quy ước](../../cane-mvp/docs/00-conventions.md), [sổ CR](../../cane-mvp/docs/pm/change-requests.md) |

Đọc đúng mục liên quan ngay trước từng task và ghi tiêu chí/fixture cụ thể. Nếu có
khác biệt ảnh hưởng contract, xử lý trước khi code phần phụ thuộc. Câu RTM còn ghi
kế hoạch Draft là thông tin chưa đồng bộ với Gate 3 ngày 08/11; giữ trạng thái nghiệp
vụ chưa Implemented/Verified, đồng bộ câu mô tả khi cập nhật tài liệu chính thức.

## 3. Quy trình tuần tự và Git

Thực hiện theo yêu cầu bắt đầu của chủ dự án ngày 10/11/2026:

1. Kiểm Git/diff hiện tại, toolchain, Docker Engine và bộ khung hiện hành. Giữ các
   diff cũ riêng; dùng worktree sạch nếu cần tránh trộn, không stash/reset file người dùng.
2. Tạo branch riêng cho tài liệu/skill chuẩn bị, commit/PR phạm vi này trước. Đề xuất
   `chore/wp01-foundation-guidance`, commit `docs: add backend structure skill and WP01 plan`.
   PR backend được thao tác trên GitHub qua trình duyệt đang mở.
3. Cho mỗi task, lấy main mới nhất sau PR trước đã merge; tạo branch
   `feat/wp01-01-core-migration`, `feat/wp01-02-data-protection`, ... tương ứng task.
4. Đọc contract + task + nhóm test; xác định file, điều kiện đạt, fixture và rủi ro
   dữ liệu. Yêu cầu nghiệp vụ/schema/API/quyền/kiến trúc chưa rõ phải hỏi trước và
   giữ phần phụ thuộc chờ quyết định; tổ chức code trong thiết kế đã có được chủ
   động lựa chọn. Ghi bắt đầu thực tế, không gán thời gian ước đoán thành kết quả.
5. Thực hiện đúng một task, viết/chạy test cần thiết cùng code. Test đỏ hoặc đầu vào
   bắt buộc thiếu thì sửa/gỡ chặn, không bắt đầu task kế tiếp để bỏ qua.
6. Kiểm local phù hợp: format/unit, integration PostgreSQL/Redis thật và build qua
   runner chuẩn. Rà XML failures/errors/skipped và dữ liệu tác dụng phụ; dọn tài
   nguyên thử thuộc lượt này. Thay tài liệu đơn thuần dùng kiểm tài liệu tương ứng.
7. Tự review diff, structure/import/contract/migration/quyền/secret; cập nhật hướng
   dẫn và bằng chứng. Stage file/hunk rõ ràng. Mỗi commit là một thay đổi chạy/kiểm
   được; có thể nhiều commit trong một task khi chúng có ý nghĩa, không gom task sau.
8. Push branch bằng Git; dùng Browser Use trên Chrome để tạo PR về main, xem Files
   changed và theo dõi Actions/checks đúng HEAD. PR ghi vấn đề/hành vi, kiểm đã chạy,
   tác động schema/quyền, giới hạn và việc triển khai nếu có.
9. Chỉ merge khi kiểm bắt buộc đạt, diff đã rà và không còn lỗi chặn. Đề xuất squash
   merge nếu repo cho phép để mỗi task có một mốc main rõ; nếu repo yêu cầu cách khác,
   dùng cách được phép, không thay protection hoặc bypass check.
10. Qua browser xác nhận PR Merged và SHA trên main; kiểm CI sau merge đúng SHA.
    Lấy lại main local, đối chiếu HEAD/diff. Cập nhật backlog/RTM bằng kết quả thực,
    rồi mới task kế tiếp. Nếu merge/build lỗi, sửa task hiện tại trước.

Một task tương ứng một branch/PR là mặc định đề xuất. Bước này không yêu cầu xin lại
phép sau mỗi task khi chủ dự án đã chốt cả quy trình; những lựa chọn mới về phạm vi,
chi phí hoặc truy cập ngoài phạm vi vẫn cần quyết định tương ứng. PR/merge chỉ bắt
đầu sau lần chốt mà người dùng yêu cầu, không diễn ra trong lượt lập kế hoạch.

Repo tài liệu và backend là hai Git repo riêng. Cập nhật trạng thái/truy vết ở
`cane-mvp/` có commit/PR riêng khi cần, liên kết PR/SHA backend; không tự gộp code
vào repo tài liệu hoặc coi sửa file local là đã cập nhật trên GitHub. Thay nguồn
quyết định về nghiệp vụ/schema/API phải đồng bộ tài liệu và được chốt trước phần code.

## 4. Phân chia migration WP01-01/02

[Thiết kế dữ liệu §8.1](../../cane-mvp/docs/03-database-design.md#8-phương-án-migration)
quy định V001 chứa cả 3 function/11 trigger. Task 01/02 của backlog chia kiểm cấu trúc
và kiểm sâu bảo vệ/quyền. Đề xuất giữ nguyên hợp đồng V001:

- Task 01 tạo V001 đầy đủ như đặc tả, gồm đối tượng trigger/function theo SQL được
  duyệt; kiểm migration, cấu trúc/miền/khóa và sự tồn tại đối tượng. Runtime chưa
  được cấp quyền bảng để dùng schema chưa hoàn tất kiểm bảo vệ.
- Task 02 kiểm hành vi trigger/bất biến cùng nhánh hợp lệ, thiết lập/kiểm quyền
  runtime tối thiểu bằng bước cấp quyền có cấu hình role rõ ràng; không sửa V001 đã
  phát hành chỉ để hoàn thành task 02. Nếu phát hiện lỗi sau khi V001 đã merge/áp,
  thêm migration kế tiếp và kiểm đường nâng cấp, không repair checksum.
- Không áp/mở schema vào môi trường dùng lâu dài trước khi cả 01 và 02 đạt. Các
  lượt test dùng DB thử mới; không thêm inbox ngân hàng hoặc version dự phòng cho auto.

Cách này thực hiện/kiểm tuần tự nhưng thừa nhận phần đối tượng DDL của hai task có
liên hệ. Nếu chủ dự án muốn tách DDL thành V001/V002 ngay từ đầu, cần sửa và chốt
thiết kế/backlog trước khi triển khai; không tự thay phương án trong lúc code.

## 5. Kế hoạch từng task

Tên lớp/package dưới đây là ví dụ tổ chức, không phải yêu cầu tạo mọi file sẵn.
Mô-đun/bốn lớp theo thiết kế; chủ động chọn tên/package phù hợp code thực tế. Tài
liệu quy định script/encoder dùng chung; bootstrap Java CLI là lựa chọn triển khai
được chủ động quyết định trong kiến trúc hiện hành. Quyền/yêu cầu hệ thống còn thiếu
vẫn phải làm rõ. Mỗi task đáp ứng toàn bộ đầu ra/DoD tại backlog.

### WP01-01 — Migration lõi · phụ thuộc: không

- Đối chiếu 10 bảng/122 cột lõi, enum/default/generated/FK, CHECK, index, 16 settings
  và đối tượng V001. Phân biệt bảng nghiệp vụ với flyway_schema_history/inbox tùy chọn.
- Viết `src/main/resources/db/migration/core/V001__cane_core.sql`; chuyển SQL tham
  chiếu sang Flyway: bỏ BEGIN/COMMIT và CREATE SCHEMA, giữ SET LOCAL search_path.
- Có lệnh validate → migrate riêng bằng credential DDL; API/worker không chạy
  migration. Kiểm lệnh, cấu hình an toàn và hướng dẫn vận hành cùng task này.
- Test migration ở package hạ tầng/bootstrap phù hợp; fixture lỗi chỉ trong test.
  Kiểm DB trống, validate/chạy lại, checksum sai, schema có sẵn thiếu history, lỗi
  giữa migration rollback/dừng; không tự baseline/repair/clean.
- Tách test khung validate-empty hiện có khỏi fixture migrated. Giữ runtime không
  tự migrate. Chỉ kết luận task 01 theo phạm vi migration; kiểm sâu trigger ở task 02.
- Nguồn kiểm: kịch bản §3.1/3.2, dữ liệu §3–6/8; function/trigger theo §4 kế hoạch này.

### WP01-02 — Bảo vệ dữ liệu và quyền · phụ thuộc: 01

- Kiểm 3 function/11 trigger; UPDATE/DELETE/TRUNCATE lịch sử, dữ kiện receipt gốc,
  principal, bank phụ đổi liên kết và hoàn hợp lệ. Kiểm SQLSTATE 55000 bằng role thử
  có quyền câu lệnh; kiểm thiếu quyền runtime riêng, không để một lớp che lớp khác.
  Function cố định search_path=pg_catalog, không SECURITY DEFINER.
- Thêm bước cấp quyền theo bảng/thao tác, role API/worker có cấu hình; không hardcode
  secret, không blanket CRUD/DDL/TRUNCATE hoặc sở hữu schema. Tách quyền lệnh vận hành
  cần khi thêm seed/staff khỏi quyền role nhân viên.
- Dự kiến script ở `scripts/database/`, test DB/privilege trong src/test; không ghi
  logic tiền/đơn vào script. Kiểm chạy lại bước cấp quyền và nhánh runtime hợp lệ.
- Nguồn kiểm: §3.2/3.3; mọi lỗi DDL phát hiện sau V001 dùng migration mới.

### WP01-03 — Validation/lỗi/log · phụ thuộc: không, thực hiện sau 02 theo yêu cầu

- `support/web`, `support/validation`: từ chối JSON trùng key/trường lạ, body tối đa
  64 KiB, kiểm media, requestId, format lỗi/header chung; ánh xạ lỗi framework trước
  và sau security.
- Validator 16 settings thuộc Admin; primitive miền số/chuỗi/thời gian đặt đúng chủ
  quản. Tiền nguyên/overflow/giới hạn JS; password không trim, UTC Z đúng độ chính xác.
- Unit + HTTP test phù hợp kiểm 400/422/413/415/503, no-store/X-Request-ID và chuỗi
  nhạy cảm không xuất hiện trong DTO/log. Giữ mapping hành vi PREP-02 chưa có auth
  cho tới task 10/11; không thêm endpoint thử để kiểm validator.
- Nguồn kiểm: §3.2/3.10/3.16, API §1–2.

### WP01-04 — Runtime/readiness · phụ thuộc: 01, 02, 03

- Mở rộng bootstrap từ connection smoke sang schema/version/quyền readiness, kiểm
  cấu hình và lỗi DB; Redis lỗi có trạng thái suy giảm, không giả thành DB sẵn sàng.
  Bộ đếm rate/fallback hoàn thiện ở task 08; task 04 kiểm trạng thái phụ thuộc.
- Giữ một JAR/hai vai trò: API HTTP không scheduler nghiệp vụ, worker không HTTP.
  Thiết kế/kiểm profile staging và cấu hình proxy để phục vụ smoke task 16; hiện chỉ
  local/test, không suy rằng staging đã chạy được.
- Phân biệt secret bắt buộc theo chức năng đang có; auto/email chưa triển khai không
  chặn manual. Chỉ đưa diagnostics cần thiết trong phạm vi vận hành, không endpoint giả.
- Nguồn kiểm: §3.15/3.16; cấu hình hiện có và vận hành §1–2.

### WP01-05 — JDBC/transaction/khóa · phụ thuộc: 01, 02, 03

- `support/transaction` cho cơ chế chung; repository JDBC ở mô-đun sở hữu bảng.
  Bind SQL/map rõ; ghi cùng connection/TransactionTemplate READ COMMITTED. Đọc nhiều
  bảng dùng một SELECT/CTE hoặc read-only REPEATABLE READ nếu tách câu đọc.
- Advisory actor/key trước khóa hàng; đơn → receipt → SKU theo thứ tự ID tăng dần;
  settings theo luồng khóa riêng. Retry deadlock tối đa 3 lần bằng TX mới, có jitter
  ngoài transaction; rollback trước đọc kết quả UNIQUE thắng, phân loại constraint;
  hook sau commit không đảo kết quả.
- PostgreSQL thật, connection riêng/barrier/fault có kiểm soát. Kiểm rollback trước
  commit và lỗi sau commit; chưa nghiệm thu guard đặt đơn/tiền của WP03–WP06.
- Nguồn kiểm: §3.12/3.13, luồng §1, ADR-002.

### WP01-06 — Seed 9 SKU · phụ thuộc: 01, 02, 05

- Catalog sở hữu manifest/thành phần seed/JDBC; lệnh trong scripts dùng thành phần
  nghiệp vụ phù hợp, chọn cơ chế bootstrap khi làm task này. Đối chiếu tập SKU với
  catalog frontend; dữ liệu thật cần xác nhận, không lấy giá minh họa làm giá bán.
- Tạo tồn 0, chỉ thêm thiếu/kiểm hiện hữu; chạy lại và phục hồi bước dở không ghi đè
  giá/tồn/settings. Settings thuộc V001, không seed lại thành mặc định lúc khởi động.
- CLI một lần không HTTP/scheduler; fixture riêng dev/test. Dữ liệu thật thiếu không
  chặn kiểm seed fixture nhưng vẫn phải ghi rõ chưa sẵn sàng nhập production.
- Nguồn kiểm: §3.4; dữ liệu §9; frontend catalog hiện có chỉ là nguồn đối chiếu SKU.

### WP01-07 — Staff/password CLI · phụ thuộc: 01, 02, 05

- Admin sở hữu staff service, password adapter và JDBC/CLI; script wrapper prompt
  kín, không password CLI argument/env dump/log; encoder dùng chung với login.
- Argon2id đúng cấu hình; password 15–128 không trim. Owner đầu auth_version=1,
  chạy lại không reset; thay password/role/active thu hồi phiên/tăng auth_version.
- Kiểm hai script đồng thời bảo vệ Owner active cuối; không endpoint/UI staff mới.
  Bootstrap CLI tối thiểu, kết thúc sau lệnh và không scheduler nghiệp vụ.
- Nguồn kiểm: §3.5, bảo mật §2.

### WP01-08 — Redis/rate/fallback · phụ thuộc: 03, 04

- `support/ratelimit`: nhóm/IP, Lua tăng/TTL nguyên tử, một lần/request, 429/Retry-After;
  ACL/namespace/noeviction/timeout, trusted proxy/IP; process fallback cùng cửa sổ.
- Kiểm 5 nhóm ngưỡng bằng harness; login thật gắn ở task 11. Redis timeout/OOM/rỗng/
  mất kết nối, restart và đồng thời không làm mất kiểm quyền/khóa SQL.
- Kiểm ACL script thực tế đủ lệnh; không nới quyền Redis vô hạn. Chưa cache prices.
- Nguồn kiểm: §3.14, ADR-003.

### WP01-09 — Session/quyền hiện hành · phụ thuộc: 03, 04, 05

- Admin domain/application/JDBC/security: token ngẫu nhiên 32 byte, DB chỉ SHA-256,
  cookie __Host-cane-session có Secure/HttpOnly/SameSite=Lax/Path=/, không Domain;
  hạn tuyệt đối 8 giờ.
- Đọc SQL staff active/role/auth_version/flag bank mỗi request; actor tin cậy cho
  service; DB lỗi từ chối an toàn. Chưa có login dùng fixture để kiểm cơ chế.
- Kiểm đúng mốc hết hạn/thu hồi, không sliding refresh, không HttpSession hoặc Bearer
  guest thay cookie. Không làm dọn session job của gói khác chỉ để chứng minh hết hạn.
- Nguồn kiểm: §3.8/3.16.

### WP01-10 — CSRF/me/logout · phụ thuộc: 03, 09

- Admin API/security + bootstrap wiring: GET me và POST logout đúng contract; me
  401 vẫn cấp CSRF cookie/header, logout mất phiên vẫn 204 sau CSRF/origin hợp lệ.
- Origin/Referer/Sec-Fetch-Site, materialize và xoay CSRF, cookie clearing; chặn trước
  replay. Giữ deny-all phần ngoài phạm vi, chưa mở login trước task 11.
- Kiểm HTTP thực và session DB; không thêm endpoint bootstrap hoặc redirect HTML.
- Nguồn kiểm: §3.6/3.8, API §5.2–5.3, bảo mật §3.

### WP01-11 — Login/khóa tài khoản · phụ thuộc: 07, 08, 10

- Admin API/application/domain/security/JDBC: POST login qua rate/CSRF/origin, hash
  ngoài khóa dài, khóa staff rồi kiểm lại hash/auth_version/active/locked_until.
  Nếu dữ kiện verify đã đổi, rollback và xác minh lại có giới hạn theo bảo mật §2.2.
- Sai thứ 5 khóa 15 phút; commit bộ đếm trước 401. Trong khóa không gia hạn; hết
  khóa reset đúng. Không tồn tại/inactive/sai/khóa cùng lỗi; dùng dummy hash phù hợp.
- Login tạo/thay session trong TX, mốc DB duy nhất +8 giờ; cookie/CSRF sau commit.
  Kiểm race đổi password, nhiều sai đồng thời, expiry và phân biệt 429 IP với lock.
- Nguồn kiểm: §3.6/3.7/3.8/3.14/3.16, API §5.1.

### WP01-12 — Event/neo/outbox writer · phụ thuộc: 02, 05

- Writer JDBC trong support/audit, support/idempotency và support/outbox theo trách
  nhiệm; event/neo/outbox dùng TX caller, không TX riêng hay provider call.
- Kiểm source/FK/bộ event-channel-recipient theo phạm vi thành phần; insert outbox
  trùng không phá TX, neo tối thiểu không full request/PII/token/URL.
- Fault injection rollback bằng fixture hợp lệ; template/nguồn nghiệp vụ đầy đủ và
  dispatcher/lease/retry gửi thuộc gói sử dụng, không làm lấn WP05.
- Nguồn kiểm: §3.2/3.13/3.16.

### WP01-13 — Khung POST admin chống lặp · phụ thuộc: 05, 09, 10, 12

- Actor/key namespace, canonical hash method/route/toàn body kể cả expected/kind/
  rotate; auth/CSRF/quyền hiện tại trước replay, advisory lock rồi tra lại.
- Kiểm mismatch/in-progress, replay trước guard biến động, cùng key một tác dụng;
  key mới no-op có neo riêng, rollback không neo thành công; phân loại đúng UNIQUE.
- Harness nội bộ, kiểm cùng/khác actor/hash và thu hồi quyền; không thêm POST thử.
  Không áp neo cho login/logout/PATCH giá/PUT settings hoặc hash đặt đơn.
- Nguồn kiểm: §3.8/3.11/3.12/3.13.

### WP01-14 — Nhập tồn đầu · phụ thuộc: 06, 07, 13

- Catalog service/CLI dùng Owner hợp lệ, key ổn định, khóa SKU, cập nhật tồn + ledger
  + event + neo cùng TX; dùng hạ tầng chống lặp đã kiểm, không SQL chỉnh tồn trực tiếp.
- Tồn đầu 0 không tạo restock delta 0; mất response sau commit retry không cộng lại;
  lỗi từng bước rollback sạch. Chỉ nhập dữ liệu thật sau khi manifest/lượng được xác nhận.
- Không triển khai API/UI adjust kho WP06; kiểm fixture nội bộ/CLI và DB thật.
- Nguồn kiểm: §3.4/3.11/3.12/3.13.

### WP01-15 — Thành phần HMAC link · phụ thuộc: 03, 04, 05

- Orders domain/application/crypto/JDBC: token canonical 6 phần, HMAC-SHA256,
  key ring/active kid, secret ≥32 byte; token dài tối đa 512 byte, so MAC constant-time.
- So order/version/hạn với DB chính xác milliseconds; bằng hạn là hết hạn, sai/hết/
  thu hồi cùng lỗi; caller ghi kiểm lại dưới khóa. Xoay active kid giữ khóa cũ còn cần.
- Harness/fixture kiểm revoke/version/race/key ring API-worker; token/URL không vào
  log/DB/neo/outbox. Route guest/resend/rotate/gửi email thật thuộc WP05.
- Nguồn kiểm: §3.9/3.16, bảo mật §4.

### WP01-16 — Tích hợp nền/staging/demo · phụ thuộc: 01–15

- Chạy lại đủ 16 nhóm kiểm bằng PostgreSQL/Redis thật, ba auth API thật và fixture/
  harness cho cơ chế nền. Kiểm rollback/race/mất response, runtime/DB lỗi/Redis fallback.
- Dựng staging qua HTTPS/proxy thật; kiểm cookie/CSRF/origin/trusted IP, cùng JAR
  API-worker, không công khai DB/Redis, readiness và giới hạn quyền. Không dùng local
  hoặc chứng chỉ bỏ qua validation để thay smoke HTTPS.
- Ghi build/schema/config, lệnh dựng/migrate/cấp quyền/seed/staff/nhập tồn/chạy/kiểm/
  dọn/quay lui tương thích; lưu kết quả đã che secret. Demo ba auth API và cơ chế nền.
- Cập nhật backlog/RTM đúng nhánh đã kiểm; chủ dự án xem/chấp nhận đầu ra. Thiếu
  staging/HTTPS hoặc lỗi chặn thì WP01-16 và WP01 chưa hoàn thành.
- Nguồn kiểm: §3.1–3.16/§5, vận hành; chưa nghiệm thu tiền/đặt đơn/UI/provider.

## 6. Bộ kiểm, bằng chứng và điều kiện chuyển task

- Unit dùng Clock/fixture kiểm soát được; SQL/constraint/trigger/quyền/TX/concurrency
  chạy PostgreSQL thật, Redis behavior chạy Redis thật. Không H2/SQLite thay thế.
- Tách fixture DB rỗng cho migration khỏi fixture migrated; mỗi ca lỗi nâng cấp
  dùng DB/history thử, không sửa migration gốc/history của môi trường dùng lâu dài.
- Barrier và connection riêng để tái hiện race; không dựa sleep tùy ý, không bọc các
  request cạnh tranh trong một transaction test dùng chung.
- Test không hợp lệ tới đúng lớp cần kiểm: ví dụ giữ CSRF hợp lệ khi kiểm validation,
  phân biệt thiếu quyền SQL với trigger, rate IP với khóa theo tài khoản.
- Kết quả gắn task + nhóm §3 của kịch bản + UC/BR/NFR/T khi áp dụng; giữ mã T01–T118.
  Ghi commit/build/schema/môi trường, trước/sau DB và tác dụng phụ, lệnh chạy/kết quả.
- Chỉ Passed khi đủ kết quả; Not run/Blocked không đổi thành đạt. Bằng chứng khung
  PREP-02 hoặc CI green không thay kiểm task/HTTPS. Verified toàn T cần các WP sau.
- Điều kiện chuyển: task đúng đầu ra, kiểm áp dụng đạt, không lỗi chặn, diff được
  review, tài liệu khớp, PR merge/check main đúng SHA theo quy trình đã chốt.
- Demo các điểm 01–05, 06–11 và 12–16 theo milestone; task kiểm ngay khi làm, không
  dồn test về cuối. Ghi giờ thực tế; rà ước lượng sau 01–03 và cuối WP01, không cam
  kết ngày phát hành 16/11 chỉ từ việc đã có khung hoặc đã lập kế hoạch.

## 7. Đầu vào và giới hạn cần giữ rõ

| Đầu vào / quyết định | Thời điểm cần |
|---|---|
| Kế hoạch tuần tự, cấu trúc skill và cách tách 01/02 | Đã được phép bắt đầu ngày 10/11/2026; giữ V001 theo thiết kế |
| Java CLI/bootstrap và quyền DB lệnh vận hành | Chủ động chọn bootstrap; làm rõ quyền/yêu cầu hệ thống còn thiếu trước task phụ thuộc |
| Engine Docker/toolchain chạy đúng bộ khóa; truy cập Git/GitHub sẵn có | Kiểm lại trước task đầu và khi môi trường thay đổi |
| Chọn hosting/domain/HTTPS/proxy, quyền triển khai và chi phí | Chuẩn bị khi làm runtime; phải có trước smoke 16 |
| Manifest SKU, giá/tồn thật, Owner vận hành | Trước nhập dữ liệu thật; test nền dùng fixture riêng |
| Email/ngân hàng tự động, UI bán hàng, cache prices | Ngoài phạm vi WP01; đúng gói tương ứng |

WP01 chỉ có ba auth endpoint: GET `/api/v1/admin/me`, POST
`/api/v1/admin/auth/login`, POST `/api/v1/admin/auth/logout`, cùng thành phần nền/CLI.
Không thêm checkout/place/payment/kho/settings/guest endpoint, dispatcher/job nghiệp
vụ hoặc auto inbox để chứng minh nền. Không quyết định provider/hosting có phí trong
lượt chuẩn bị này. Lựa chọn vận hành cần được chốt khi tới thời điểm phụ thuộc.

## 8. Quy trình được chấp thuận và quyết định còn mở

1. Dùng skill repo-local và AGENTS làm điểm đọc trước khi sửa backend. Chỉ dẫn về
   cây tham khảo, chất lượng code, phân lớp/interface và cân nhắc pattern đã được
   ghi nhận; chủ động chọn cách triển khai CLI trong thiết kế hiện hành.
2. Giữ thứ tự 01 → 16, kiểm đúng rồi mới chuyển; mỗi task một branch/PR mặc định,
   commit có ý nghĩa, PR/merge qua browser và kiểm main sau merge.
3. V001 theo thiết kế đầy đủ, task 02 kiểm sâu trigger/quyền; không sửa checksum cũ.
4. Khởi đầu bằng PR tài liệu/skill, sau đó WP01-01; giữ các diff cũ riêng. Mỗi task
   một PR, tên branch và squash merge được chủ động áp dụng theo quy trình đã cho phép.
5. Staging HTTPS là điều kiện hoàn thành task 16; kết thúc nền có demo/chấp nhận,
   chưa đồng nghĩa mở bán hoặc nghiệm thu toàn bộ T01–T118.

Ngày **10/11/2026**, chủ dự án yêu cầu bắt đầu WP01 theo các tài liệu, skill và
những điểm đã thống nhất. Thực hiện quy trình trên; môi trường staging, quyền/yêu cầu
hệ thống hoặc dữ liệu thật còn thiếu được hỏi tại thời điểm cần. Chỉ ghi task hoàn thành theo
bằng chứng kiểm và PR đã merge, không suy từ quyết định bắt đầu.

## 9. Rà soát trước triển khai — 10/11/2026

- Đã đối chiếu lại 16 task/phụ thuộc với backlog, quyền sở hữu/bốn lớp với thiết kế
  kỹ thuật và các nhánh kiểm với 16 nhóm test WP01. Chưa có kết quả triển khai/test
  nghiệp vụ mới; kiểm tài liệu không thay kiểm runtime hoặc staging.
- Đã bỏ cách diễn đạt phải duyệt từng tên Java/package; giữ quyền chủ động tổ chức
  code mà người dùng cho phép, hỏi khi thiếu quyết định nghiệp vụ/kiến trúc thực sự.
- Làm rõ Java CLI là phương án dự kiến, bộ đếm fallback ở task 08; bổ sung lệnh
  migrate riêng, snapshot đọc, jitter retry và kiểm lại dữ kiện verify login.
- Không yêu cầu cài thêm skill Java/Spring/design pattern để bắt đầu WP01. Skill
  CANE, đặc tả, code hiện hành và bộ kiểm là nền làm việc; skill cộng đồng chỉ là
  tài liệu hỗ trợ tùy nhu cầu, không thay stack/contract hoặc ép thêm pattern.

## 10. Nhật ký thực hiện

- 10/11/2026: chủ dự án yêu cầu bắt đầu WP01. PR hướng dẫn/skill
  [#1](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/1) đã squash merge vào
  `1a97233`; CI PR và CI main đều đạt. Hai diff có sẵn ở checkout ban đầu giữ riêng.
- WP01-01 hoàn thành: [PR #2](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/2)
  merge tại `6a0e5b6`, CI PR/main đạt; [bằng chứng](wp01-01-verification.md).
  Truy vết Cane-MVP đã commit local. Theo chỉ dẫn mới của chủ dự án, repo tài liệu
  chỉ commit, không push; backend giữ quy trình PR/merge qua browser.
- WP01-02 hoàn thành tại [PR #3](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/3),
  main `3c09f08`, CI PR/main đạt; [bằng chứng](wp01-02-verification.md).
  Giữ nguyên V001,
  kiểm sâu trigger và cấp quyền runtime riêng theo môi trường. Worker bản thủ công
  chưa có quyền ghi tiền/tồn hoặc nhánh auto; rà/bổ sung khi triển khai nhánh đó.
- WP01-03 hoàn thành tại [PR #4](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/4),
  main `2d9e340`, CI PR/main đạt: validator/settings, JSON
  nghiêm ngặt, lỗi chung Security/MVC và log/requestId. Trạng thái/bằng chứng tại
  [hồ sơ WP01-03](wp01-03-verification.md); không mở endpoint nghiệp vụ sớm.
- WP01-04 hoàn thành tại [PR #5](https://github.com/dinhdev-nu/cane-ecommerce-api/pull/5),
  main `49b7102`, CI PR/main đạt: kiểm DB/schema trước HTTP/job,
  profile staging/production, Redis degraded; [hướng dẫn](runtime-readiness.md).
  Ngày 10/11/2026 chủ dự án xác nhận chưa có staging/domain; proxy/HTTPS và smoke
  thật còn phụ thuộc lựa chọn hạ tầng, không suy từ profile/fixture local.
- WP01-05 trên branch `feat/wp01-05-transactions`: JDBC/transaction/khóa/rollback và
  retry deadlock; [hướng dẫn](transactions.md), [hồ sơ kiểm](wp01-05-verification.md).
- Chủ dự án đồng ý kiểm trước migrate chỉ bỏ qua `versioned:pending`, giữ chặn
  checksum/migration thiếu; sau migrate kiểm đầy đủ. Xem hướng dẫn migration và
  hồ sơ kiểm thực tế, không dùng kết quả cũ để ghi task đạt.
- Test thực trên Flyway 12.4.0 phát hiện ngoại lệ này vẫn báo schema chưa tồn tại
  khi dựng DB mới. Chủ dự án đã đồng ý chặn repeatable trong core và dùng
  `*:pending` ở bước trước; sau migrate vẫn kiểm đầy đủ. Runner cuối đạt 20 unit
  và 23 integration, không lỗi/bỏ qua; bằng chứng/PR của task 01 liên kết ở trên.
