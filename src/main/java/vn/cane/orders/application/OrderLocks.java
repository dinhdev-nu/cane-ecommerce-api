package vn.cane.orders.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Lock the complete known order set before receipts/products; return existing IDs in DB order. */
public interface OrderLocks {
    List<UUID> lockIds(Collection<UUID> orderIds);
}
