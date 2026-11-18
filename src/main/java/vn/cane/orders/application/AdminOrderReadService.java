package vn.cane.orders.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import vn.cane.admin.application.StaffAuthorization;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.admin.domain.StaffRole;
import vn.cane.orders.domain.OrderActionRejected;
import vn.cane.payments.application.PaymentReadService;
import vn.cane.payments.application.ReceiptService;
import vn.cane.support.transaction.Transactions;

@Service
@Profile("api & !worker")
public final class AdminOrderReadService {
    private static final Set<String> BEFORE_HANDOVER =
            Set.of("awaiting_payment", "confirmed", "preparing", "ready_for_pickup");
    private final AdminOrderReader orders;
    private final ReceiptService receipts;
    private final PaymentReadService payments;
    private final StaffAuthorization authorization;
    private final Transactions tx;

    public AdminOrderReadService(
            AdminOrderReader orders,
            ReceiptService receipts,
            PaymentReadService payments,
            StaffAuthorization authorization,
            Transactions tx) {
        this.orders = orders;
        this.receipts = receipts;
        this.payments = payments;
        this.authorization = authorization;
        this.tx = tx;
    }

    public AdminOrderView.Page list(StaffPrincipal principal, AdminOrderQuery query) {
        return tx.readSnapshot(() -> {
            var actor = authorization.requireCurrent(principal);
            var at = orders.serverTime();
            return new AdminOrderView.Page(
                    orders.page(query),
                    query.page(),
                    query.pageSize(),
                    orders.count(query),
                    at,
                    actor.role() == StaffRole.OWNER ? orders.failures() : null);
        });
    }

    public AdminOrderView.Detail detail(StaffPrincipal principal, UUID id) {
        return tx.readSnapshot(() -> {
            var actor = authorization.requireCurrent(principal);
            var order = orders.find(id).orElseThrow(() -> new OrderActionRejected(true));
            boolean owner = actor.role() == StaffRole.OWNER;
            var principalRows = receipts.principals(List.of(id));
            if (principalRows.size() > 1) throw new IllegalStateException("Order has multiple principal receipts");
            var receipt = principalRows.isEmpty() ? null : principalRows.getFirst();
            var summary = receipt == null
                    ? null
                    : new AdminOrderView.Principal(
                            receipt.id(),
                            receipt.method(),
                            receipt.amountVnd(),
                            receipt.status(),
                            receipt.confirmedAt(),
                            receipt.refundedAt());
            var permissions = new AdminOrderView.Permissions(
                    actor.canConfirmBank()
                            && order.paymentMethod().equals("bank_transfer")
                            && order.status().equals("awaiting_payment")
                            && order.paymentStatus().equals("unpaid"),
                    BEFORE_HANDOVER.contains(order.status())
                            && (order.paymentStatus().equals("unpaid")
                                    || owner && order.paymentStatus().equals("paid")),
                    owner
                            && order.fulfillmentMethod().equals("delivery")
                            && order.status().equals("shipping")
                            && order.deliveryIssue(),
                    owner
                            && order.status().equals("cancelled")
                            && order.paymentStatus().equals("refund_pending")
                            && receipt != null
                            && receipt.status().equals("succeeded"),
                    owner);
            return new AdminOrderView.Detail(
                    order,
                    orders.items(id),
                    orders.timeline(id),
                    summary,
                    owner ? payments.extras(principal, id) : null,
                    permissions);
        });
    }
}
