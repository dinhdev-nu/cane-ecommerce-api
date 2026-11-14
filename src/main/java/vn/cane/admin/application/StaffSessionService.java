package vn.cane.admin.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import vn.cane.admin.domain.SessionToken;
import vn.cane.admin.domain.StaffActor;
import vn.cane.admin.domain.StaffRole;
import vn.cane.support.transaction.TransactionAccess;
import vn.cane.support.transaction.Transactions;

@Service
@Profile("api & !worker")
public final class StaffSessionService {
    private final StaffSessionRepository repository;
    private final Transactions transactions;
    private final SecureRandom random = new SecureRandom();

    public StaffSessionService(StaffSessionRepository repository, Transactions transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    /** Login verifies the password/lock and owns the caller transaction, including this write. */
    public IssuedSession issue(UUID staffId, int verifiedAuthVersion, String previousToken) {
        TransactionAccess.requireWrite();
        var token = SessionToken.generate(random);
        var lifetime = repository.insert(token.hash(), staffId, verifiedAuthVersion);
        SessionToken.parse(previousToken).ifPresent(previous -> repository.delete(previous.hash()));
        return new IssuedSession(token, lifetime.createdAt(), lifetime.expiresAt());
    }

    public Optional<StaffPrincipal> authenticate(String token) {
        return SessionToken.parse(token).flatMap(parsed -> authenticateHash(parsed.hash()));
    }

    Optional<StaffPrincipal> authenticateHash(byte[] tokenHash) {
        return repository
                .find(tokenHash)
                .filter(state -> state.active()
                        && state.staffVersion() == state.sessionVersion()
                        && state.expiresAt().isAfter(state.checkedAt()))
                .map(state -> new StaffPrincipal(
                        new StaffActor(
                                state.staffId(),
                                state.username(),
                                state.role(),
                                state.staffVersion(),
                                state.role() == StaffRole.OWNER || state.operatorCanConfirmBank(),
                                state.expiresAt()),
                        tokenHash));
    }

    public void revoke(String token) {
        SessionToken.parse(token)
                .ifPresent(parsed -> transactions.write(() -> {
                    repository.delete(parsed.hash());
                    return null;
                }));
    }

    static AuthenticationCredentialsNotFoundException missingSession() {
        return new AuthenticationCredentialsNotFoundException("A current staff session is required");
    }

    public static final class IssuedSession {
        private final SessionToken token;
        private final Instant createdAt;
        private final Instant expiresAt;

        private IssuedSession(SessionToken token, Instant createdAt, Instant expiresAt) {
            this.token = token;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        public SessionToken token() {
            return token;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public Instant expiresAt() {
            return expiresAt;
        }

        @Override
        public String toString() {
            return "IssuedSession[redacted]";
        }
    }
}
