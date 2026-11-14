package vn.cane.admin.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.cane.admin.domain.StaffRole;

public interface StaffSessionRepository {
    Optional<Snapshot> find(byte[] tokenHash);

    Lifetime insert(byte[] tokenHash, UUID staffId, int authVersion);

    void delete(byte[] tokenHash);

    record Lifetime(Instant createdAt, Instant expiresAt) {}

    record Snapshot(
            UUID staffId,
            String username,
            StaffRole role,
            boolean active,
            int staffVersion,
            int sessionVersion,
            boolean operatorCanConfirmBank,
            Instant expiresAt,
            Instant checkedAt) {}
}
