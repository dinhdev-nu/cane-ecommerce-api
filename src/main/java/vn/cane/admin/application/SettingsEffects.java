package vn.cane.admin.application;

/** Bootstrap connects this effect to Catalog's existing after-commit price invalidation. */
@FunctionalInterface
public interface SettingsEffects {
    void afterLowStockChange();
}
