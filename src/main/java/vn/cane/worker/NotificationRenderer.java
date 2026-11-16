package vn.cane.worker;

import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vn.cane.admin.application.CheckoutSettings;
import vn.cane.orders.application.OrderLinkTokens;
import vn.cane.orders.domain.OrderLinkState;

/** Templates consume allowlisted immutable event facts. All dynamic HTML is escaped here. */
@Component
@Profile("worker & !api")
public final class NotificationRenderer {
    private final OrderLinkTokens tokens;
    private final Clock clock;
    private final String origin;
    private final boolean vietnamese;

    public NotificationRenderer(
            OrderLinkTokens tokens,
            Clock clock,
            @Value("${cane.http.public-origin}") String origin,
            @Value("${cane.notifications.language:vi}") String language) {
        if (!java.util.Set.of("vi", "en").contains(language))
            throw new IllegalStateException("Unsupported notification language");
        vietnamese = language.equals("vi");
        this.tokens = tokens;
        this.clock = clock;
        this.origin = origin.replaceAll("/$", "");
    }

    public Rendered render(OutboxLease lease, NotificationSourceLoader.Source source) {
        boolean owner = lease.kind().startsWith("owner_");
        var event = source.event();
        var facts = event.facts();
        String number = source.order() == null ? "" : source.order().order().number();
        String title = title(lease.kind());
        var text = new StringBuilder(title)
                .append(" — ")
                .append(number)
                .append('\n')
                .append(copy(
                        "Cane Shop is sending you this update.\nRecorded at: ",
                        "Cane Shop gửi bạn thông tin cập nhật.\nThời điểm ghi nhận: "))
                .append(event.at())
                .append('\n');
        if (facts.path("paymentStatus").isString())
            text.append(copy("Payment at this event: ", "Thanh toán tại thời điểm này: "))
                    .append(state(facts.path("paymentStatus").asString()))
                    .append('\n');
        String status = event.toStatus() == null ? facts.path("status").asString("") : event.toStatus();
        if (!status.isEmpty())
            text.append(copy("Order at this event: ", "Đơn tại thời điểm này: "))
                    .append(state(status))
                    .append('\n');
        if (lease.kind().equals("order_placed") || lease.kind().equals("owner_new_order")) {
            var row = source.order().order();
            for (var item : source.order().items())
                text.append(item.quantity())
                        .append(" × ")
                        .append(item.name())
                        .append(" (")
                        .append(item.unitLabel())
                        .append(") — ")
                        .append(item.lineTotalVnd())
                        .append(" VND\n");
            text.append(copy("Total: ", "Tổng tiền: "))
                    .append(row.total())
                    .append(copy(" VND\nReceiving: ", " VND\nCách nhận: "))
                    .append(state(row.fulfillment()))
                    .append('\n');
            if ("awaiting_payment".equals(status) && row.bank() != null) {
                for (String key : java.util.List.of("bankName", "accountName", "accountNo"))
                    text.append(text(row.bank(), key)).append('\n');
                text.append(copy("Transfer content: ", "Nội dung chuyển khoản: "))
                        .append(row.number())
                        .append('\n');
                if ("manual".equals(row.bankMode()))
                    text.append(
                            copy(
                                    "The shop will confirm your transfer manually. Contact the shop if needed.\n",
                                    "Cane Shop sẽ kiểm tra và xác nhận chuyển khoản của bạn. Bạn có thể liên hệ hỗ trợ khi cần.\n"));
            }
        }
        if (lease.kind().equals("payment_received") || lease.kind().equals("refunded")) {
            text.append(copy("Receipt amount: ", "Số tiền của khoản thu: "))
                    .append(facts.path("amountVnd").asLong())
                    .append(copy(" VND\nMethod: ", " VND\nPhương thức: "))
                    .append(state(facts.path("method").asString()))
                    .append('\n')
                    .append(
                            facts.path("isPrincipal").asBoolean()
                                    ? copy("Principal receipt\n", "Khoản thu chính\n")
                                    : copy("Additional receipt\n", "Khoản thu bổ sung\n"));
            if ("close_review".equals(facts.path("action").asString("")))
                text.append(
                        copy(
                                "The shop verified a previously recorded receipt; this is not a new payment.\n",
                                "Cane Shop đã xác minh khoản thu được ghi nhận trước đó; đây không phải một lần thanh toán mới.\n"));
            if (lease.kind().equals("refunded"))
                text.append(copy("Refund recorded at: ", "Hoàn tiền được ghi nhận lúc: "))
                        .append(facts.path("refundedAt").asString())
                        .append('\n');
        }
        if (lease.kind().equals("cancelled") || lease.kind().equals("delivery_failed_closed"))
            text.append(copy("Closure type: ", "Loại đóng đơn: "))
                    .append(state(facts.path("cancelKind").asString()))
                    .append('\n');
        if (owner) {
            text.append("Attention: ").append(event.kind()).append('\n');
            String alert = facts.path("alertType").asString("");
            if (!alert.isEmpty()) text.append("Alert: ").append(alert).append('\n');
            if (lease.orderId() != null)
                text.append("Order ID: ").append(lease.orderId()).append('\n');
            if (lease.paymentId() != null)
                text.append("Receipt ID: ").append(lease.paymentId()).append('\n');
        }
        UUID expiredId = null;
        long expiredVersion = 0;
        String viewUrl = null;
        if (!owner) {
            var row = source.order().order();
            var link = new OrderLinkState(row.id(), row.linkVersion(), row.linkExpiresAt());
            if (link.validAt(clock.instant())) {
                viewUrl = origin + "/orders/view#t=" + tokens.sign(link).value();
                text.append(copy("View order: ", "Xem đơn của bạn: "))
                        .append(viewUrl)
                        .append('\n');
            } else {
                expiredId = row.id();
                expiredVersion = row.linkVersion();
                text.append(copy(
                        "Your viewing link has expired. Contact the shop for a new link.\n",
                        "Link xem đơn đã hết hạn. Bạn vui lòng liên hệ Cane Shop để nhận link mới.\n"));
            }
        }
        var support = source.settings().get("support");
        String phone = text(support, "phone"), zalo = text(support, "zaloUrl");
        if (phone != null)
            text.append(copy("Support: ", "Hỗ trợ bạn: ")).append(phone).append('\n');
        if (zalo != null) text.append("Zalo: ").append(zalo).append('\n');
        String body = text.toString();
        String html = escape(body);
        if (viewUrl != null) {
            String safeUrl = escape(viewUrl);
            html = html.replace(safeUrl, "<a href=\"" + safeUrl + "\">" + safeUrl + "</a>");
        }
        return new Rendered(
                title + " — " + number,
                body,
                "<!doctype html><html><body><pre style=\"white-space:pre-wrap\">" + html + "</pre></body></html>",
                expiredId,
                expiredVersion);
    }

    private String title(String kind) {
        if (vietnamese)
            return switch (kind) {
                case "order_placed" -> "Cane Shop đã nhận đơn của bạn";
                case "payment_received" -> "Đã ghi nhận thanh toán";
                case "shipped" -> "Đơn của bạn đang được giao";
                case "ready_pickup" -> "Đơn đã sẵn sàng để bạn đến lấy";
                case "completed" -> "Đơn đã hoàn tất";
                case "cancelled" -> "Đơn đã hủy";
                case "delivery_failed_closed" -> "Đã đóng đơn giao không thành công";
                case "refunded" -> "Đã ghi nhận hoàn tiền";
                case "link_resent" -> "Link xem đơn của bạn";
                case "owner_new_order" -> "Cane Shop có đơn mới";
                case "owner_payment_due" -> "Thanh toán cần xử lý";
                case "owner_alert" -> "Cane Shop cần xử lý";
                default -> throw new IllegalStateException("Unsupported notification kind");
            };
        return switch (kind) {
            case "order_placed" -> "Order received";
            case "payment_received" -> "Payment recorded";
            case "shipped" -> "Order shipped";
            case "ready_pickup" -> "Ready for pickup";
            case "completed" -> "Order completed";
            case "cancelled" -> "Order cancelled";
            case "delivery_failed_closed" -> "Delivery closed";
            case "refunded" -> "Refund recorded";
            case "link_resent" -> "Your order viewing link";
            case "owner_new_order" -> "New order";
            case "owner_payment_due" -> "Payment needs attention";
            case "owner_alert" -> "Shop action required";
            default -> throw new IllegalStateException("Unsupported notification kind");
        };
    }

    private String copy(String en, String vi) {
        return vietnamese ? vi : en;
    }

    private String state(String value) {
        if (!vietnamese) return value;
        return switch (value) {
            case "unpaid" -> "Chưa thanh toán";
            case "paid" -> "Đã thanh toán";
            case "refund_pending" -> "Chờ hoàn tiền";
            case "refunded" -> "Đã hoàn tiền";
            case "awaiting_payment" -> "Chờ thanh toán";
            case "confirmed" -> "Đã xác nhận";
            case "preparing" -> "Đang chuẩn bị";
            case "shipping" -> "Đang giao";
            case "ready_for_pickup" -> "Sẵn sàng lấy";
            case "completed" -> "Hoàn tất";
            case "cancelled" -> "Đã hủy";
            case "delivery" -> "Giao hàng";
            case "pickup" -> "Lấy tại cửa hàng";
            case "bank" -> "Chuyển khoản";
            case "cash" -> "Tiền mặt";
            case "before_handover" -> "Hủy trước bàn giao";
            case "delivery_failed" -> "Giao không thành công";
            default -> value;
        };
    }

    static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String text(JsonNode node, String key) {
        return node == null ? null : CheckoutSettings.text(node, key);
    }

    public record Rendered(String subject, String text, String html, UUID expiredOrderId, long expiredVersion) {
        @Override
        public String toString() {
            return "RenderedNotification[redacted]";
        }
    }
}
