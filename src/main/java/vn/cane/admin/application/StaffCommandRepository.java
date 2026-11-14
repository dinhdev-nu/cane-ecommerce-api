package vn.cane.admin.application;

import java.util.Optional;
import java.util.UUID;
import vn.cane.admin.domain.StaffRole;

public interface StaffCommandRepository {
    Optional<Staff> findById(UUID staffId);

    Optional<Staff> lockByUsername(String username);

    Staff insert(String username, String passwordHash, StaffRole role);

    long countActiveOwners();

    void changePassword(UUID staffId, String passwordHash);

    void changeAccess(UUID staffId, StaffRole role, boolean active);

    void revokeSessions(UUID staffId);

    record Staff(UUID id, StaffRole role, boolean active, int authVersion) {}
}
