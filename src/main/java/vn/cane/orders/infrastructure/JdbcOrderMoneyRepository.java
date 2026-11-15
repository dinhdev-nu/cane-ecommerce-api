package vn.cane.orders.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.orders.application.OrderMoneyRepository;
import vn.cane.payments.domain.MoneyOrder;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcOrderMoneyRepository implements OrderMoneyRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrderMoneyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MoneyOrder> locked(Collection<UUID> ids) {
        TransactionAccess.requireWrite();
        if (ids.isEmpty()) return List.of();
        return jdbc.query(
                """
                SELECT id,order_number,status,payment_status,payment_method,fulfillment_method,total_vnd,
                    bank_snapshot->>'bankCode' AS bank_code,bank_snapshot->>'accountNo' AS account_no
                FROM cane.orders WHERE id IN (:ids) ORDER BY id FOR UPDATE
                """,
                Map.of("ids", List.copyOf(ids)),
                (r, n) -> new MoneyOrder(
                        r.getObject("id", UUID.class),
                        r.getString("order_number"),
                        r.getString("status"),
                        r.getString("payment_status"),
                        r.getString("payment_method"),
                        r.getString("fulfillment_method"),
                        r.getLong("total_vnd"),
                        normalize(r.getString("bank_code"), false),
                        normalize(r.getString("account_no"), true)));
    }

    @Override
    public void apply(MoneyOrder order) {
        TransactionAccess.requireWrite();
        int changed = jdbc.update("""
                UPDATE cane.orders SET payment_status=CASE WHEN status='cancelled' THEN 'refund_pending' ELSE 'paid' END,
                    status=CASE WHEN status='awaiting_payment' THEN 'confirmed' ELSE status END,payment_due_at=NULL
                WHERE id=:id AND payment_status='unpaid'
                """, Map.of("id", order.id()));
        if (changed != 1) throw new IllegalStateException("Locked order changed unexpectedly");
    }

    @Override
    public void refunded(UUID orderId) {
        TransactionAccess.requireWrite();
        if (jdbc.update(
                        "UPDATE cane.orders SET payment_status='refunded' WHERE id=:id AND status='cancelled' AND payment_status='refund_pending'",
                        Map.of("id", orderId))
                != 1) throw new IllegalStateException("Locked refund order changed unexpectedly");
    }

    @Override
    public String customerEmail(UUID orderId) {
        return jdbc.queryForObject(
                "SELECT recipient_email FROM cane.orders WHERE id=:id", Map.of("id", orderId), String.class);
    }

    private static String normalize(String value, boolean account) {
        return value == null ? null : account ? value.replaceAll("\\s", "") : value.strip();
    }
}
