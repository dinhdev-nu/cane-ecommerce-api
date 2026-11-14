package vn.cane.admin.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.cane.admin.application.StaffLoginRepository.Credentials;
import vn.cane.admin.domain.LoginLock;
import vn.cane.admin.domain.StaffActor;
import vn.cane.admin.domain.StaffRole;
import vn.cane.support.transaction.Transactions;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;

@Service
@Profile("api & !worker")
public final class StaffLoginService {
    private static final int MAX_VERIFICATIONS = 3;
    private final StaffLoginRepository repository;
    private final PasswordEncoder passwords;
    private final StaffSessionService sessions;
    private final Transactions transactions;
    private final String dummyHash;

    public StaffLoginService(
            StaffLoginRepository repository,
            PasswordEncoder passwords,
            StaffSessionService sessions,
            Transactions transactions) {
        this.repository = repository;
        this.passwords = passwords;
        this.sessions = sessions;
        this.transactions = transactions;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public Optional<AuthenticatedLogin> login(String username, String password, String previousSession) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Login must verify passwords outside the caller transaction");
        }
        String normalized = InputNormalizer.username(username);
        if (password == null) throw InputValidationException.malformed("password");
        if (password.codePointCount(0, password.length()) > 128) {
            throw InputValidationException.invalid("password", "Mật khẩu vượt giới hạn độ dài.");
        }
        for (int attempt = 0; attempt < MAX_VERIFICATIONS; attempt++) {
            var candidate = repository.findByUsername(normalized);
            // Spring skips hashing empty strings. Burn the dummy work without accepting an empty password.
            String checked = password.isEmpty() ? "empty-password-timing-only" : password;
            String hash = password.isEmpty() || candidate.isEmpty()
                    ? dummyHash
                    : candidate.get().passwordHash();
            boolean matches = passwords.matches(checked, hash) && !password.isEmpty();
            if (candidate.isEmpty()) return Optional.empty();
            try {
                return transactions.write(() -> finish(candidate.get(), matches, previousSession));
            } catch (CredentialsChanged retryVerification) {
                // The previous transaction rolled back. Re-read and hash again without retaining its locks.
            }
        }
        throw new IllegalStateException("Staff credentials kept changing during login");
    }

    private Optional<AuthenticatedLogin> finish(Credentials verified, boolean matches, String previousSession) {
        Credentials current = repository.lock(verified.id()).orElseThrow(CredentialsChanged::new);
        if (current.authVersion() != verified.authVersion()
                || current.active() != verified.active()
                || !current.passwordHash().equals(verified.passwordHash())) throw new CredentialsChanged();
        if (!current.active()) return Optional.empty();
        var decision =
                LoginLock.evaluate(current.failedCount(), current.lockedUntil(), repository.currentTime(), matches);
        if (decision.failedCount() != current.failedCount()
                || !Objects.equals(decision.lockedUntil(), current.lockedUntil())) {
            repository.recordAttempt(current.id(), decision.failedCount(), decision.lockedUntil());
        }
        // Rejection is a committed result, never an exception that undoes the failed-attempt counter.
        if (!decision.allowed()) return Optional.empty();
        boolean bankPermission = repository.operatorCanConfirmBank();
        var session = sessions.issue(current.id(), current.authVersion(), previousSession);
        var actor = new StaffActor(
                current.id(),
                current.username(),
                current.role(),
                current.authVersion(),
                current.role() == StaffRole.OWNER || bankPermission,
                session.expiresAt());
        return Optional.of(new AuthenticatedLogin(actor, session));
    }

    public record AuthenticatedLogin(StaffActor actor, StaffSessionService.IssuedSession session) {
        @Override
        public String toString() {
            return "AuthenticatedLogin[redacted]";
        }
    }

    private static final class CredentialsChanged extends RuntimeException {
        private CredentialsChanged() {
            super("Staff credentials changed during verification", null, false, false);
        }
    }
}
