package vn.cane.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.cane.catalog.domain.PublicPrice;

class PublicPricesServiceTest {
    private static final Instant START = Instant.parse("2026-11-15T01:00:00Z");
    private static final List<PublicPrice> ITEMS = List.of(new PublicPrice("cane-tea", 33001, "low", "city"));

    @ParameterizedTest
    @ValueSource(ints = {14999, 15000, 15001})
    void fixedDeadlineAndServerTimeAtResponse(int elapsedMillis) {
        var clock = new TestClock();
        var cache = new MemoryCache();
        var reads = new AtomicInteger();
        var service = new PublicPricesService(
                () -> {
                    reads.incrementAndGet();
                    return ITEMS;
                },
                cache,
                clock);
        service.read();
        assertThat(cache.entry.expiresAt()).isEqualTo(START.plusSeconds(15));
        clock.now = START.plusMillis(elapsedMillis);
        var response = service.read();
        assertThat(reads.get()).isEqualTo(elapsedMillis < 15000 ? 1 : 2);
        assertThat(response.serverTime()).isEqualTo(clock.now);
        assertThat(response.items()).isEqualTo(ITEMS);
        assertThat(cache.writes).isEqualTo(elapsedMillis < 15000 ? 1 : 2);
    }

    @Test
    void slowSqlNeverGetsAFreshDeadlineOrExtendsCache() {
        var clock = new TestClock();
        var cache = new MemoryCache();
        var response = new PublicPricesService(
                        () -> {
                            clock.now = START.plusSeconds(16);
                            return ITEMS;
                        },
                        cache,
                        clock)
                .read();
        assertThat(cache.writes).isZero();
        assertThat(response.serverTime()).isEqualTo(clock.now);
    }

    @Test
    void sqlFallbackSurvivesBothCacheFailuresButNeverMasksSqlFailure() {
        var cache = new MemoryCache();
        cache.fail = true;
        assertThat(new PublicPricesService(() -> ITEMS, cache, new TestClock())
                        .read()
                        .items())
                .isEqualTo(ITEMS);
        var service = new PublicPricesService(
                () -> {
                    throw new IllegalStateException("SQL unavailable");
                },
                cache,
                new TestClock());
        assertThatThrownBy(service::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SQL unavailable");
    }

    @Test
    void futureDeadlineCannotMakeACorruptEntryLiveIndefinitely() {
        var cache = new MemoryCache();
        cache.entry = new PricesCache.Entry(START.plusSeconds(60), List.of());
        assertThat(new PublicPricesService(() -> ITEMS, cache, new TestClock())
                        .read()
                        .items())
                .isEqualTo(ITEMS);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 9007199254740992L, Long.MAX_VALUE})
    void rejectsInvalidPublicMoney(long amount) {
        assertThatThrownBy(() -> new PublicPrice("cane-tea", amount, "out", "city"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static final class TestClock extends Clock {
        volatile Instant now = START;

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    static final class MemoryCache implements PricesCache {
        Entry entry;
        int writes;
        boolean fail;

        @Override
        public Entry get() {
            if (fail) throw new IllegalStateException();
            return entry;
        }

        @Override
        public void put(Entry value) {
            if (fail) throw new IllegalStateException();
            entry = value;
            writes++;
        }

        @Override
        public void evict() {
            entry = null;
        }
    }
}
