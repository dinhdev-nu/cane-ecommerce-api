package vn.cane.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.cane.admin.application.StaffLoginRepository.Credentials;
import vn.cane.admin.domain.StaffRole;
import vn.cane.support.transaction.Transactions;
import vn.cane.support.validation.InputValidationException;

class StaffLoginServiceTest {
    private final StaffLoginRepository repository = mock(StaffLoginRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final StaffSessionService sessions = mock(StaffSessionService.class);
    private final Transactions transactions = mock(Transactions.class);
    private StaffLoginService service;

    @BeforeEach
    void setup() {
        when(passwords.encode(anyString())).thenReturn("dummy-hash");
        service = new StaffLoginService(repository, passwords, sessions, transactions);
        when(transactions.write(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
    }

    @Test
    void unknownUsernameBurnsSameEncoderWorkWithoutWrites() {
        when(repository.findByUsername("unknown")).thenReturn(Optional.empty());
        assertThat(service.login(" UNKNOWN ", "secret", null)).isEmpty();
        verify(passwords).matches("secret", "dummy-hash");
        verifyNoInteractions(transactions, sessions);
    }

    @Test
    void emptyPasswordStillBurnsNonemptyDummyWorkAndCannotSucceed() {
        when(repository.findByUsername("unknown")).thenReturn(Optional.empty());
        when(passwords.matches(anyString(), anyString())).thenReturn(true);
        assertThat(service.login("unknown", "", null)).isEmpty();
        verify(passwords).matches("empty-password-timing-only", "dummy-hash");
        verifyNoInteractions(transactions, sessions);
    }

    @Test
    void changedCredentialsRequireFreshVerificationAndBoundedRollback() {
        UUID id = UUID.randomUUID();
        var before = new Credentials(id, "owner", "old-hash", StaffRole.OWNER, true, 1, 0, null);
        var changed = new Credentials(id, "owner", "new-hash", StaffRole.OWNER, true, 2, 0, null);
        when(repository.findByUsername("owner")).thenReturn(Optional.of(before));
        when(repository.lock(id)).thenReturn(Optional.of(changed));
        assertThatThrownBy(() -> service.login("owner", "secret", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Staff credentials kept changing during login");
        verify(passwords, times(3)).matches("secret", "old-hash");
        verify(transactions, times(3)).write(any());
        verify(repository, never()).recordAttempt(any(), anyInt(), any());
        verifyNoInteractions(sessions);
    }

    @Test
    void loginRejectsCallerTransactionBeforeAnyPasswordWork() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.login("owner", "secret", null)).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(repository, sessions, transactions);
        verify(passwords, never()).matches(anyString(), anyString());
    }

    @Test
    void passwordLimitCountsCodepointsAndSensitiveTypesAreRedacted() {
        assertThatThrownBy(() -> service.login("owner", "😀".repeat(129), null))
                .isInstanceOf(InputValidationException.class);
        when(repository.findByUsername("owner")).thenReturn(Optional.empty());
        assertThat(service.login("owner", "😀".repeat(128), null)).isEmpty();
        var credentials = new Credentials(
                UUID.randomUUID(), "sensitive-owner", "sensitive-hash", StaffRole.OWNER, true, 1, 5, Instant.now());
        assertThat(credentials.toString()).isEqualTo("StaffCredentials[redacted]");
        assertThat(new vn.cane.admin.api.AdminLoginRequest("sensitive-owner", "sensitive-password").toString())
                .isEqualTo("AdminLoginRequest[redacted]");
    }
}
