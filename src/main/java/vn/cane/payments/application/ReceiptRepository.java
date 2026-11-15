package vn.cane.payments.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.cane.payments.domain.PaymentReceipt;

public interface ReceiptRepository {
    Optional<PaymentReceipt> find(UUID id);

    Optional<PaymentReceipt> findBank(String key);

    List<PaymentReceipt> principals(Collection<UUID> orderIds);

    PaymentReceipt insert(UUID orderId, MoneyCommands.Entry entry, UUID actor, boolean verified, boolean review);

    PaymentReceipt apply(UUID id, UUID orderId, UUID actor, boolean reviewed);

    PaymentReceipt review(UUID id, UUID orderId, UUID actor);

    PaymentReceipt refund(UUID id, String reference);

    List<PaymentReceipt> page(PaymentQuery query);

    long count(PaymentQuery query);
}
