package vn.cane.catalog.domain;

public final class CatalogRejected extends RuntimeException {
    private final Integer currentStock;

    private CatalogRejected(Integer currentStock) {
        super("Catalog operation rejected");
        this.currentStock = currentStock;
    }

    public Integer currentStock() {
        return currentStock;
    }

    public static CatalogRejected missing() {
        return new CatalogRejected(null);
    }

    public static CatalogRejected conflict(int current) {
        return new CatalogRejected(current);
    }
}
