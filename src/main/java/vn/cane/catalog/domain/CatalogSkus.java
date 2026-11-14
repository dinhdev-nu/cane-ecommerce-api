package vn.cane.catalog.domain;

import java.util.Set;

/** Stable identities approved by the project owner on 10 November 2026. */
public final class CatalogSkus {
    public static final String JUICE_CUP = "cane-juice-cup";
    public static final Set<String> ALL = Set.of(
            "cane-juice-bottle",
            JUICE_CUP,
            "cane-tea",
            "cane-candy",
            "cane-syrup",
            "cane-molasses",
            "raw-cane-sugar",
            "whole-cane-sugar",
            "cane-vinegar");

    private CatalogSkus() {}
}
