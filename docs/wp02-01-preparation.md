# WP02-01 — Dữ liệu thử và môi trường tích hợp riêng

Dùng hướng dẫn này khi cần môi trường kiểm tích hợp riêng với fixture WP02. Nguồn gốc fixture và các quyết định về chín SKU được ghi trong [biên nhận WP02-01/02](../../cane-mvp/evidence/wp02/wp02-01-02-execution.md). Để chạy hệ thống local hằng ngày, bắt đầu từ [README backend](../README.md).

## Dữ liệu chỉ dành cho local/test

Fixture nằm trong `src/test/resources`, không đóng gói vào JAR runtime. Fixture
WP01 cũ vẫn dùng tên/quy cách tùy ý để kiểm parser. WP02 dùng tên tiếng Anh FE hiện
có và `unitLabel` đã được chủ dự án xác nhận, đồng bộ template release tại
`scripts/catalog/catalog-template.json`. Giá, active/tồn/scope, liên hệ, vùng,
tài khoản ngân hàng và settings trong fixture vẫn là dữ liệu thử.

| SKU | UnitLabel được xác nhận |
|---|---|
| cane-juice-bottle | Bottle 300 ml |
| cane-juice-cup | Cup 500 ml (with ice) |
| cane-tea | Pouch 100 g |
| cane-candy | Pouch 150 g |
| cane-syrup | Bottle 330 ml |
| cane-molasses | Jar 450 g |
| raw-cane-sugar | Pouch 500 g |
| whole-cane-sugar | Pouch 500 g |
| cane-vinegar | Bottle 330 ml |

Ly 500 ml là dung tích danh nghĩa của ly phục vụ gồm đá, không cam kết 500 ml nước
mía tịnh. Template release đã điền tên/quy cách, còn `priceVnd`, `isActive` và scope
chưa xác nhận vẫn null (cup giữ near theo contract); CLI tiếp tục từ chối template
chưa đủ dữ liệu. Không sao chép giá fixture vào release để vượt guard này.

- `catalog/wp02/seed-fixture.json`: đúng định dạng seed CLI, `dataKind=fixture`,
  chín SKU/tên/quy cách đã xác nhận, các giá/trạng thái/phạm vi vẫn là dữ liệu thử;
  không chứa tồn/settings.
- `catalog/wp02/stock-fixture.json`: trạng thái tồn thử, ngưỡng low=5.
- `admin/wp02/settings-default.json`: đủ 16 giá trị khởi tạo của migration hiện có.
- `admin/wp02/settings-scenarios.json`: patch trên mặc định, có cờ `validSettings`.
- `public/wp02/*.json`: **body response** không bọc `data`, không có metadata
  `dataKind`, header hoặc trường nội bộ. Thời gian prices cố định
  `2026-11-14T00:00:00Z` chỉ để test; API thật phải sinh `serverTime` lúc trả.
- `public/wp02/load-scenario.sql`: nạp baseline tồn/settings vào DB thử vừa tạo,
  không phải lệnh nghiệp vụ nhập kho và không chứng minh ledger/nhập tồn đầu.

| SKU | Giá VND thử | Active | Tồn thử | Scope | Availability kỳ vọng |
|---|---:|---|---:|---|---|
| cane-juice-bottle | 25001 | true | 6 | city | in_stock |
| cane-juice-cup | 19000 | true | 1 | near | low |
| cane-tea | 33000 | true | 4 | city | low |
| cane-candy | 1 | true | 5 | city | in_stock |
| cane-syrup | 125000 | false | 0 | city | paused |
| cane-molasses | 87000 | true | 0 | city | out |
| raw-cane-sugar | 47000 | false | 6 | city | paused |
| whole-cane-sugar | 9007199254740991 | true | 5 | city | in_stock |
| cane-vinegar | 63000 | true | 6 | city | in_stock |

Giá 25001 kiểm không làm tròn; 1 và số nguyên an toàn tối đa kiểm biên. Giá tối đa
cố ý làm phép nhân số lượng 2/cộng thêm dòng khác vượt miền JSON, không dùng làm
dữ liệu demo mua hàng thông thường hoặc giá mở bán. Tồn bằng ngưỡng là in_stock;
inactive ưu tiên paused ngay cả tồn 0. `prices-missing-sku.json` bỏ duy nhất vinegar,
không bù giá/card giả. T03 SKU chỉ có BE được fault-inject ở test FE sau này; bộ seed
vẫn giữ đúng chín SKU, không thêm sản phẩm thứ mười vào contract.

| Shop response | Dữ liệu/ý nghĩa |
|---|---|
| shop-default.json | Store/support/phí null, vùng/phương thức rỗng; acceptingOrders=true, deliveryEnabled=false |
| shop-complete.json | Store/support thử có đủ; cod+bank_transfer với tài khoản giả; phí 17001, ngưỡng miễn phí 100001, hai vùng thử allowNear true/false |
| shop-closed.json | Cấu hình complete, acceptingOrders=false; giữ catalog/nút thêm trên FE |
| shop-null-fee.json | Có vùng nhưng phí null: deliveryEnabled=false, không đổi null thành 0 |
| shop-no-zones.json | Có phí nhưng vùng rỗng: deliveryEnabled=false |
| shop-zero-fee.json | Phí/ngưỡng 0 hợp lệ; deliveryEnabled=true khi có vùng |
| shop-bank-incomplete.json | Cố ý mô phỏng stored drift: bật bank nhưng bank_details=null; response bỏ bank_transfer |
| shop-methods-disabled.json | Store đầy đủ nhưng phương thức rỗng |

`bank-incomplete` là dữ liệu sai tương quan: `SettingsValidator` hiện hành phải từ
chối ghi. Chỉ dùng DB fixture riêng để kiểm đường đọc lọc bank theo API §3.1, không
coi đây là settings có thể lưu qua service. Các case còn lại phải qua validator.
`bankConfirmationMode=manual` ngay cả khi bank tắt, vì `bank_auto_confirm=false`;
không tự trả null hoặc bật auto khi nhánh tự động chưa nghiệm thu. DTO shop có
13 trường flat; support null hoặc đúng `{phone,zaloUrl}`. Không có pickupEnabled,
owner_notify, quyền Operator, bank_details; prices không có tên/quy cách/số tồn.

Các response dùng lại tại WP02-03/04 và FE WP02-07/08. Nếu test FE cần sao chép, ghi
đường nguồn + commit BE đã ghim; CI repo riêng không được mặc định có thư mục sibling.
Đây chưa là cơ chế `check:catalog` của WP02-06. Null/loading/timeout/503, JSON sai,
giá sai kiểu/ngoài miền, trường mới tương thích và phản hồi đến muộn là fault của
test HTTP/client sau này, không đưa chúng vào manifest seed hợp lệ.

## Kiểm tự động hiện có và thêm mới

```powershell
# Từ cane-api-springboot; dùng đúng JDK/Maven/Docker đã khóa.
pwsh -NoProfile -File scripts/Test-Toolchain.ps1
.\mvnw.cmd -B -ntp '-Dtest=Wp02FixturesTest,CatalogManifestReaderTest' test
pwsh -NoProfile -File scripts/Test-Backend.ps1
```

`Wp02FixturesTest` kiểm bộ SKU/giá, quy cách và tên khớp template, giá trị release
chưa xác nhận còn null/không dùng được, DTO allowlist, biên tiền, settings hợp lệ và
case drift bị từ chối. `Wp02PreparationIT` migrate/grant PostgreSQL dùng harness
`TestInfrastructure`, seed qua `CatalogSeedCommand`, chạy chính SQL nạp baseline
trên DB/container mới và kiểm không tạo đơn/staff/event/ledger/outbox. Harness dùng
credential ngẫu nhiên, cổng ánh xạ động, Redis thử chỉ cho `cane:test:*`; không nối
DB dev. Runner chuẩn có Compose project ngẫu nhiên và tự dọn đúng project đó;
tham số `-EnvFile` mới cho phép chọn env thử riêng, mặc định vẫn `.local/dev.env`.
Port trong file được chọn phải khả dụng; runner không sửa port/file của người dùng.

## Tuyến tích hợp thủ công cho các task API/FE tiếp theo

Lệnh dưới đây chạy trong **một PowerShell 7 ở gốc backend**. Dùng lại Compose,
init-role/Redis ACL, migration/grant và seed hiện có. Tạo env/project/volume/cổng
riêng; không dùng/ghi đè `.local/dev.env` hay volume dev. Chạy API riêng bằng role
runtime, không cấp credential vận hành cho API. Dừng API trước bước cleanup.

```powershell
$ErrorActionPreference = 'Stop'
& ./scripts/Test-Toolchain.ps1
function Assert-Wp02Exit { if ($LASTEXITCODE -ne 0) { throw 'WP02 command failed; stop and inspect.' } }
function New-Wp02Port {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
$project = 'cane-wp02-test-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$testDir = Join-Path (Get-Location) ('.local/' + $project)
$null = New-Item -ItemType Directory -Path $testDir
$envFile = Join-Path $testDir 'test.env'
$values = @{}
foreach ($name in @('CANE_POSTGRES_ADMIN_PASSWORD','CANE_MIGRATION_PASSWORD','CANE_API_DB_PASSWORD','CANE_WORKER_DB_PASSWORD','CANE_REDIS_PASSWORD')) {
    $values[$name] = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
}
$values['CANE_POSTGRES_PORT'] = New-Wp02Port
do { $values['CANE_REDIS_PORT'] = New-Wp02Port } while ($values['CANE_REDIS_PORT'] -eq $values['CANE_POSTGRES_PORT'])
do { $values['CANE_API_PORT'] = New-Wp02Port } while ($values['CANE_API_PORT'] -in @($values['CANE_POSTGRES_PORT'],$values['CANE_REDIS_PORT']))
[IO.File]::WriteAllLines($envFile, @($values.Keys | ForEach-Object { "$_=$($values[$_])" }), [Text.UTF8Encoding]::new($false))
$override = Join-Path $testDir 'compose.test.yaml'
[IO.File]::WriteAllText($override, "services:`n  redis:`n    environment:`n      CANE_ENVIRONMENT: test`n", [Text.UTF8Encoding]::new($false))
$compose = @('compose','--env-file',$envFile,'--project-name',$project,'-f','compose.yaml','-f',$override)
# No secret values are printed; record this project/path for cleanup.
Write-Output "WP02 owned project: $project; state: $testDir"
& docker @compose config --quiet
Assert-Wp02Exit
& docker @compose up --detach --wait --wait-timeout 120
Assert-Wp02Exit
```

Các port chọn được trả về hệ điều hành trước khi Compose bind; nếu có tiến trình
khác chiếm đúng lúc, lệnh up thất bại và phải dừng/dọn project vừa tạo. Không đổi
cổng/container của tiến trình khác. File env/HMAC nằm ngoài Git; không chia sẻ
`docker compose config` không có `--quiet` hoặc dump môi trường.

Nếu chỉ chạy runner chuẩn bằng credential/cổng riêng, dùng bước tạo `$envFile` ở
trên rồi chạy `pwsh -NoProfile -File scripts/Test-Backend.ps1 -EnvFile $envFile`
**trước** lệnh Compose up thủ công. Runner tự up/down project của nó; không chạy
hai stack với cùng port. `-EnvFile` không tồn tại bị từ chối, không tự tạo hay ghi
đè env thay thế.

```powershell
.\mvnw.cmd -B -ntp package -DskipTests
Assert-Wp02Exit
$env:CANE_MIGRATION_URL = "jdbc:postgresql://127.0.0.1:$($values['CANE_POSTGRES_PORT'])/cane_local"
$env:CANE_MIGRATION_USER = 'cane_migrator'
$env:CANE_MIGRATION_PASSWORD = $values['CANE_MIGRATION_PASSWORD']
& ./scripts/database/Migrate-Core.ps1
Assert-Wp02Exit
& docker @compose cp scripts/database/Grant-Runtime.sql postgres:/tmp/wp02-grants.sql
Assert-Wp02Exit
& docker @compose exec -T postgres psql -X --no-password -U cane_migrator -d cane_local -v cane_api_role=cane_api -v cane_worker_role=cane_worker -f /tmp/wp02-grants.sql
Assert-Wp02Exit
$env:CANE_OPERATIONS_DB_URL = $env:CANE_MIGRATION_URL
$env:CANE_OPERATIONS_DB_USER = 'cane_migrator'
$env:CANE_OPERATIONS_DB_PASSWORD = $values['CANE_MIGRATION_PASSWORD']
& ./scripts/catalog/Seed-Catalog.ps1 -Manifest src/test/resources/catalog/wp02/seed-fixture.json -Environment test
Assert-Wp02Exit
# Select a fixture case explicitly; default/closed/zero-fee/etc. use the same fresh DB.
$scenarioName = 'complete'
$settings = Get-Content -Raw src/test/resources/admin/wp02/settings-default.json | ConvertFrom-Json -AsHashtable
$scenarios = Get-Content -Raw src/test/resources/admin/wp02/settings-scenarios.json | ConvertFrom-Json -AsHashtable
$scenario = @($scenarios.scenarios | Where-Object { $_.name -eq $scenarioName })
if ($scenario.Count -ne 1) { throw 'Unknown or duplicate scenario.' }
foreach ($key in $scenario[0].patch.Keys) { $settings[$key] = $scenario[0].patch[$key] }
$settingsJson = ConvertTo-Json -InputObject $settings -Depth 10 -Compress
$stockJson = Get-Content -Raw src/test/resources/catalog/wp02/stock-fixture.json
& docker @compose cp src/test/resources/public/wp02/load-scenario.sql postgres:/tmp/wp02-load.sql
Assert-Wp02Exit
& docker @compose exec -T postgres psql -X --no-password -U cane_migrator -d cane_local -v "stockJson=$stockJson" -v "settingsJson=$settingsJson" -f /tmp/wp02-load.sql
Assert-Wp02Exit
# Verify fixture state without claiming an HTTP test.
& docker @compose exec -T postgres psql -X --no-password -U cane_migrator -d cane_local -c 'SELECT sku,price_vnd,stock_qty,is_active,delivery_scope FROM cane.products ORDER BY sku;'
Assert-Wp02Exit
```

SQL baseline chỉ dùng trong project mới sở hữu; không chỉ đổi URL để chạy với DB có
sẵn. Khi đổi seed/giá/trạng thái hoặc kiểm cache/invalidation sau này, tạo lại project
mới hay dùng writer đúng task; seed chỉ thêm dòng thiếu, không ghi đè dữ liệu cũ.
Không dùng SQL nạp fixture để chứng minh invalidation của writer nghiệp vụ.

```powershell
$env:CANE_DB_URL = $env:CANE_MIGRATION_URL
$env:CANE_DB_USER = 'cane_api'
$env:CANE_DB_PASSWORD = $values['CANE_API_DB_PASSWORD']
$env:CANE_REDIS_HOST = '127.0.0.1'
$env:CANE_REDIS_PORT = [string]$values['CANE_REDIS_PORT']
$env:CANE_REDIS_USER = 'cane_runtime'
$env:CANE_REDIS_PASSWORD = $values['CANE_REDIS_PASSWORD']
$env:CANE_API_PORT = [string]$values['CANE_API_PORT']
$env:PUBLIC_ORIGIN = 'http://localhost:3000'
$env:CANE_TRUSTED_PROXIES = ''
$key = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_')
$env:LINK_SECRETS = ConvertTo-Json -InputObject @{ wp02 = $key } -Compress
$env:LINK_ACTIVE_KID = 'wp02'
# Remove operational credentials before starting the API child process.
foreach ($name in @('CANE_MIGRATION_URL','CANE_MIGRATION_USER','CANE_MIGRATION_PASSWORD','CANE_OPERATIONS_DB_URL','CANE_OPERATIONS_DB_USER','CANE_OPERATIONS_DB_PASSWORD')) {
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}
Write-Output "Backend upstream for the later trusted FE proxy: http://127.0.0.1:$($values['CANE_API_PORT'])"
& java -jar target/cane-ecommerce-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=test,api --cane.environment=test
# Ctrl+C stops this foreground process; then execute cleanup below.
```

`test,api` và `cane.environment=test` chọn đúng namespace Redis `cane:test:*`;
`application-test.yml` chỉ ở test classpath nên JAR cần tham số environment rõ ràng.
Không dùng `Run-Local.ps1` ở tuyến này: script đó cố định `.local/dev.env`/profile
local. Không khởi chạy worker cho thao tác chỉ xem catalog/giỏ.

WP02-07 sẽ cấp rewrite/proxy server-side cùng origin FE `/api/v1/...` đến upstream
vừa in. Chạy FE ở `http://localhost:3000`, kiểm request browser thực sự đi qua FE →
API → DB/Redis. Chưa có proxy/endpoint public trong phạm vi task 01/02 nên hiện tại
GET shop/prices còn 403; **không coi stub JSON hoặc khởi động API là tích hợp đạt**.
Ở các task sau cần ghi cặp commit/build FE/BE, case fixture, no-store cả tuyến,
trước/sau số dòng orders/stock_movements/business_events/outbox và trạng thái tồn;
fault Redis chỉ tác động project thử này, không FLUSH Redis khác.

```powershell
# Same PowerShell, after stopping API. Only resources of the exact owned project.
if ($project -notmatch '^cane-wp02-test-[a-f0-9]{12}$') { throw 'Unexpected cleanup target.' }
& docker @compose down --volumes --remove-orphans
Assert-Wp02Exit
foreach ($name in @('CANE_DB_URL','CANE_DB_USER','CANE_DB_PASSWORD','CANE_REDIS_HOST','CANE_REDIS_PORT','CANE_REDIS_USER','CANE_REDIS_PASSWORD','CANE_API_PORT','PUBLIC_ORIGIN','CANE_TRUSTED_PROXIES','LINK_SECRETS','LINK_ACTIVE_KID')) {
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}
# Close the dedicated shell to discard values/HMAC; keep/delete only its ignored
# $testDir after inspecting the absolute path. Never prune Docker globally.
```

## Bằng chứng lần chuẩn bị

Ngày 14/11/2026, lượt cuối đạt 24 ca tập trung và 162 unit + 233 integration. Bản ghi đủ gồm checksum JAR `1f70ab91e0e35cfd1ba0f9a0891f3b9f0c12b8bddc3d71a0ddd13473dcd7fab3`, lỗi bind cổng 55432 ở lượt trước và cách cô lập môi trường thử. [Kết quả và revision FE/BE](../../cane-mvp/evidence/wp02/wp02-01-02-execution.md). Để tra transcript cũ, mở ZIP v3 hoặc chạy `git show 16a17d6:docs/wp02-01-preparation.md` trong clone BE.
