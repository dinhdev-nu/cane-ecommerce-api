package vn.cane.support.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionsTest {
    @Test
    void boundsDeadlockAttemptsAndOnlyPausesAfterRollback() {
        var manager = manager();
        var attempts = new AtomicInteger();
        var pauses = new AtomicInteger();
        var transactions = new Transactions(manager, () -> {
            assertThat(manager.rollbacks).isEqualTo(attempts.get());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            pauses.incrementAndGet();
        });
        assertThatThrownBy(() -> transactions.write(() -> {
                    attempts.incrementAndGet();
                    throw sqlFailure("40P01");
                }))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(pauses.get()).isEqualTo(2);
        assertThat(manager.rollbacks).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"23505", "40001", "08006"})
    void doesNotRetryUniqueSerializationOrUnknownCommitConnectionFailures(String state) {
        var attempts = new AtomicInteger();
        var transactions = new Transactions(manager(), () -> {
            throw new AssertionError("Unexpected retry");
        });
        assertThatThrownBy(() -> transactions.write(() -> {
                    attempts.incrementAndGet();
                    throw sqlFailure(state);
                }))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void doesNotRetryBusinessFailures() {
        var transactions = new Transactions(manager(), () -> {
            throw new AssertionError("Unexpected retry");
        });
        var failure = new IllegalArgumentException("business guard fixture");
        assertThatThrownBy(() -> transactions.write(() -> {
                    throw failure;
                }))
                .isSameAs(failure);
    }

    @Test
    void doesNotRetryAnUnknownCommitOutcomeEvenWithADeadlockLikeCause() {
        var manager = manager();
        manager.commitFailure = new TransactionSystemException("Unknown commit fixture", sqlFailure("40P01"));
        var calls = new AtomicInteger();
        var transactions = new Transactions(manager, () -> {
            throw new AssertionError("Unknown commit must not retry");
        });
        assertThatThrownBy(() -> transactions.write(calls::incrementAndGet)).isSameAs(manager.commitFailure);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void advisoryHashSeparatesNamespaceActorAndUnambiguousKeyParts() {
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000001");
        long expected = AdvisoryLocks.lockId("admin-operation", actor, "fixture-key-0001");
        assertThat(AdvisoryLocks.lockId("admin-operation", actor, "fixture-key-0001"))
                .isEqualTo(expected);
        assertThat(AdvisoryLocks.lockId("admin-operation", null, "fixture-key-0001"))
                .isNotEqualTo(expected);
        assertThat(AdvisoryLocks.lockId("admin-operation", UUID.randomUUID(), "fixture-key-0001"))
                .isNotEqualTo(expected);
        assertThat(AdvisoryLocks.lockId("place-order", actor, "fixture-key-0001"))
                .isNotEqualTo(expected);
        assertThat(AdvisoryLocks.lockId("ab", null, "c")).isNotEqualTo(AdvisoryLocks.lockId("a", null, "bc"));
        assertThat(AdvisoryLocks.lockId("admin-operation", null, "fixture-key-0001"))
                .isNotEqualTo(AdvisoryLocks.lockId("admin-operation", new UUID(0, 0), "fixture-key-0001"));
    }

    private static CountingManager manager() {
        return new CountingManager();
    }

    /** Spring drives synchronization/completion; the unit fixture has no database resources. */
    private static final class CountingManager extends AbstractPlatformTransactionManager {
        private int rollbacks;
        private RuntimeException commitFailure;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (commitFailure != null) throw commitFailure;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }

    private static RuntimeException sqlFailure(String state) {
        return new DataAccessResourceFailureException("SQL failure fixture", new SQLException("fixture", state));
    }
}
