package vn.cane.admin.application;

import java.time.Clock;
import org.springframework.stereotype.Service;
import vn.cane.support.transaction.Transactions;

@Service
public final class SessionCleanupService {
    private final ExpiredSessions sessions;
    private final Transactions tx;
    private final Clock clock;

    public SessionCleanupService(ExpiredSessions sessions, Transactions tx, Clock clock) {
        this.sessions = sessions;
        this.tx = tx;
        this.clock = clock;
    }

    public int deleteExpired() {
        return tx.write(() -> sessions.deleteThrough(clock.instant()));
    }
}
