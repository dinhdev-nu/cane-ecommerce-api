# Dữ liệu vận hành và thay mock trước go-live

WP02 có bộ cấu hình **MOCK, chỉ local/test** trong `scripts/dev/wp02-settings.json`,
catalog trong `scripts/dev/wp02-catalog.json`, tồn trong
`src/test/resources/catalog/wp02/stock-fixture.json`. Không hardcode các dữ kiện này
trong Java/React. `Initialize-Wp02.ps1` nhận `-Settings` và `-Stock` để thay file,
không cần sửa code; catalog đi qua `Seed-Catalog.ps1 -Manifest <file>`.

Thông tin shop/giờ/điện thoại do chủ dự án cung cấp ngày 15/11/2026 cho dev. Phí
15.000đ, ngưỡng miễn 100.000đ, phân loại gần/xa và tồn là mock theo chỉ dẫn tiếp
theo cùng ngày. Bốn mã/tên xã/phường được đối chiếu [danh mục hành chính trên cổng
Cục Thống kê](https://www.nso.gov.vn/wp-content/uploads/2025/07/19_2025_qd-ttg_30062025-signed-6-143-da-nen.pdf):
19885 Đan Điền, 19867 Quảng Điền, 19753 Phú Xuân, 19789 Thuận Hóa. **Việc cho phép
giao/allowNear là mock**, không phải kết luận khoảng cách hay phạm vi giao thực tế.
Không tự đưa toàn bộ 40 xã/phường thành vùng phục vụ.

`bank_details.accountNo = "0338825982"` là STK **MOCK DEV** do chủ dự án chỉ định
dùng SĐT dev; chưa xác minh đây là tài khoản nhận tiền. Tên tài khoản và tên ngân
hàng có nhãn MOCK; **không chuyển tiền thử vào số này**. Email `.invalid` không gửi
được; không coi là kênh Owner thật. Cổng shop công khai không trả thông tin ngân
hàng; các bước thanh toán thật thuộc WP03 và tiếp theo.

## Bảng dữ liệu thật cần cung cấp

Các tên khóa dưới đây là tên JSON của settings, `values` là object bọc khi PUT.
Chuỗi tiền/số không được dùng thay số JSON. Null là JSON null, không phải `"null"`.

| Trường | Ý nghĩa / nơi dùng | Định dạng và ví dụ để chuẩn bị |
|---|---|---|
| store.name | Tên shop, cửa hàng/public shop, snapshot đơn | Chuỗi ≤100 ký tự; `Cane Shop` |
| store.address | Địa điểm nhận tại shop, snapshot đơn | Chuỗi có địa chỉ thật đầy đủ; địa chỉ dev phải xác nhận lại với khách |
| store.openingHours | Giờ xử lý hiển thị, không tự mở/đóng nhận đơn | Chuỗi; `08:00–20:00` |
| store.pickupGuidance | Hướng dẫn khách đến lấy | Chuỗi; đến khi trạng thái Sẵn sàng lấy, COD thu khi bàn giao; bổ sung chỉ dẫn địa điểm thật nếu có |
| accepting_orders | Chặn/cho tạo đơn mới | Boolean; giữ `false` trong lúc chuẩn bị, bật sau nghiệm thu |
| enabled_payment_methods | Thanh toán cho đơn mới | Mảng không trùng gồm `cod`, `bank_transfer`; `[]` khi chưa sẵn sàng |
| shipping_fee_vnd | Phí giao cố định theo hợp đồng, quote/snapshot đơn | Số nguyên ≥0, ví dụ `15000`; null = chưa hỗ trợ giao, 0 = miễn phí có chủ ý |
| free_ship_threshold_vnd | Ngưỡng miễn phí khi đủ tiền hàng | Số nguyên ≥0, ví dụ `100000`; null = không miễn theo ngưỡng |
| delivery_zones[].wardCode | Định danh xã/phường được giao | Chuỗi mã hành chính đã xác minh; `"19885"` là ví dụ Huế đã đối chiếu, không dùng tên làm mã |
| delivery_zones[].name | Tên vùng hiển thị checkout | Chuỗi, ví dụ `Xã Đan Điền`; cần danh sách phục vụ thật do vận hành duyệt |
| delivery_zones[].allowNear | Vùng cho SKU có deliveryScope=near, nhất là ly | Boolean; phải duyệt logistics thực tế, không suy từ tên/xã lân cận |
| bank_details.bankCode | Định danh ngân hàng nhận | Chuỗi ≤20; dev dùng `VietinBank`; xác nhận mã mà nhánh tích hợp thanh toán sẽ dùng |
| bank_details.bankName | Tên ngân hàng khách nhìn thấy | Chuỗi ≤100; tên thật được vận hành xác nhận |
| bank_details.accountNo | Số tài khoản nhận tiền | **Chuỗi**, giữ số 0 đầu; phải thay STK mock bằng STK thật đã đối chiếu |
| bank_details.accountName | Chủ tài khoản | Chuỗi ≤100; tên thật khớp tài khoản/ngân hàng, không dùng nhãn mock |
| bank_auto_confirm | Mode của đơn bank mới | Boolean; **false** cho nhánh thủ công hiện hành; chỉ bật khi nhánh auto được triển khai/nghiệm thu |
| operator_can_confirm_bank | Quyền xác nhận tiền của Operator | Boolean; mặc định false; Owner quyết định, không cấp quyền ngoại lệ/hoàn tiền |
| support.phone | Hỗ trợ khách/public shop và xem đơn | SĐT VN được chuẩn hóa; chuỗi `0338825982` là số dev do chủ dự án cung cấp |
| support.zaloUrl | Link Zalo công khai | URL HTTPS đã kiểm đúng tài khoản, hoặc null; `https://zalo.me/<số-thật>` |
| owner_notify.email | Người nhận email vận hành, không public | Email thật hoặc null; thay `wp02-owner@example.invalid` |
| owner_notify.telegramChatId | Chat ID nhận thông báo vận hành | Chuỗi hoặc null; cần xác minh bot/chat và quyền gửi bằng kênh thật |
| low_stock_threshold | Ngưỡng low/cảnh báo tồn, invalidation cache | Số nguyên ≥0; `5` |
| max_qty_per_sku | Giới hạn SKU lúc quote/place, không giới hạn giỏ WP02 | Số nguyên >0; `99` là mặc định cần vận hành duyệt |
| bank_hold_min | Phút nhắc manual / hết hạn auto theo mode snapshot | Số nguyên >0; `60` |
| link_ttl_days | Thời hạn link đơn lúc tạo/rotate | Số nguyên >0; `90` |
| stale_alert_hours | Giờ cảnh báo đơn tồn đọng theo cấu hình hiện hành | Số nguyên >0; `24` |
| products[].sku | Ghép catalog FE/BE, snapshot đơn | Chín ID cố định trong manifest, không tự đổi khi nhập dữ liệu |
| products[].name / unitLabel | Tên ngắn và quy cách snapshot BE | Tên/quy cách đã chốt; FE giữ nội dung hiển thị, đối chiếu khi đổi quy cách |
| products[].priceVnd | Giá một đơn vị bán, public prices/quote | Số nguyên dương ≤9007199254740991; giá khởi tạo đã có căn cứ trong tài liệu WP02 |
| products[].isActive | Cho bán SKU | Boolean phải do vận hành xác nhận; template release còn null để tránh tự mở bán |
| products[].deliveryScope | SKU giao city hay chỉ near | `city` hoặc `near`; ly bắt buộc `near`, các SKU khác cần xác nhận |
| initial stock: sku / quantity / ownerId / reason / key | Kiểm đếm thực và ledger/audit qua CLI tồn đầu | quantity nguyên không âm theo **đơn vị bán**; Owner active UUID, lý do và key ổn định; không dùng số lượng fixture |

Thông tin hạ tầng/secret cũng phải chuẩn bị ngoài Git theo `.env.example` và runbook
staging: domain/HTTPS/public origin, credential DB API/worker/operations tách riêng,
Redis environment/ACL, HMAC key ring, credential email/Telegram khi triển khai kênh.
Không đặt secret provider vào settings public hoặc gửi chúng vào FE.

## Cách nạp không sửa code

**Dev local/test:** build BE, migrate/grant bằng runner/scripts hiện có; nạp các biến
`CANE_OPERATIONS_DB_*`, tên role API/worker và `CANE_ENVIRONMENT/CANE_REDIS_*` từ file
env được bảo vệ. Sau đó:

```powershell
./scripts/dev/Initialize-Wp02.ps1 -Environment local
# Hoặc bản sao file riêng (ngoài Git) để đổi kịch bản:
./scripts/dev/Initialize-Wp02.ps1 -Environment local -Settings .local/my-settings.json -Stock .local/my-stock.json
```

Settings wrapper phải là `{ "dataKind": "fixture", "values": { ...16 khóa... } }`;
stock giữ shape fixture trong file mẫu. Loader kiểm JSON trùng khóa, kiểu/miền,
tương quan bank/phương thức, đủ SKU và khóa settings. Settings và tồn là hai
transaction riêng; lỗi Redis sau commit không đảo DB. Chỉ dùng DB dev/test có thể
làm lại; đây là fixture setup trực tiếp, không phải lịch sử vận hành được audit.

**Triển khai thật:** sao chép `scripts/catalog/catalog-template.json` ra file release
ngoài Git, hoàn tất boolean/scope, dùng seed catalog và nhập tồn đầu bằng CLI đã có.
Seed catalog giữ nguyên hàng đã tồn tại; **không dùng seed để cập nhật giá hiện có**.
Giá/tồn/settings sau đó dùng các luồng Owner/admin của WP06.

Sao chép `scripts/operations/settings-live.template.json` ra file riêng
`settings-live.json`, điền body `{ "values": { ... } }` theo bảng trên
(bỏ wrapper `dataKind` dành cho fixture). Khi WP06 được triển khai, Owner đăng nhập,
đọc/lưu trong màn Settings hoặc PUT `/api/v1/admin/settings` với phiên/CSRF/origin
đúng hợp đồng, rồi đọc lại GET và hai API public để đối chiếu. **Route ghi settings
chưa được mở trong WP02**; không dùng dev loader để vượt ranh giới đó trên staging/
production. Việc nạp live là điều kiện triển khai gói admin, không yêu cầu sửa code
ứng dụng hay rebuild FE. Nếu chỉ làm WP02, bàn giao file đã chuẩn bị và giữ nhận đơn
thật tắt; không tuyên bố có thể mở bán chỉ từ bộ mock này.

## Checklist trước go-live

- [ ] Vận hành xác nhận shop, địa chỉ, giờ, hướng dẫn pickup, điện thoại và link Zalo.
- [ ] **Thay toàn bộ bank_details mock**, đối chiếu tên ngân hàng, mã, STK, chủ tài
  khoản qua kênh chính thức; kiểm thông tin hiển thị sau khi lưu. Không dùng SĐT dev
  làm tài khoản nhận tiền chỉ vì file dev có số đó.
- [ ] Thay email `.invalid`, chat ID/credential demo và các secret dev; kiểm kênh thật.
- [ ] Duyệt từng mã/tên phường/xã, danh sách vùng phục vụ và allowNear thực tế; kiểm
  ly bị chặn khi giao vùng không allowNear, vùng ngoài danh sách và pickup.
- [ ] Duyệt phí, ngưỡng miễn, maxQty, ngưỡng tồn và thời hạn. Phí là một mức cố định
  của hợp đồng hiện tại; muốn theo từng vùng cần thay yêu cầu trước khi code.
- [ ] Kiểm đếm tồn thật qua CLI có Owner/key/audit; loại mọi stock, event hoặc outbox
  fixture bằng cách dựng DB triển khai sạch, không xóa lịch sử DB thật để làm sạch mock.
- [ ] Hoàn tất chín giá/quy cách/active/scope release, kiểm manifest; không dùng
  `scripts/dev/` hoặc fixture giá biên làm dữ liệu production.
- [ ] Chạy luồng quote/place/thanh toán/hủy/hoàn và thông báo trên artifact triển khai
  của các WP sở hữu; đối chiếu DB và kênh thật. WP02 chưa triển khai những luồng này.
- [ ] Giữ bank_auto_confirm=false cho đến khi nhánh auto được nghiệm thu riêng.
- [ ] Kiểm HTTPS/origin/cookie/CSRF/role, backup/restore, restart/Redis fallback, key
  rotation và checklist staging của WP01/WP07; mock/local tests không thay nghiệm thu.
- [ ] GET `/shop` và `/prices` trả dữ liệu live đúng, không lộ PII/secret/stock chính
  xác; FE không còn dùng kênh giả, bank mock hoặc giá fixture.
- [ ] Owner nghiệm thu cấu hình và demo trước khi bật accepting_orders/phương thức
  thanh toán. Ghi người xác nhận, thời điểm và revision cấu hình ngoài dữ liệu public.
