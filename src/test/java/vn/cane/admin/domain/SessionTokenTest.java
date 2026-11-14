package vn.cane.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SessionTokenTest {
    @Test
    void generatesIndependentCanonicalThirtyTwoByteTokensAndStoresOnlyDigest() throws Exception {
        var random = new SecureRandom();
        var distinct = new HashSet<String>();
        for (int index = 0; index < 1000; index++) {
            var token = SessionToken.generate(random);
            assertThat(token.value()).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(token.value())).hasSize(32);
            assertThat(token.hash())
                    .hasSize(32)
                    .isEqualTo(MessageDigest.getInstance("SHA-256")
                            .digest(token.value().getBytes(StandardCharsets.US_ASCII)));
            byte[] mutated = token.hash();
            mutated[0] ^= 1;
            assertThat(token.hash()).isNotEqualTo(mutated);
            assertThat(token.toString()).isEqualTo("SessionToken[redacted]").doesNotContain(token.value());
            assertThat(SessionToken.parse(token.value())).isPresent();
            distinct.add(token.value());
        }
        assertThat(distinct).hasSize(1000);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                "short",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            })
    void refusesMalformedOrNonCanonicalTokens(String token) {
        assertThat(SessionToken.parse(token)).isEmpty();
    }
}
