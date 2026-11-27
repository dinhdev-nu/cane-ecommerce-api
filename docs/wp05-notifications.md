# WP05 — xem đơn và thông báo

Khách nhận email theo sự kiện của đơn. Owner nhận thông báo qua email và chat Telegram đã cấu hình. Khi mở bán ngày 26/11/2026, worker đã bật gửi thật. SES Singapore và Telegram đã xác nhận tiếp nhận tin kiểm. [Biên nhận phát hành](../../cane-mvp/evidence/wp07/wp07-execution.md#wp07-go-live-20261126) · [Dashboard và các tác vụ nhắc](wp06-admin-worker.md)

## Chạy dev có kiểm soát

1. Chuẩn bị PostgreSQL/Redis/schema/role và HMAC theo runbook hiện có. API và worker
   dùng cùng `PUBLIC_ORIGIN`, `LINK_SECRETS`, `LINK_ACTIVE_KID`, khác tài khoản DB.
2. Chuẩn bị đầu vào riêng ngoài Git bằng `scripts/dev/Prepare-Wp05.ps1 -InputFile
   <intake.env> -Destination <private-directory>`. Helper chỉ chấp nhận public origin
   loopback; sinh `settings.dev.json`, `worker.dev.env`, `copy-notes.txt`. Không ghi DB,
   không gửi tin, không tự dùng bot token thật. Không ghi đè file riêng đã có.
3. Nạp `settings.dev.json` qua `scripts/dev/Initialize-Wp02.ps1 -Environment local
   -Settings <private-file>` với credential fixture và stock fixture phù hợp. Đây là
   seed DEV; không dùng trên staging/live. Không chạy lại seed stock vào DB có đơn
   để chỉ đổi cấu hình. Dữ liệu live dùng luồng vận hành/settings WP06.
4. `docker compose -f compose.notifications.yaml up -d`. SMTP bắt tại loopback 1025,
   xem đầy đủ thư tại `http://127.0.0.1:8025`; không cấu hình relay. Có thể đổi cổng
   bằng môi trường `SMTP_PORT`/`MAILPIT_UI_PORT`, đồng thời đổi private env worker.
5. Chạy API như trước; chạy worker bằng `scripts/Run-Local.ps1 -Mode worker
   -NotificationEnvFile <worker.dev.env>`. `NOTIFICATIONS_ENABLED=true` mới dispatch.
   Telegram mock tạo file `<outbox-id>-<attempt>.telegram.json` có `mock=true` tại thư
   mục ngoài repo. Không log recipient, nội dung, credential hoặc URL có token.
6. Đặt đơn trên FE; mở View order hoặc link email trong SMTP capture. Link chỉ có
   fragment `#t=`, API chỉ nhận Bearer. Không copy token vào query/log/storage. Ngân
   hàng trong seed là **MOCK DEV — KHÔNG CHUYỂN TIỀN**.
7. Khi xong, dừng đúng process/project notification đã tạo; không prune Docker chung.

`copy-notes.txt` là ghi chú để người viết template xem, không chèn nguyên văn vào
email. Template hiện hỗ trợ vi/en theo cấu hình worker; đây không phải setting mới
của cửa hàng hoặc chức năng đa ngôn ngữ toàn storefront. Test customer email là dữ
liệu từng đơn thử; bot username chỉ để vận hành. Không lưu hai trường này vào settings.

## Bảng cấu hình worker

| Biến | Ý nghĩa/nơi dùng | Định dạng/ví dụ an toàn |
|---|---|---|
| `NOTIFICATIONS_ENABLED` | Bật scheduler gửi, mặc định tắt | `true` / `false` |
| `SMTP_MODE` | `capture` dev hoặc SMTP `live` | `capture` |
| `SMTP_HOST`, `SMTP_PORT` | Máy/cổng nhận SMTP | Dev `127.0.0.1`, `1025`; live theo provider |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | Xác thực SMTP khi provider yêu cầu; secret của worker | Chuỗi riêng, không đưa vào Git/chat |
| `EMAIL_FROM` | Địa chỉ người gửi; live phải được provider xác minh | Dev `dev@cane.invalid`; live do Owner cung cấp |
| `EMAIL_FROM_NAME` | Tên hiển thị người gửi | `Cane Shop` |
| `EMAIL_REPLY_TO` | Địa chỉ nhận thư trả lời | Hộp thư vận hành đã duyệt |
| `EMAIL_FEEDBACK_TO` | Địa chỉ nhận phản hồi phát thư qua SMTP envelope | Hộp thư theo cấu hình SES của môi trường |
| `EMAIL_LANGUAGE` | Ngôn ngữ template thông báo | `vi` hoặc `en` |
| `TELEGRAM_MODE` | Capture giả lập hoặc Bot API thật, không chuyển tự động | `mock` / `live` |
| `TELEGRAM_BOT_TOKEN` | Secret gửi Bot API, chỉ worker/live | Token do BotFather cấp, không lưu DB |
| `NOTIFICATION_CAPTURE_DIRECTORY` | Đích Telegram mock, bắt buộc ngoài repo | Đường dẫn tuyệt đối private |
| `PUBLIC_ORIGIN`, `LINK_SECRETS`, `LINK_ACTIVE_KID` | URL FE và HMAC hiện hành để dựng link lúc gửi | Theo `.env.example`, cùng API |

Owner email/chat ID thuộc `settings.owner_notify`; store/support thuộc settings,
không nằm trong secret vận chuyển. Recipient/source/channel chốt khi enqueue và
không bị thay bởi settings mới trong retry. Thay env cần khởi động lại worker; thay
settings dùng luồng settings được hỗ trợ, không sửa code hoặc outbox cũ.

SMTP live dùng STARTTLS bắt buộc, xác minh chứng chỉ, connection/read/write timeout
và đóng socket khi vượt tổng 30 giây; provider dùng implicit TLS riêng cần đối chiếu
cấu hình trước khi chọn. Telegram gọi HTTPS `sendMessage`, không cần webhook. Hai
adapter không có exactly-once: ACK rồi crash trước commit `sent` có thể gửi trùng.
Message-ID SMTP ổn định theo outbox ID chỉ hỗ trợ đối chiếu/dedup, không bảo đảm inbox.
Tham khảo [Spring Boot mail](https://docs.spring.io/spring-boot/reference/io/email.html),
[Mailpit Docker](https://mailpit.axllent.org/docs/install/docker/),
[Telegram Bot API](https://core.telegram.org/bots/api#sendmessage).

## Kiểm lại khi dựng môi trường hoặc đổi nhà cung cấp

- Xác minh HTTPS origin, địa chỉ gửi, Reply-To, DNS email và quyền SMTP. Kiểm SPF, DKIM và DMARC theo cấu hình của domain.
- Đối chiếu đích nhận của Owner, bot và chat Telegram. Khi được phép gửi thử, dùng địa chỉ và chat đã duyệt. Kiểm thời gian từ sự kiện tới phản hồi nhà cung cấp theo mốc 60 giây, rồi kiểm timeout, retry, lease và cảnh báo.
- Nạp dữ liệu vận hành đã duyệt cho môi trường mới, gồm tài khoản ngân hàng, liên hệ, vùng giao, phí và tồn. Với bản mở bán thủ công, xác minh `bank_auto_confirm=false`.
- Cấp bộ khóa HMAC và thông tin DB, Redis, SMTP, bot riêng cho từng môi trường. Kiểm quy trình xoay khóa trước khi chuyển lưu lượng.

## Khi worker đang gửi

Worker claim tối đa 20, concurrency 4, lease 5 phút, tối đa 8 lượt. Mọi completion
fence bằng id/status/lockedAt/attempts; retry 30s tăng gấp đôi, cap 3600s + jitter
0–20%. Dòng `sent/skipped/exhausted` không tự mở lại. Owner resend tạo thông báo mới,
không sửa dòng exhausted. Chỉ Owner alerts kiểm điều kiện hiện tại; email nghiệp vụ
mô tả dữ kiện tại sự kiện, link dựng bằng phiên bản/hạn hiện hành lúc gửi.
