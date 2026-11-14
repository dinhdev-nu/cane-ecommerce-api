package vn.cane.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginLockTest {
    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");

    @Test
    void fifthFailureStartsExactlyFifteenMinutes() {
        for (int previous = 0; previous < 5; previous++) {
            var result = LoginLock.evaluate(previous, null, NOW, false);
            assertThat(result.allowed()).isFalse();
            assertThat(result.failedCount()).isEqualTo(previous + 1);
            assertThat(result.lockedUntil()).isEqualTo(previous == 4 ? NOW.plus(Duration.ofMinutes(15)) : null);
        }
    }

    @Test
    void correctAndWrongPasswordsDuringLockDoNotExtendOrIncrement() {
        for (boolean matches : new boolean[] {true, false}) {
            assertThat(LoginLock.evaluate(5, NOW.plusNanos(1), NOW, matches))
                    .isEqualTo(new LoginLock.Decision(false, 5, NOW.plusNanos(1)));
        }
    }

    @Test
    void equalOrPastDeadlineResetsBeforeEvaluatingWrongPassword() {
        for (Instant deadline : new Instant[] {NOW, NOW.minusNanos(1)}) {
            assertThat(LoginLock.evaluate(5, deadline, NOW, false)).isEqualTo(new LoginLock.Decision(false, 1, null));
        }
    }

    @Test
    void correctPasswordAtOrAfterExpiryClearsCounterAndLock() {
        for (Instant deadline : new Instant[] {NOW, NOW.minusNanos(1)}) {
            assertThat(LoginLock.evaluate(5, deadline, NOW, true)).isEqualTo(new LoginLock.Decision(true, 0, null));
        }
        assertThat(LoginLock.evaluate(4, null, NOW, true)).isEqualTo(new LoginLock.Decision(true, 0, null));
    }
}
