package vn.cane.support.transaction;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Application services own the outer boundary; nested writes join the same transaction. */
@Component
public final class Transactions {
    private static final Logger LOG = LoggerFactory.getLogger(Transactions.class);
    private static final int MAX_ATTEMPTS = 3;
    private final TransactionTemplate write;
    private final TransactionTemplate snapshot;
    private final Runnable retryPause;

    @Autowired
    public Transactions(PlatformTransactionManager manager) {
        this(manager, Transactions::pauseBeforeRetry);
    }

    Transactions(PlatformTransactionManager manager, Runnable retryPause) {
        this.retryPause = retryPause;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        snapshot = new TransactionTemplate(manager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
    }

    public <T> T write(Supplier<T> work) {
        Objects.requireNonNull(work);
        // The outer owner must roll back the entire operation before retrying. Never retry a
        // participating transaction or suspend the caller to commit part of an operation.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return write.execute(status -> work.get());
        }
        for (int attempt = 1; ; attempt++) {
            var outcome = new Attempt<T>();
            try {
                return write.execute(status -> {
                    TransactionSynchronizationManager.registerSynchronization(outcome);
                    outcome.result = work.get();
                    return outcome.result;
                });
            } catch (RuntimeException failure) {
                if (outcome.completion == TransactionSynchronization.STATUS_COMMITTED) {
                    warnPostCommit();
                    return outcome.result;
                }
                if (outcome.completion != TransactionSynchronization.STATUS_ROLLED_BACK
                        || !SqlFailures.isDeadlock(failure)
                        || attempt == MAX_ATTEMPTS) throw failure;
                retryPause.run();
            }
        }
    }

    public <T> T readSnapshot(Supplier<T> work) {
        return snapshot.execute(status -> work.get());
    }

    /** Non-database effects only. Audit/outbox writes belong inside the caller's transaction. */
    public void afterCommit(Runnable action) {
        Objects.requireNonNull(action);
        TransactionAccess.requireWrite();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    warnPostCommit();
                }
            }
        });
    }

    private static void warnPostCommit() {
        LOG.atWarn().addKeyValue("component", "transaction").log("Post-commit action failed");
    }

    private static final class Attempt<T> implements TransactionSynchronization {
        private int completion = STATUS_UNKNOWN;
        private T result;

        @Override
        public void afterCompletion(int status) {
            completion = status;
        }
    }

    private static void pauseBeforeRetry() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(10, 51));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Transaction retry interrupted");
        }
    }
}
