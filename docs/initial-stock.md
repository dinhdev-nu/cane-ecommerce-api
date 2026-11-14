# Nhập tồn đầu — WP01-14

Nguồn: thiết kế dữ liệu §9.1–9.2; test cases §3.4/3.11–3.13. Đây là lệnh vận hành
cho Catalog đã seed, chưa phải API/UI điều chỉnh kho WP06.

## Chuẩn bị

Build JAR và migrate/validate schema trước; seed SKU tồn 0 rồi tạo Owner qua script
nhân viên. Dùng credential vận hành riêng qua `CANE_OPERATIONS_DB_URL`,
`CANE_OPERATIONS_DB_USER`, `CANE_OPERATIONS_DB_PASSWORD`. Lệnh không tự migrate,
không mở HTTP/worker/Redis, không dùng credential runtime `cane_api`/`cane_worker`.
Đặt thêm hai biến **tên role, không phải mật khẩu** `CANE_API_DB_USER` và
`CANE_WORKER_DB_USER` đúng theo `cane_api_role`/`cane_worker_role` đã cấp bằng
`Grant-Runtime.sql` trên DB đích. Không mặc định tên role: thiếu/rỗng, role không
tồn tại hoặc hai role trùng nhau đều dừng trước thao tác kho. CLI từ chối login/
current role runtime và login có membership trực tiếp/gián tiếp vào runtime,
kể cả khi đổi tên role. Dùng login vận hành riêng, không superuser và không có
membership vào runtime; role schema owner `cane_migrator` hoặc role vận hành có
quyền tối thiểu bên dưới đều dùng được. Lấy tên từ cấu hình triển khai tin cậy;
không điền tên khác để bỏ qua hàng rào kiểm credential.
Cần đọc Flyway history, SELECT staff_users/products/business_events, UPDATE ba cột
products stock_qty/updated_by/updated_at, INSERT business_events/stock_movements và
USAGE schema. Không cần ghi staff/settings/outbox hoặc quyền DDL; không nới grants
runtime. Credential vận hành là quyền truy cập lệnh, giống seed/script staff;
ownerId chọn actor audit đang active/role owner trong SQL, không phải đăng nhập HTTP
bằng UUID. Không dùng service này từ controller để thay session/CSRF/RBAC.

Mỗi file là một thao tác cho một SKU; nhập chín SKU bằng các file/key riêng được
giữ ổn định. Lưu file ngoài Git, ví dụ `.local/initial-stock-cane-tea.json`:

```json
{
  "dataKind": "release",
  "ownerId": "<UUID Owner đã tạo>",
  "sku": "cane-tea",
  "quantity": null,
  "reason": "<lý do kiểm đếm thực tế>",
  "key": "<UUID v4 tạo một lần cho thao tác này>"
}
```

Đây là template chưa chạy được. Điền lượng **thật đã xác nhận** là số nguyên
0–2147483647; không dùng giá trị minh họa làm lượng mở bán. Key ASCII 16–200 ký tự,
nên tạo UUID v4 một lần và lưu cùng file; không sinh lại mỗi lần gọi script.
Lý do bắt buộc trim, 1–1000 Unicode code point; không ghi PII/secret vào lý do.
File tối đa 64 KiB, đủ đúng sáu trường; từ chối duplicate/trailing JSON, kiểu sai,
trường lạ. `dataKind=fixture` chỉ cho local/test; staging/production cần release.
Nhãn môi trường là lựa chọn của người vận hành, phải kiểm DB đích độc lập trước chạy.

```powershell
pwsh -NoProfile -File scripts/catalog/Initialize-Stock.ps1 -Manifest .local/initial-stock-cane-tea.json -Environment production
```

`-Jar` tùy chọn để chỉ đúng artifact. Output chỉ có operationId/replayed/SKU và
stockAfter **của biên nhận cũ**, không phải số tồn hiện tại nếu đã có giao dịch sau đó.
Không in key/lý do/body/credential hoặc exception gốc. Có thể dùng file fixture
trong DB thử riêng; hiện chưa có dữ liệu thật được nhập.

## Transaction và chạy lại

`InitialStockCommand` bất biến là nguồn chung cho hash/phần ghi. Hash dùng
`CanonicalOperation.commandLine`, ba phần length-framed `CLI`,
`catalog/initial-stock`, JSON chuẩn hóa gồm sku/quantity/reason. Phạm vi này tách
POST HTTP; hash HTTP WP01-13/golden giữ nguyên. Actor/key vẫn dùng neo và advisory
chung, nên không tái sử dụng key đã dùng cho thao tác admin khác của cùng Owner.
dataKind là nhãn an toàn file; ownerId là actor; key là định danh thao tác, không
phải dữ kiện kho để hash.

Kiểm Owner hiện hành trước replay và khi bắt đầu transaction; khóa actor/key,
tra lại rồi khóa một SKU. Replay trả neo trước guard tồn. Thao tác mới chỉ nhập
từ tồn 0, không thay giá/trạng thái bán hoặc nhập thêm lên tồn hiện hữu. Lượng dương
cập nhật tồn/updated_by/updated_at, một event stock_adjusted có neo, một ledger
restock có actor/lý do và event_id, cùng transaction. Lượng 0 giữ nguyên row sản
phẩm, ghi một operation_noop có neo/movementId null, không tạo ledger delta 0.
Không có outbox, cache hoặc provider.

Lỗi trước commit rollback tất cả. Nếu mất phản hồi/không biết commit, giữ nguyên
Owner/key/dữ kiện đã chuẩn hóa để chạy lại: thành công cũ chỉ replay, không cộng tồn
lần nữa. Cùng actor/key khác dữ kiện là mismatch; đang thực hiện là busy. Không
chữa lỗi bằng key mới hoặc sửa SQL trực tiếp. Dữ kiện cần thay sau nhập thành công
phải dùng điều chỉnh có chủ đích theo quy trình kho WP06. Guard tồn 0 không thay
thế việc giữ key/lịch sử; số tồn có thể về 0 sau bán/điều chỉnh.

Các SKU độc lập commit riêng; nếu dừng giữa danh sách, chạy tiếp chính các file/key
cũ. Không tuyên bố toàn bộ danh sách là một transaction. Kiểm thử cơ chế/CLI bằng
fixture không xác nhận số tồn mở bán, API kho, HTTPS hoặc nghiệp vụ đơn/tiền.
