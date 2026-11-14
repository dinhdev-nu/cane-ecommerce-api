package vn.cane.admin.domain;

import java.time.Duration;
import java.time.Instant;

/** Fixed login security policy, independent of the IP limiter and existing-session authorization. */
public final class LoginLock {
    private static final int FAILURE_LIMIT = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private LoginLock() {}

    public static Decision evaluate(int failedCount, Instant lockedUntil, Instant now, boolean passwordMatches) {
        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            return new Decision(false, failedCount, lockedUntil);
        }
        if (passwordMatches) return new Decision(true, 0, null);
        int previous = lockedUntil == null ? failedCount : 0;
        int next = Math.min(previous, FAILURE_LIMIT - 1) + 1;
        return new Decision(false, next, next == FAILURE_LIMIT ? now.plus(LOCK_DURATION) : null);
    }

    public record Decision(boolean allowed, int failedCount, Instant lockedUntil) {}
}
