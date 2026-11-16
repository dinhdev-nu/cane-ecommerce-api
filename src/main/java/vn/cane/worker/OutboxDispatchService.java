package vn.cane.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import vn.cane.orders.domain.OrderLinkState;
import vn.cane.support.transaction.Transactions;

/** Every database boundary is short. The dispatcher calls providers only after these methods return. */
@Service
@Profile("worker & !api")
public final class OutboxDispatchService {
    private final Transactions tx;
    private final JdbcOutboxQueue queue;
    private final NotificationAlerts alerts;
    private final Clock clock;

    public OutboxDispatchService(Transactions tx, JdbcOutboxQueue queue, NotificationAlerts alerts, Clock clock) {
        this.tx = tx;
        this.queue = queue;
        this.alerts = alerts;
        this.clock = clock;
    }

    public List<OutboxLease> claim() {
        return tx.write(() -> {
            var now = OrderLinkState.millisecondInstant(clock.instant());
            var claimed = new ArrayList<OutboxLease>();
            for (var row : queue.lockDue(now)) {
                if (row.attempts() == 8) {
                    if (queue.finish(row, "exhausted", "LEASE_EXPIRED", now, now)) alerts.exhausted(row);
                } else claimed.add(queue.claim(row, now));
            }
            return List.copyOf(claimed);
        });
    }

    public boolean hasTime(OutboxLease lease) {
        return clock.instant().plusSeconds(31).isBefore(lease.lockedAt().plusSeconds(300));
    }

    public void sent(OutboxLease lease) {
        finish(lease, "sent", null);
    }

    public void skipped(OutboxLease lease) {
        finish(lease, "skipped", null);
    }

    public void failed(OutboxLease lease, String code) {
        if (!java.util.Set.of("SOURCE_INVALID", "PROVIDER_TIMEOUT", "PROVIDER_FAILED", "LEASE_EXPIRED")
                .contains(code)) throw new IllegalArgumentException("Unknown dispatch error code");
        finish(lease, lease.attempts() == 8 ? "exhausted" : "pending", code);
    }

    private void finish(OutboxLease lease, String status, String code) {
        tx.write(() -> {
            var now = clock.instant();
            Instant next = status.equals("pending")
                    ? now.plus(backoff(
                            lease.attempts(), ThreadLocalRandom.current().nextDouble()))
                    : now;
            if (queue.finish(lease, status, code, next, now) && status.equals("exhausted")) alerts.exhausted(lease);
            return null;
        });
    }

    static Duration backoff(int attempt, double jitter) {
        if (attempt < 1 || attempt > 8 || jitter < 0 || jitter >= 1)
            throw new IllegalArgumentException("Invalid retry state");
        long base = Math.min(30L << (attempt - 1), 3600);
        return Duration.ofMillis(base * 1000 + (long) (base * 200 * jitter));
    }
}
