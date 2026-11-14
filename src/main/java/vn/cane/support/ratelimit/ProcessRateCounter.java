package vn.cane.support.ratelimit;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** One API process only. Entries and count/expiry changes are atomic per key. */
public final class ProcessRateCounter implements RateCounter {
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final Clock clock;

    public ProcessRateCounter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Hit increment(String key) {
        Window current = windows.compute(key, (ignored, previous) -> {
            long now = clock.millis();
            if (previous == null || previous.expiresAtMillis() <= now) {
                return new Window(1, Math.addExact(now, WINDOW_MILLIS));
            }
            return new Window(
                    previous.count() == Long.MAX_VALUE ? Long.MAX_VALUE : previous.count() + 1,
                    previous.expiresAtMillis());
        });
        if ((calls.incrementAndGet() & 255) == 0) {
            long now = clock.millis();
            windows.forEach((entryKey, window) -> {
                if (window.expiresAtMillis() <= now) windows.remove(entryKey, window);
            });
        }
        return new Hit(current.count(), Math.clamp(current.expiresAtMillis() - clock.millis(), 0, WINDOW_MILLIS));
    }

    private record Window(long count, long expiresAtMillis) {}
}
