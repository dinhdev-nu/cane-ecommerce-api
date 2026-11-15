package vn.cane.catalog.application;

import java.util.List;
import vn.cane.catalog.domain.PublicPrice;

public interface PricesReader {
    List<PublicPrice> read();
}
