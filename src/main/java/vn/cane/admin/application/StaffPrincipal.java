package vn.cane.admin.application;

import vn.cane.admin.domain.StaffActor;

/** Created only after session validation; contains no raw session token. */
public final class StaffPrincipal {
    private final StaffActor actor;
    private final byte[] tokenHash;

    StaffPrincipal(StaffActor actor, byte[] tokenHash) {
        this.actor = actor;
        this.tokenHash = tokenHash.clone();
    }

    public StaffActor actor() {
        return actor;
    }

    byte[] tokenHash() {
        return tokenHash.clone();
    }

    @Override
    public String toString() {
        return "StaffPrincipal[redacted]";
    }
}
