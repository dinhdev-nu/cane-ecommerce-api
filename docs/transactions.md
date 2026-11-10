# JDBC, transaction và khóa — WP01-05

Nguồn: thiết kế kỹ thuật §5.2, luồng xử lý §1, thiết kế dữ liệu §7 và ADR-002.
PostgreSQL là nơi quyết định transaction/khóa; không có ORM, Redis lock hoặc
transaction độc lập cho event/ledger/biên nhận/outbox của cùng thao tác.

## Ranh giới service

Application service gọi `Transactions.write(() -> ...)`. `TransactionTemplate`
dùng READ COMMITTED và cùng `JdbcTransactionManager`/DataSource với các repository
`NamedParameterJdbcTemplate`. Lời gọi write lồng nhau tham gia transaction hiện
hữu, không tự commit một phần hoặc retry bên trong transaction đã lỗi.

Controller/job chỉ gọi service. Bind giá trị SQL, schema `cane` tường minh,
mapping bằng RowMapper; không nội suy giá trị client làm SQL/định danh. PostgreSQL
driver giữ nguyên phiên bản BOM, nay có compile scope để đọc constraint bằng API
`PSQLException`, không parse message chứa SQL/PII.

Đọc nhiều bảng bằng một SELECT/CTE/aggregate nếu phù hợp. Nếu tách nhiều SELECT,
dùng `Transactions.readSnapshot` (REPEATABLE READ và PostgreSQL READ ONLY thực).
Không âm thầm join boundary khác isolation hoặc biến read-only thành write;
manager kiểm và từ chối. READ COMMITTED nhiều SELECT không thay snapshot này.

## Khóa và retry

1. Kiểm auth/CSRF/quyền/đầu vào trước thao tác; quy tắc replay cụ thể ở task 13.
2. Trong write, gọi `AdvisoryLocks.acquire(namespace, actorId, key)` trước khóa hàng.
   SHA-256 của các phần có độ dài/tag rõ ràng tạo mã 64 bit ổn định. Actor null
   dành namespace không có staff. Không dùng mã khóa này thay so full key/hash.
   `pg_try_advisory_xact_lock` bận ném `RequestInProgressException`, rollback;
   HTTP ánh xạ 409 `REQUEST_IN_PROGRESS`, `Retry-After: 1`, lỗi chung/no-store.
3. Khóa đủ tập đơn hiện biết bằng `OrderLocks.lockIds`, sau đó đủ receipt bằng
   `PaymentLocks.lockIds`, sau đó SKU bằng `ProductLocks.lockSkus`. SQL mỗi nhóm
   `ORDER BY ... FOR UPDATE`; UUID theo thứ tự PostgreSQL, không Java signed UUID.
   Kết quả là các khóa tồn tại; service phải kiểm phần thiếu/guard và đọc dữ kiện
   trong cùng transaction. Thu thập đủ tập trước mỗi nhóm, không lấy thêm đơn
   sau receipt. Liên kết receipt đổi sang đơn chưa khóa thì rollback/đọc lại tập
   theo luồng nghiệp vụ; không bổ sung khóa theo thứ tự ngược.
4. Settings dùng transaction riêng và `SettingsLocks.lockAll`: khóa đủ 16 key tăng
   dần, từ chối thiếu dòng. Không giữ khóa settings rồi vào đơn/receipt/kho. Service
   lưu settings vẫn phải đọc/ghép/validate tập cuối và commit nguyên tử ở WP06.
5. Guard và ghi toàn bộ dữ liệu/event/ledger/neo/outbox cùng transaction. Rollback
   khi lỗi; không Redis/email/Telegram/provider trong transaction giữ khóa.

Repository giữ SQL của module sở hữu; interface lock là contract cho application,
không cho module khác import implementation cập nhật chéo bảng. Thứ tự giữa nhóm
là trách nhiệm của service điều phối; không có interceptor tự sửa luồng khóa sai.

Chỉ SQLSTATE **40P01** và kết quả **đã rollback** được tự retry ở outer write, **tối đa 3 lượt thực thi tổng
cộng** (gồm lượt đầu), transaction mới và cùng closure/key/body. Jitter 10–50 ms
sau khi transaction cũ rollback/cleanup; không ngủ trong transaction. Chuẩn hóa
key/hash/command trước callback, không sinh key mới ở mỗi lượt. Lỗi nghiệp
vụ/UNIQUE/serialization/đứt kết nối không tự retry. Đứt kết nối khi commit có thể
không rõ kết quả: trả lỗi an toàn/requestId, client giữ key/body để hỏi lại.

UNIQUE phải thoát write/rollback rồi mới đọc người thắng. `SqlFailures.uniqueConstraint`
chỉ trả tên từ SQLSTATE 23505 + metadata driver, không tự biến lỗi thành replay.
Caller chỉ xử lý đúng constraint mình sở hữu: key đơn/admin khác bank reference,
source key hoặc outbox. Bank cùng dữ kiện vẫn phải quay lại luồng đầy đủ để tạo
neo cho key mới; outbox dedup dùng ON CONFLICT theo contract. Không catch lỗi SQL
rồi tiếp tục query trong transaction đã aborted.

## Sau commit

Đăng ký `Transactions.afterCommit` trong write cho tác dụng không ghi DB như xóa
cache. Hook chỉ chạy khi commit thành công; RuntimeException được ghi cảnh báo
cố định, không stack/message/đầu vào của callback và không làm retry hoặc báo thao
tác đã commit thất bại. Outer boundary cũng theo dõi completion: callback
Spring khác lỗi sau commit vẫn trả kết quả đã commit, không nhầm 40P01 của callback
thành transaction có thể chạy lại. Outcome chưa xác định không tự retry.
Mạng không chạy khi giữ khóa PostgreSQL.

Không ghi audit/outbox ở hook. Spring có thể còn giữ resource của transaction khi
gọi afterCommit; không truy vấn DB qua hook hoặc dùng nó mở lại thao tác ghi.
Xem [TransactionSynchronization](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html#afterCommit()).
Provider gửi thông báo vẫn thuộc worker/outbox ở gói tương ứng.

Runtime tắt log thô của `TransactionTemplate` và `JdbcTransactionManager`: Spring
có thể log nguyên ngoại lệ SQL khi rollback ghi đè lỗi trước đó. Cảnh báo cố định
của ứng dụng và log HTTP/requestId vẫn hoạt động. Ca HTTP tiêm lỗi rollback kiểm
cả response lẫn log, không chỉ trường hợp SQL lỗi thông thường.

## Kiểm và giới hạn

`TransactionsIT` dùng PostgreSQL thật, credential API và các repository thật;
fixture là bộ gọi cơ chế, không service bán hàng. Kiểm connection/isolation chung,
snapshot/read-only, contention, thứ tự khóa, UNIQUE/đọc sau rollback, deadlock thật,
rollback từng bước và lỗi sau commit. `ApiInputIT` kiểm mapping 409 qua HTTP thử.
V001/quyền không đổi. Không coi nhóm này là nghiệm thu toàn T95/T96/T116/T117;
guard tiền–đơn/tồn/quyền và các đường dùng thật còn kiểm tại task/WP sở hữu.
