package vn.cane.admin.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.cane.admin.domain.StaffRole;

public interface StaffLoginRepository {
    Optional<Credentials> findByUsername(String username);

    Optional<Credentials> lock(UUID staffId);

    Instant currentTime();

    void recordAttempt(UUID staffId, int failedCount, Instant lockedUntil);

    boolean operatorCanConfirmBank();

    record Credentials(
            UUID id,
            String username,
            String passwordHash,
            StaffRole role,
            boolean active,
            int authVersion,
            int failedCount,
            Instant lockedUntil) {
        @Override
        public String toString() {
            return "StaffCredentials[redacted]";
        }
    }
}
