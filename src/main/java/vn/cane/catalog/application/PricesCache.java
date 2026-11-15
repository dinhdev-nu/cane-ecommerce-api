package vn.cane.catalog.application;

import java.time.Instant;
import java.util.List;
import vn.cane.catalog.domain.PublicPrice;

public interface PricesCache {
    Entry get();

    void put(Entry entry);

    void evict();

    record Entry(Instant expiresAt, List<PublicPrice> items) {
        public Entry {
            items = List.copyOf(items);
        }
    }
}
