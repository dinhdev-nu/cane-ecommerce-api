package vn.cane.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.cane.catalog.domain.PublicPrice;

@Service
public final class PublicPricesService {
    public static final Duration MAX_AGE = Duration.ofSeconds(15);
    private static final Logger LOG = LoggerFactory.getLogger(PublicPricesService.class);
    private final PricesReader database;
    private final PricesCache cache;
    private final Clock clock;

    public PublicPricesService(PricesReader database, PricesCache cache, Clock clock) {
        this.database = database;
        this.cache = cache;
        this.clock = clock;
    }

    public Prices read() {
        try {
            var entry = cache.get();
            Instant now = clock.instant();
            if (entry != null
                    && now.isBefore(entry.expiresAt())
                    && !entry.expiresAt().isAfter(now.plus(MAX_AGE))) {
                signal("hit");
                return new Prices(now, entry.items());
            }
            signal("miss");
        } catch (RuntimeException failure) {
            signal("read_fallback");
        }
        Instant startedAt = clock.instant();
        var items = List.copyOf(database.read());
        var entry = new PricesCache.Entry(startedAt.plus(MAX_AGE), items);
        if (clock.instant().isBefore(entry.expiresAt())) {
            try {
                cache.put(entry);
            } catch (RuntimeException failure) {
                signal("write_failed");
            }
        }
        return new Prices(clock.instant(), items);
    }

    private static void signal(String outcome) {
        LOG.atDebug()
                .addKeyValue("component", "prices_cache")
                .addKeyValue("outcome", outcome)
                .log("Prices cache");
    }

    public record Prices(Instant serverTime, List<PublicPrice> items) {}
}
