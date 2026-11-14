package vn.cane.fixture;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A shared controllable instant, including views with a different zone. */
public final class MutableClock extends Clock {
    private final AtomicReference<Instant> time;
    private final ZoneId zone;

    public MutableClock(Instant initial) {
        this(new AtomicReference<>(initial), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> time, ZoneId zone) {
        this.time = time;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        time.updateAndGet(value -> value.plus(duration));
    }

    public void set(Instant value) {
        time.set(value);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
        return new MutableClock(time, requestedZone);
    }

    @Override
    public Instant instant() {
        return time.get();
    }
}
