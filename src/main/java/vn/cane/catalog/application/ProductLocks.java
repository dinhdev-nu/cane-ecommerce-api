package vn.cane.catalog.application;

import java.util.Collection;
import java.util.List;

/** Acquire after orders and receipts; missing SKUs remain the caller's validation responsibility. */
public interface ProductLocks {
    List<String> lockSkus(Collection<String> skus);
}
