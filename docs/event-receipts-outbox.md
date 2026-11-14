# Event, biên nhận thao tác và outbox

WP01-12 cung cấp writer JDBC dùng chung trong `support/audit`,
`support/idempotency`, `support/outbox`; không có endpoint hoặc bảng mới.
Nguồn: Cane-MVP thiết kế database §3.8–3.10/§4/§7, xử lý §1/§8.2,
hợp đồng nghiệp vụ BR-011/015 và kế hoạch WP01-12.

Application service sở hữu `Transactions.write`. Chuẩn bị UUID và chọn dữ kiện
server trước khi gọi `BusinessEventWriter.append(event, operationStamp)`; chỉ một
event trong thao tác mang key/hash/result. Các event phụ gọi `append(event)`.
Ghi ledger và gọi `OutboxWriter.enqueue` trước commit, trên cùng DataSource.
Writer từ chối ngoài write transaction hoặc trong read-only transaction; không
mở transaction, retry hay gọi Redis/provider riêng.

Caller giữ đúng thứ tự advisory actor/key → order ID → payment ID → SKU; writer
không lấy thêm khóa receipt sau SKU. Actor được xác thực bởi caller, không lấy từ
body. Writer đối chiếu order/payment ID và các dữ kiện khoản thu được cung cấp
với receipt tại lúc tạo event; ID trong biên nhận phải khớp event. Số tiền, method,
reference, principal và verified được kiểm khi có trong facts. Refund dùng
refund_reference. Tính đầy đủ của payload theo từng event, guard tiền/trạng thái,
quyền và danh sách public_label cố định vẫn thuộc service nghiệp vụ ở gói sử dụng.
Không dùng khả năng nhận String kind/publicLabel để chuyển trực tiếp input client.

`AuditJson` chỉ nhận các field persistence được chọn tường minh, scalar và hai
cấu trúc restockItems/changedKeys được biết. Biên nhận không chứa expected/body,
người nhận, password, cookie, token hay URL. Không truyền request/entity nguyên
vào writer; field chuỗi hợp lệ về cấu trúc vẫn phải được caller chọn đúng nghĩa.
Note nội bộ không phải dữ liệu template công khai. Các giá trị mutable được copy;
toString của event/stamp/receipt/notification không in dữ kiện.

`OperationReceipts.find(actor, key)` chỉ đọc neo sau khi caller đã kiểm quyền.
Khung hash/replay/lock của POST quản trị tiếp nối WP01-13. Key mới no-op phải có
event `operation_noop` với biên nhận riêng, không xếp thông báo. Không cập nhật
event sau INSERT; UNIQUE lỗi phải rollback trước khi đọc kết quả thắng.

Trong một lần `enqueue(eventId, allCandidates)`, truyền **tất cả** ứng viên khách
và Owner của event. Writer kiểm toàn bộ trước INSERT, chuẩn hóa người nhận và ưu
tiên khách khi email trùng Owner. Gọi tách Owner trước khách sẽ không thay được
dòng đã tạo; caller không được dùng cách đó. Owner dùng settings hiện hành tại
lúc xếp; địa chỉ đã xếp được giữ nguyên. Khách chỉ email trong order nguồn;
thông báo tiền/hoàn đòi verified tại event. Thu cash ở bàn giao dùng thông báo
mốc completed, không có email payment_received riêng.

Event/outbox giữ order_id/payment_id tại mốc. Outbox đọc snapshot event, không
tra receipt hiện tại để đổi liên kết/người nhận lịch sử. Kind phải phù hợp event;
event nội bộ stock/product/settings/no-op không xếp thông báo. OWNER_ALERT do
caller quyết định theo nghiệp vụ. Link chỉ lưu version/hạn; không lưu URL/token.

INSERT outbox chỉ bỏ qua UNIQUE `(event_id, channel, recipient)`; trả ID vừa tạo.
Trùng exact tuple không làm hỏng transaction; lỗi PK/FK/UNIQUE khác vẫn làm thao
tác rollback. Status pending, attempts 0 và mốc thời gian lấy default SQL. Không
render/gửi/claim/lease, không bảo đảm provider gửi đúng một lần. Dispatcher và
template đầy đủ tiếp nối WP05.

Kiểm chứng và giới hạn tại [hồ sơ WP01-12](wp01-12-verification.md).
