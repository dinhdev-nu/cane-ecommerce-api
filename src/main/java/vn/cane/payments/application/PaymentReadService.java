package vn.cane.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import vn.cane.admin.application.StaffAuthorization;
import vn.cane.admin.application.StaffPrincipal;
import vn.cane.payments.domain.PaymentReceipt;
import vn.cane.support.transaction.Transactions;

@Service
@Profile("api & !worker")
public final class PaymentReadService {
    private final ReceiptRepository receipts;
    private final StaffAuthorization authorization;
    private final Transactions transactions;
    private final Clock clock;

    public PaymentReadService(
            ReceiptRepository receipts, StaffAuthorization authorization, Transactions transactions, Clock clock) {
        this.receipts = receipts;
        this.authorization = authorization;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Page list(StaffPrincipal principal, PaymentQuery query) {
        authorization.requireOwner(principal);
        return transactions.readSnapshot(() -> new Page(
                receipts.page(query).stream().map(Payment::from).toList(),
                query.page(),
                query.pageSize(),
                receipts.count(query),
                clock.instant()));
    }

    public record Page(List<Payment> items, int page, int pageSize, long total, Instant serverTime) {}

    public record BankReceipt(String bankCode, String accountNo, String transferContent, Instant transactionAt) {
        @Override
        public String toString() {
            return "BankReceipt[redacted]";
        }
    }

    public record Payment(
            UUID id,
            UUID orderId,
            String method,
            long amountVnd,
            String status,
            boolean appliedToOrder,
            boolean orderLinkVerified,
            String reference,
            BankReceipt bankReceipt,
            boolean needsReview,
            String note,
            String confirmedBy,
            Instant confirmedAt,
            String reviewedBy,
            Instant reviewedAt,
            Instant refundedAt,
            String refundReference) {
        static Payment from(PaymentReceipt r) {
            var b = r.bank();
            return new Payment(
                    r.id(),
                    r.orderId(),
                    r.method(),
                    r.amountVnd(),
                    r.status(),
                    r.appliedToOrder(),
                    r.orderLinkVerified(),
                    b == null ? null : b.reference(),
                    b == null
                            ? null
                            : new BankReceipt(b.bankCode(), b.accountNo(), b.transferContent(), b.transactionAt()),
                    r.needsReview(),
                    r.note(),
                    r.confirmedBy(),
                    r.confirmedAt(),
                    r.reviewedBy(),
                    r.reviewedAt(),
                    r.refundedAt(),
                    r.refundReference());
        }

        @Override
        public String toString() {
            return "Payment[redacted]";
        }
    }
}
