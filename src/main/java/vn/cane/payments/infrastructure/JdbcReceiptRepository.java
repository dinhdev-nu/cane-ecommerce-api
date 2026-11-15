package vn.cane.payments.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.payments.application.MoneyCommands;
import vn.cane.payments.application.PaymentQuery;
import vn.cane.payments.application.ReceiptRepository;
import vn.cane.payments.domain.BankEvidence;
import vn.cane.payments.domain.PaymentReceipt;
import vn.cane.support.transaction.TransactionAccess;
import vn.cane.support.validation.BankReceiptKey;

@Repository
public class JdbcReceiptRepository implements ReceiptRepository {
    private static final String FILTER =
            " WHERE (CAST(:review AS boolean) IS NULL OR needs_review=:review) AND (CAST(:orderId AS uuid) IS NULL OR order_id=:orderId)";
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcReceiptRepository(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<PaymentReceipt> find(UUID id) {
        return jdbc.query("SELECT * FROM cane.payments WHERE id=:id", Map.of("id", id), this::map).stream()
                .findFirst();
    }

    @Override
    public Optional<PaymentReceipt> findBank(String key) {
        return jdbc.query("SELECT * FROM cane.payments WHERE provider_ref=:key", Map.of("key", key), this::map).stream()
                .findFirst();
    }

    @Override
    public List<PaymentReceipt> principals(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) return List.of();
        return jdbc.query(
                "SELECT * FROM cane.payments WHERE order_id IN (:ids) AND applied_to_order ORDER BY id",
                Map.of("ids", List.copyOf(orderIds)),
                this::map);
    }

    @Override
    public PaymentReceipt insert(
            UUID orderId, MoneyCommands.Entry entry, UUID actor, boolean verified, boolean review) {
        TransactionAccess.requireWrite();
        var bank = entry.bank();
        String evidence = bank == null
                ? null
                : json.writeValueAsString(json.createObjectNode()
                        .put("bankCode", bank.bankCode())
                        .put("accountNo", bank.accountNo())
                        .put("transferContent", bank.transferContent())
                        .put("transactionAt", bank.transactionAt().toString()));
        var p = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("orderId", orderId, Types.OTHER)
                .addValue("method", entry.method())
                .addValue("amount", entry.amountVnd())
                .addValue("actor", actor.toString())
                .addValue("ref", bank == null ? null : bank.key(), Types.VARCHAR)
                .addValue("bank", evidence, Types.VARCHAR)
                .addValue("verified", verified)
                .addValue("review", review)
                .addValue("note", entry.note(), Types.VARCHAR);
        return one("""
                INSERT INTO cane.payments(id,order_id,method,amount_vnd,provider_ref,bank_receipt,confirmed_by,order_link_verified,needs_review,note)
                VALUES (:id,:orderId,:method,:amount,:ref,CAST(:bank AS jsonb),:actor,:verified,:review,:note) RETURNING *
                """, p);
    }

    @Override
    public PaymentReceipt apply(UUID id, UUID orderId, UUID actor, boolean reviewed) {
        TransactionAccess.requireWrite();
        return one(
                """
                UPDATE cane.payments SET order_id=:orderId,applied_to_order=true,order_link_verified=true,needs_review=false,
                    reviewed_by=CASE WHEN :reviewed THEN :actor ELSE reviewed_by END,
                    reviewed_at=CASE WHEN :reviewed THEN statement_timestamp() ELSE reviewed_at END
                WHERE id=:id AND status='succeeded' AND NOT applied_to_order RETURNING *
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orderId", orderId)
                        .addValue("actor", actor.toString())
                        .addValue("reviewed", reviewed));
    }

    @Override
    public PaymentReceipt review(UUID id, UUID orderId, UUID actor) {
        TransactionAccess.requireWrite();
        return one(
                """
                UPDATE cane.payments SET order_id=:orderId,order_link_verified=(CAST(:orderId AS uuid) IS NOT NULL),needs_review=false,
                    reviewed_by=:actor,reviewed_at=statement_timestamp()
                WHERE id=:id AND status='succeeded' AND NOT applied_to_order RETURNING *
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orderId", orderId, Types.OTHER)
                        .addValue("actor", actor.toString()));
    }

    @Override
    public PaymentReceipt refund(UUID id, String reference) {
        TransactionAccess.requireWrite();
        return one("""
                UPDATE cane.payments SET status='refunded',refunded_at=statement_timestamp(),refund_reference=:reference,needs_review=false
                WHERE id=:id AND status='succeeded' RETURNING *
                """, new MapSqlParameterSource().addValue("id", id).addValue("reference", reference));
    }

    @Override
    public List<PaymentReceipt> page(PaymentQuery query) {
        return jdbc.query(
                "SELECT * FROM cane.payments" + FILTER
                        + " ORDER BY confirmed_at DESC,id DESC LIMIT :limit OFFSET :offset",
                parameters(query),
                this::map);
    }

    @Override
    public long count(PaymentQuery query) {
        return jdbc.queryForObject("SELECT count(*) FROM cane.payments" + FILTER, parameters(query), Long.class);
    }

    private MapSqlParameterSource parameters(PaymentQuery query) {
        return new MapSqlParameterSource()
                .addValue("review", query.needsReview(), Types.BOOLEAN)
                .addValue("orderId", query.orderId(), Types.OTHER)
                .addValue("limit", query.pageSize())
                .addValue("offset", query.offset());
    }

    private PaymentReceipt one(String sql, MapSqlParameterSource parameters) {
        var rows = jdbc.query(sql, parameters, this::map);
        if (rows.size() != 1) throw new IllegalStateException("Locked receipt changed unexpectedly");
        return rows.getFirst();
    }

    private PaymentReceipt map(ResultSet r, int number) throws SQLException {
        BankEvidence bank = null;
        if (r.getString("method").equals("bank")) {
            var b = json.readTree(r.getString("bank_receipt"));
            bank = new BankEvidence(
                    b.path("bankCode").asString(),
                    b.path("accountNo").asString(),
                    BankReceiptKey.reference(r.getString("provider_ref")),
                    b.path("transferContent").asString(),
                    Instant.parse(b.path("transactionAt").asString()));
            if (!bank.key().equals(r.getString("provider_ref")))
                throw new IllegalStateException("Receipt evidence is inconsistent");
        }
        return new PaymentReceipt(
                r.getObject("id", UUID.class),
                r.getObject("order_id", UUID.class),
                r.getString("method"),
                r.getLong("amount_vnd"),
                r.getString("status"),
                r.getBoolean("applied_to_order"),
                r.getBoolean("order_link_verified"),
                bank,
                r.getBoolean("needs_review"),
                r.getString("note"),
                r.getString("confirmed_by"),
                time(r, "confirmed_at"),
                r.getString("reviewed_by"),
                time(r, "reviewed_at"),
                time(r, "refunded_at"),
                r.getString("refund_reference"));
    }

    private static Instant time(ResultSet r, String field) throws SQLException {
        var value = r.getObject(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
