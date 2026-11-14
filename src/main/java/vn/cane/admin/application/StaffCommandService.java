package vn.cane.admin.application;

import java.util.Objects;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import vn.cane.admin.application.StaffCommandRepository.Staff;
import vn.cane.admin.domain.StaffRole;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.Transactions;
import vn.cane.support.validation.InputNormalizer;

@Service
public final class StaffCommandService {
    private final StaffCommandRepository repository;
    private final PasswordEncoder passwords;
    private final Transactions transactions;
    private final AdvisoryLocks locks;

    public StaffCommandService(
            StaffCommandRepository repository,
            PasswordEncoder passwords,
            Transactions transactions,
            AdvisoryLocks locks) {
        this.repository = repository;
        this.passwords = passwords;
        this.transactions = transactions;
        this.locks = locks;
    }

    public Result create(String username, CharSequence password, StaffRole role) {
        String normalized = InputNormalizer.username(username);
        Objects.requireNonNull(role);
        InputNormalizer.validateCreationPassword(password);
        String hash = passwords.encode(password); // Expensive hashing stays outside database locks.
        return transactions.write(() -> {
            lockStaffCommands();
            var existing = repository.lockByUsername(normalized);
            if (existing.isPresent()) return result(existing.get(), false);
            return result(repository.insert(normalized, hash, role), true);
        });
    }

    public Result resetPassword(String username, CharSequence password) {
        String normalized = InputNormalizer.username(username);
        InputNormalizer.validateCreationPassword(password);
        String hash = passwords.encode(password);
        return transactions.write(() -> {
            lockStaffCommands();
            Staff staff = requireStaff(normalized);
            int version = Math.incrementExact(staff.authVersion());
            repository.changePassword(staff.id(), hash);
            repository.revokeSessions(staff.id());
            return new Result(staff.id(), staff.role(), staff.active(), version, true);
        });
    }

    public Result changeRole(String username, StaffRole role) {
        return changeAccess(username, Objects.requireNonNull(role), null);
    }

    public Result changeActive(String username, boolean active) {
        return changeAccess(username, null, active);
    }

    private Result changeAccess(String username, StaffRole requestedRole, Boolean requestedActive) {
        String normalized = InputNormalizer.username(username);
        return transactions.write(() -> {
            lockStaffCommands();
            Staff staff = requireStaff(normalized);
            StaffRole role = requestedRole == null ? staff.role() : requestedRole;
            boolean active = requestedActive == null ? staff.active() : requestedActive;
            if (role == staff.role() && active == staff.active()) return result(staff, false);
            if (staff.role() == StaffRole.OWNER
                    && staff.active()
                    && (role != StaffRole.OWNER || !active)
                    && repository.countActiveOwners() <= 1) {
                throw new IllegalStateException("The last active Owner must remain active and Owner");
            }
            int version = Math.incrementExact(staff.authVersion());
            repository.changeAccess(staff.id(), role, active);
            repository.revokeSessions(staff.id());
            return new Result(staff.id(), role, active, version, true);
        });
    }

    private void lockStaffCommands() {
        locks.acquire("staff-commands", null, "active-owner-guard-v1");
    }

    private Staff requireStaff(String username) {
        return repository
                .lockByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Staff account does not exist"));
    }

    private static Result result(Staff staff, boolean changed) {
        return new Result(staff.id(), staff.role(), staff.active(), staff.authVersion(), changed);
    }

    public record Result(UUID staffId, StaffRole role, boolean active, int authVersion, boolean changed) {}
}
