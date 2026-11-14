package vn.cane.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import vn.cane.admin.domain.SessionToken;
import vn.cane.admin.domain.StaffRole;
import vn.cane.support.transaction.Transactions;

class StaffSessionServiceTest {
    private final StaffSessionRepository repository = mock(StaffSessionRepository.class);
    private final StaffSessionService sessions = new StaffSessionService(repository, mock(Transactions.class));
    private final SessionToken token = SessionToken.generate(new SecureRandom());
    private final Instant expiry = Instant.parse("2026-11-14T08:00:00Z");
    private final UUID staffId = UUID.randomUUID();

    @Test
    void expirationIsAbsoluteAndEqualityAlreadyMeansExpired() {
        for (Instant checked : new Instant[] {expiry.minusNanos(1), expiry, expiry.plusNanos(1)}) {
            snapshot(StaffRole.OPERATOR, true, 1, 1, false, checked);
            assertThat(sessions.authenticate(token.value()).isPresent()).isEqualTo(checked.isBefore(expiry));
        }
    }

    @Test
    void inactiveOrChangedVersionCannotAuthenticate() {
        snapshot(StaffRole.OWNER, false, 1, 1, false, expiry.minusSeconds(1));
        assertThat(sessions.authenticate(token.value())).isEmpty();
        snapshot(StaffRole.OWNER, true, 2, 1, false, expiry.minusSeconds(1));
        assertThat(sessions.authenticate(token.value())).isEmpty();
    }

    @Test
    void guardsReadCurrentRoleAndPermissionAgainRatherThanTrustingEarlierPrincipal() {
        snapshot(StaffRole.OWNER, true, 1, 1, false, expiry.minusSeconds(1));
        var principal = sessions.authenticate(token.value()).orElseThrow();
        assertThat(principal.toString()).isEqualTo("StaffPrincipal[redacted]");
        var authorization = new StaffAuthorization(sessions);
        assertThat(authorization.requireOwner(principal).canConfirmBank()).isTrue();
        snapshot(StaffRole.OPERATOR, true, 1, 1, false, expiry.minusSeconds(1));
        assertThatThrownBy(() -> authorization.requireOwner(principal)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.requireBankConfirmation(principal))
                .isInstanceOf(AccessDeniedException.class);
        snapshot(StaffRole.OPERATOR, true, 1, 1, true, expiry.minusSeconds(1));
        assertThat(authorization.requireBankConfirmation(principal).role()).isEqualTo(StaffRole.OPERATOR);
        snapshot(StaffRole.OPERATOR, false, 1, 1, true, expiry.minusSeconds(1));
        assertThatThrownBy(() -> authorization.requireCurrent(principal)).isInstanceOf(AuthenticationException.class);
        assertThatThrownBy(() -> authorization.requireCurrent(null)).isInstanceOf(AuthenticationException.class);
    }

    @Test
    void sessionIssuanceCannotOpenItsOwnTransactionOrBypassCaller() {
        assertThatThrownBy(() -> sessions.issue(staffId, 1, null)).isInstanceOf(IllegalStateException.class);
    }

    private void snapshot(StaffRole role, boolean active, int version, int sessionVersion, boolean bank, Instant now) {
        when(repository.find(token.hash()))
                .thenReturn(Optional.of(new StaffSessionRepository.Snapshot(
                        staffId, "fixture", role, active, version, sessionVersion, bank, expiry, now)));
    }
}
