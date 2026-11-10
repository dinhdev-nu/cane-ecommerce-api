package vn.cane.admin.application;

import java.util.List;

/** Own transaction: lock all 16 keys in ascending order; never combine with order/stock locks. */
public interface SettingsLocks {
    List<String> lockAll();
}
