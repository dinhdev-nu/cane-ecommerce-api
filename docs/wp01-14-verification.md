# WP01-14 — Hồ sơ kiểm nhập tồn đầu

Bắt đầu **14/11/2026**, branch `feat/wp01-14-initial-stock` từ main WP01-13
`6c358012208e355df761d7f4812bd25b1f28f47a`. Đang kiểm, chưa PR/CI/merge.
[Runbook](initial-stock.md) ghi quyền vận hành, file/key và cách retry an toàn.
Nguồn dữ liệu §9.1–9.2, processing §1, test cases §3.4/3.11–3.13 và kế hoạch WP01-14.

CLI một lần không chạy Boot/HTTP/worker/Redis; schema checksum và quyền được kiểm
trước gọi cùng InitialStockService. Credential vận hành riêng chọn Owner active
trong SQL; không lấy UUID thay session authentication HTTP. Catalog gọi Admin service,
không import repository Admin. Command bất biến giữ SKU/quantity/reason/key đã
normalize; CanonicalOperation thêm framing CLI riêng, giữ nguyên golden POST.
Không sửa V001/settings/grants hoặc tạo endpoint/bảng mới.

PostgreSQL thật với quyền API kiểm service: nhập từ 0, sản phẩm updated_by, event
stock_adjusted có hash/neo, ledger restock có actor/note/event_id; số tồn tối đa int
không tràn. Retry cùng Owner/key/body trả operationId/result cũ dù tồn đổi, không
ghi thêm; đổi quantity/SKU/reason mismatch. Key mới vẫn kiểm guard tồn 0, không tự
chuyển thành cộng thêm hoặc reset tồn hiện hữu. Không có outbox.

Lượng 0 chỉ event operation_noop/neo, movementId null; sản phẩm giữ nguyên mọi cột,
không ledger restock delta 0. Replay key cũ không thêm event; key mới no-op hợp lệ
có neo riêng. Owner inactive/operator/missing bị chặn trước mismatch/replay; quyền
được đọc lại trong transaction. Hai lệnh cùng key giữ connection riêng/barrier:
đang làm trả busy, commit xong chỉ replay hoặc mismatch, một lần nhập/ledger/neo.

Fault sau update sản phẩm, event+neo, ledger và trước commit rollback sạch;
retry chính command cũ sau lỗi chỉ một hiệu lực. CLI thật được gọi trên DB thử bằng
credential vận hành, lặp giữ biên nhận; từ chối credential runtime và fixture ở
production. File reader kiểm duplicate/trailing/trường lạ/kiểu sai/tràn/body limit,
normalize và release/fixture. Không đưa fixture vào JAR sản phẩm.

Compile đầu phát hiện adapter thử StaffCommandRepository thiếu hàm findById mới;
đã bổ sung delegate và chạy lại. Kiểm tập trung cuối **154 unit + 57 integration
đạt**, 0 failures/errors/skipped; Maven 1 phút 37 giây, kết thúc **13:44:09 GMT+7**
ngày 14/11. Log ngoài Git `.local/wp01-14-focused-final.log`.

Runner chuẩn cuối **154 unit + 206 integration đạt**, 0 failures/errors/skipped;
Maven 6 phút 12 giây, kết thúc **13:50:56 GMT+7** ngày 14/11. Log ngoài Git
`.local/wp01-14-release.log`; XML đối chiếu 18 unit suite/15 IT suite. Format/build
JAR đạt, không fixture/probe/test. Compose `cane-prep02-check-7410d70b8a0c` đã dọn
container/network/volume; không còn Testcontainers CANE hoặc image mới. Wrapper
PowerShell parse đạt; diff/liên kết đạt, hai file có sẵn ở checkout gốc giữ đúng
SHA-256 ban đầu. Chờ PR/CI/merge.

Chưa nhập tồn thật: chưa có lượng/manifest được xác nhận.
Đây là nền BR-006/011, NFR-005/009, T93–T100/T116–T117 theo phạm vi áp dụng;
không Verified toàn yêu cầu đơn/tiền, API/UI kho WP06 hoặc HTTPS WP01-16. Mỗi SKU
là transaction riêng, không tuyên bố cả danh sách nhập nguyên tử. Mất phản hồi sau
commit được kiểm bằng bỏ kết quả đầu và retry; after-commit failure/retry chung
đã có suite A-TX/Transactions. Cane-MVP chỉ commit local, không push.
