package vn.cane.payments.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.cane.payments.application.PaymentLocks;
import vn.cane.support.transaction.TransactionAccess;

@Repository
public class JdbcPaymentLocks implements PaymentLocks {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPaymentLocks(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> lockIds(Collection<UUID> paymentIds) {
        TransactionAccess.requireWrite();
        var ids = List.copyOf(paymentIds);
        if (ids.isEmpty()) return List.of();
        return List.copyOf(jdbc.query(
                "SELECT id FROM cane.payments WHERE id IN (:ids) ORDER BY id FOR UPDATE",
                Map.of("ids", ids),
                (row, number) -> row.getObject("id", UUID.class)));
    }
}
