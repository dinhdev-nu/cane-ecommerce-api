# Seed Catalog — WP01-06

Nguồn: thiết kế dữ liệu §9.1, backlog WP01-06 và nhóm kiểm §3.4. Ngày 10/11/2026
chủ dự án chốt chín ID frontend làm SKU ổn định: `cane-juice-bottle`,
`cane-juice-cup`, `cane-tea`, `cane-candy`, `cane-syrup`, `cane-molasses`,
`raw-cane-sugar`, `whole-cane-sugar`, `cane-vinegar`. Đối chiếu ở
`cane-mvp/docs/02-storefront-design.md#sf-products`; chưa chốt giá/quy cách thật.

## Chuẩn bị và chạy

1. Build JAR bằng Maven Wrapper; migrate/validate core bằng lệnh vận hành riêng.
   Seed chỉ validate schema/checksum, không migrate/repair/baseline hoặc tạo schema.
2. Sao chép `scripts/catalog/catalog-template.json` vào nơi quản lý dữ liệu vận
   hành. Điền đủ tên ngắn, đơn vị/quy cách, giá VND nguyên dương, `isActive` boolean
   và `deliveryScope` city/near cho từng SKU; nước mía ly dùng near. Giữ `dataKind`
   là release cho dữ liệu đã được xác nhận. Mẫu cố ý để null: chưa điền thì lệnh dừng
   trước kết nối DB. Không lấy giá/đơn vị fixture hoặc hình mẫu làm dữ liệu thật.
3. Cấp `CANE_OPERATIONS_DB_URL`, `CANE_OPERATIONS_DB_USER`,
   `CANE_OPERATIONS_DB_PASSWORD` qua môi trường tiến trình bằng cơ chế quản lý secret
   của môi trường. URL JDBC PostgreSQL, credential vận hành riêng; không thêm quyền
   INSERT sản phẩm cho API/worker. Có thể dùng credential vận hành/migration hiện
   có theo quyền được cấp, chỉ cho tiến trình một lần; không dùng để chạy dịch vụ.
4. Xác minh đúng database đích rồi chạy từ repo backend:

```powershell
pwsh -NoProfile -File scripts/catalog/Seed-Catalog.ps1 `
  -Manifest 'D:\path-to-confirmed-data\catalog.json' -Environment production
```

`-Jar` tùy chọn trỏ tới JAR build cụ thể. `-Environment` bắt buộc là
local/test/staging/production, là lựa chọn tường minh của người vận hành, không phải
cơ chế tự nhận biết môi trường từ database. Không đặt password trong tham số Java,
PowerShell hoặc file manifest. Không commit manifest thật nếu chứa dữ liệu chưa công bố.

Script gọi main riêng qua PropertiesLauncher trong cùng JAR, không khởi động Spring
Boot/API/worker/Redis. CLI chỉ ghép DataSource, transaction và các thành phần Catalog
cần thiết; log thư viện bị tắt trong tiến trình CLI, kết quả/lỗi dùng thông báo cố
định không in ngoại lệ, SQL, password hoặc URL DB. API/worker không chạy seed startup.

## Hành vi chạy lại và lỗi

- Manifest JSON UTF-8 tối đa 64 KiB, đúng hai trường `dataKind`/`products`; mỗi dòng
  đúng sku/name/unitLabel/priceVnd/isActive/deliveryScope. Đúng chín SKU duy nhất,
  phân biệt hoa thường; không nhận stockQty, setting hoặc trường lạ. Tên/đơn vị tối
  đa 100 ký tự sau trim; giá 1..9.007.199.254.740.991. Null, float, ép chuỗi thành số,
  duplicate key hoặc nội dung JSON nối thêm đều bị từ chối.
- Khóa advisory chung cho seed, rồi khóa dòng SKU tăng dần, tất cả cùng transaction
  write nền. Chỉ INSERT dòng thiếu, stock_qty=0, ON CONFLICT không UPDATE. Giá/tồn/
  trạng thái/phạm vi/tên/đơn vị/updated_at/updated_by của dòng hiện hữu giữ nguyên,
  kể cả khác manifest. Kiểm tập SKU và miền dữ liệu hiện hữu; SKU ngoài bộ đã chốt
  khiến lệnh dừng để người vận hành rà, không tự xóa/sửa.
- Không ghi settings, staff, ledger/event hay outbox khi chỉ tạo SKU. Settings thuộc
  V001. Nhập tồn thật có Owner/event/ledger/key chống lặp thuộc task 14.
- Lỗi giữa chừng rollback toàn bộ dòng mới của lần chạy. Các dòng đã có từ lần
  khởi tạo dở trước đó được giữ, lần tiếp theo bổ sung phần thiếu. Mất response sau
  commit chạy lại cùng manifest sẽ kiểm dòng hiện hữu, không cộng tồn hoặc đổi giá.
- Hai seed tranh khóa: một bên được phép thực hiện, bên còn lại dừng; chạy lại sau
  khi bên đầu kết thúc. Không tự dùng bộ SKU khác hoặc xóa dữ liệu để chữa lỗi.
- Kết quả `inserted=N preserved=M`, N+M=9; lỗi trả exit code khác 0. Khi không rõ
  commit, kiểm database hoặc chạy lại manifest; seed không ghi đè dòng đã tồn tại.

Fixture nằm riêng `src/test/resources/catalog/nine-skus-fixture.json`, ghi rõ
`dataKind=fixture`, chỉ chạy với local/test. Không được đóng gói vào JAR runtime;
CLI từ chối fixture khi chọn staging/production. Đây là hàng rào chống nhầm dữ liệu,
không thể thay quyết định xác nhận manifest mở bán của chủ dự án.

## Kiểm và phần còn tiếp nối

`CatalogManifestReaderTest` kiểm manifest; `CatalogSeedIT` dùng PostgreSQL thật,
transaction/repository/service và script PowerShell/JAR thật. Kiểm tạo/chạy lại,
giữ mọi cột/settings, rollback, seed dở, tranh khóa, runtime bị từ chối và schema
sai/chưa migrate. [Hồ sơ](wp01-06-verification.md) ghi kết quả đúng build.

Chưa có manifest giá/quy cách/trạng thái bán/phạm vi của dữ liệu thật đầy đủ. Chưa
seed production hoặc mở bán. CI đồng bộ catalog/asset đầy đủ thuộc WP02; seed không
tự tạo `catalog.ts` hay đổi thông tin hiển thị frontend trong task nền.
