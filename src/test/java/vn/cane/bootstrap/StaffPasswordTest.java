package vn.cane.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.CharBuffer;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;

class StaffPasswordTest {
    @Test
    void sharedEncoderUsesArgon2idParametersFreshSaltAndPreservesSpacesAndUnicode() {
        var encoder = new PasswordConfiguration().staffPasswordEncoder();
        String password = "  mía ngọt 🍃 mật khẩu  ";
        String first = encoder.encode(CharBuffer.wrap(password.toCharArray()));
        String second = encoder.encode(password);
        assertThat(first).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        String[] parts = first.split("\\$");
        assertThat(Base64.getDecoder().decode(parts[4])).hasSize(16);
        assertThat(Base64.getDecoder().decode(parts[5])).hasSize(32);
        assertThat(second).isNotEqualTo(first);
        assertThat(encoder.matches(password, first)).isTrue();
        assertThat(encoder.matches(password.strip(), first)).isFalse();
        assertThat(encoder.matches("another test password", first)).isFalse();
        assertThat(first).doesNotContain(password);
    }

    @ParameterizedTest
    @ValueSource(ints = {15, 128})
    void acceptsPasswordBoundariesInUnicodeCodePointsWithoutTrimming(int count) {
        String password = "🍃".repeat(count);
        assertThat(InputNormalizer.creationPassword(password)).isEqualTo(password);
        InputNormalizer.validateCreationPassword(CharBuffer.wrap(password.toCharArray()));
        InputNormalizer.validateCreationPassword(" ".repeat(count));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 14, 129})
    void rejectsPasswordOutsideBoundaries(int count) {
        assertThatThrownBy(() -> InputNormalizer.validateCreationPassword(CharBuffer.wrap("🍃".repeat(count))))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void rejectsMissingPassword() {
        assertThatThrownBy(() -> InputNormalizer.validateCreationPassword(null))
                .isInstanceOf(InputValidationException.class);
    }
}
