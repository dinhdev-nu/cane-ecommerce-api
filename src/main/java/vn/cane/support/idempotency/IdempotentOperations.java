package vn.cane.support.idempotency;

import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.SqlFailures;
import vn.cane.support.transaction.Transactions;

/** Application caller supplies current authorization and the entire write operation, including its anchor. */
@Component
public final class IdempotentOperations {
    private static final String NAMESPACE = "admin-post";
    private final Transactions transactions;
    private final AdvisoryLocks locks;
    private final OperationReceipts receipts;

    public IdempotentOperations(Transactions transactions, AdvisoryLocks locks, OperationReceipts receipts) {
        this.transactions = transactions;
        this.locks = locks;
        this.receipts = receipts;
    }

    /** Authorization must query current SQL permissions and return the authenticated staff UUID each time. */
    public OperationReceipt execute(Supplier<UUID> authorize, CanonicalAdminPost command, Consumer<Operation> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Admin operation must own its outer transaction");
        Objects.requireNonNull(work);
        UUID actor = Objects.requireNonNull(authorize.get());
        var old = receipts.find(actor, command.key());
        if (old.isPresent()) return response(old.get(), command, true);
        var completion = new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
        try {
            return transactions.write(() -> {
                completion.set(TransactionSynchronization.STATUS_UNKNOWN);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        completion.set(status);
                    }
                });
                sameActor(actor, authorize.get());
                locks.acquire(NAMESPACE, actor, command.key());
                var found = receipts.find(actor, command.key());
                if (found.isPresent()) return response(found.get(), command, true);
                work.accept(new Operation(actor, command));
                var recorded = receipts.find(actor, command.key())
                        .orElseThrow(
                                () -> new IllegalStateException("Successful admin operation must write its anchor"));
                return response(recorded, command, false);
            });
        } catch (RuntimeException failure) {
            if (completion.get() != TransactionSynchronization.STATUS_ROLLED_BACK
                    || !SqlFailures.uniqueConstraint(failure)
                            .filter("business_events_operation_unique"::equals)
                            .isPresent()) throw failure;
            // Do not read from an aborted/unknown transaction, or treat unrelated UNIQUE failures as replay.
            sameActor(actor, authorize.get());
            var winner = receipts.find(actor, command.key());
            if (winner.isEmpty()) throw failure;
            return response(winner.get(), command, true);
        }
    }

    private static void sameActor(UUID expected, UUID current) {
        if (!expected.equals(current)) throw new IllegalStateException("Authorized operation actor changed");
    }

    private static OperationReceipt response(
            OperationReceipts.Stored stored, CanonicalAdminPost command, boolean replayed) {
        if (!MessageDigest.isEqual(stored.requestHash(), command.hash())) throw new IdempotencyMismatchException();
        return new OperationReceipt(stored.operationId(), replayed, stored.result());
    }

    public static final class Operation {
        private final UUID actor;
        private final CanonicalAdminPost command;

        private Operation(UUID actor, CanonicalAdminPost command) {
            this.actor = actor;
            this.command = command;
        }

        public UUID actor() {
            return actor;
        }

        public OperationStamp stamp(ObjectNode result) {
            return new OperationStamp(command.key(), command.hash(), result);
        }

        @Override
        public String toString() {
            return "AdminOperation[redacted]";
        }
    }
}
