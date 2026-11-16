package vn.cane.worker;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.fixture.LinkTestKeys;
import vn.cane.orders.application.*;
import vn.cane.orders.infrastructure.security.*;

class NotificationRendererTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private final OrderLinkTokens tokens = new HmacOrderLinkTokens(LinkKeyRing.parse(LinkTestKeys.JSON, "fixture"));
    private final NotificationRenderer renderer =
            new NotificationRenderer(tokens, Clock.fixed(NOW, ZoneOffset.UTC), "https://cane.example.test", "vi");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "order_placed",
                "payment_received",
                "shipped",
                "ready_pickup",
                "completed",
                "cancelled",
                "delivery_failed_closed",
                "refunded",
                "link_resent",
                "owner_new_order",
                "owner_payment_due",
                "owner_alert"
            })
    void supportsEveryDispatchKindWithoutExposingNotesOrInternalBankEvidence(String kind) {
        var lease = lease(kind);
        var rendered = renderer.render(lease, source(lease, false));
        assertThat(rendered.text())
                .contains("Cane Shop", "Hỗ trợ bạn: 0338825982")
                .doesNotContain("SECRET NOTE", "goodsResolved", "bankReceipt");
        assertThat(rendered.html()).doesNotContain("<script>");
        if (kind.startsWith("owner_")) assertThat(rendered.text()).doesNotContain("#t=");
        else {
            assertThat(rendered.text()).contains("/orders/view#t=");
            assertThat(rendered.html()).contains("<a href=\"https://cane.example.test/orders/view#t=");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"vi", "en"})
    void eventTimeAndReceiptAmountsStayHistoricalWhileLinkUsesCurrentVersionAndExpiry(String language) {
        var lease = lease("payment_received");
        var source = source(lease, false);
        var localized = new NotificationRenderer(
                tokens, Clock.fixed(NOW, ZoneOffset.UTC), "https://cane.example.test", language);
        var message = localized.render(lease, source);
        assertThat(message.text()).contains("12345 VND");
        if (language.equals("vi"))
            assertThat(message.text())
                    .contains(
                            "Khoản thu bổ sung",
                            "trước đó",
                            "Chưa thanh toán",
                            "Khoản thu gốc được ghi nhận lúc: " + NOW.minusSeconds(90),
                            "Liên kết được xác minh lúc: " + NOW.minusSeconds(50));
        else
            assertThat(message.text())
                    .contains(
                            "Additional receipt",
                            "not a new payment",
                            "Payment at this event: unpaid",
                            "Original receipt recorded at: " + NOW.minusSeconds(90),
                            "Link verified at: " + NOW.minusSeconds(50));
        assertThat(message.text()).doesNotContain("Đã thanh toán");
        String token = message.text().split("#t=", 2)[1].split("\n", 2)[0];
        assertThat(tokens.verifySignature(token).version()).isEqualTo(7);
    }

    @Test
    void expiredLinkStillSendsInformationAndSupportWithoutRenewalOrLink() {
        var lease = lease("link_resent");
        var message = renderer.render(lease, source(lease, true));
        assertThat(message.text()).doesNotContain("#t=").contains("đã hết hạn", "Hỗ trợ bạn");
        assertThat(message.html()).doesNotContain("<a ");
        assertThat(message.expiredOrderId()).isEqualTo(lease.orderId());
        assertThat(message.expiredVersion()).isEqualTo(7);
    }

    private OutboxLease lease(String kind) {
        return new OutboxLease(
                UUID.randomUUID(),
                kind,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "email",
                "fixture@example.invalid",
                1,
                NOW);
    }

    private NotificationSourceLoader.Source source(OutboxLease lease, boolean expired) {
        var facts = JSON.createObjectNode()
                .put("status", "confirmed")
                .put("paymentStatus", "unpaid")
                .put("amountVnd", 12345)
                .put("method", "bank")
                .put("isPrincipal", false)
                .put("orderLinkVerified", true)
                .put("action", "close_review")
                .put("cancelKind", "before_handover")
                .put("refundedAt", NOW.toString())
                .put("note", "SECRET NOTE")
                .put("goodsResolved", "SECRET NOTE");
        var row = new GuestOrderRepository.Snapshot(
                lease.orderId(),
                "CANEABC123",
                "completed",
                "paid",
                "pickup",
                "MOCK",
                "0338825982",
                "guest@example.invalid",
                null,
                JSON.createObjectNode().put("address", "MOCK shop"),
                10000,
                0,
                10000,
                null,
                null,
                null,
                false,
                null,
                null,
                7,
                expired ? NOW.minusSeconds(1) : NOW.plusSeconds(86400),
                NOW.minusSeconds(100));
        return new NotificationSourceLoader.Source(
                new JdbcOutboxQueue.Event(
                        lease.orderId(), lease.paymentId(), "payment_resolved", null, facts, NOW.minusSeconds(50)),
                new NotificationOrderReader.Data(
                        row, List.of(new GuestOrderView.Item("MOCK <script>", "1 box", 1, 10000, 10000))),
                NOW.minusSeconds(90),
                Map.of(
                        "support",
                        JSON.createObjectNode()
                                .put("phone", "0338825982")
                                .put("zaloUrl", "https://zalo.me/0338825982")),
                false);
    }
}
