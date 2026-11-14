package vn.cane.orders.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.cane.orders.domain.InvalidOrderLinkException;
import vn.cane.orders.domain.OrderLinkState;

class HmacOrderLinkTokensTest {
    private static final UUID ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private static final OrderLinkState STATE = new OrderLinkState(ID, 7, Instant.ofEpochMilli(1794700800123L));
    private static final String OLD = key(0);
    private static final String NEW = key(1);
    private static final String CONFIG = "{\"old\":\"" + OLD + "\",\"new\":\"" + NEW + "\"}";
    private final HmacOrderLinkTokens codec = new HmacOrderLinkTokens(LinkKeyRing.parse(CONFIG, "old"));

    @Test
    void matchesIndependentPythonHmacVectorAndExactCanonicalWireFormat() {
        var token = codec.sign(STATE);
        assertThat(token.value())
                .isEqualTo(
                        "v1.old.12345678-1234-4234-8234-123456789abc.7.1794700800123.Se2n0icaJCO5Eojm0P8skVvqG6bLD7n9xTCNjlqCPGI");
        assertThat(codec.verifySignature(token.value())).isEqualTo(STATE);
        assertThat(token.toString()).doesNotContain(token.value(), "v1.");
        assertThat(codec.toString()).doesNotContain(OLD, NEW);
    }

    @Test
    void rejectsTamperedFieldsUnknownKidsPaddingAndNoncanonicalValuesWithOneError() {
        String valid = codec.sign(STATE).value();
        for (String invalid : List.of(
                "",
                valid + ".",
                "v2" + valid.substring(2),
                valid.replace(".old.", ".unknown."),
                valid.replace(".old.", ".new."),
                valid.replace(ID.toString(), UUID.randomUUID().toString()),
                valid.replace(".7.", ".8."),
                valid.replace("1794700800123", "1794700800124"),
                valid.substring(0, valid.length() - 1) + "A",
                valid.substring(0, valid.length() - 1) + "J",
                valid + "=",
                valid.replace(".7.", ".07."),
                valid.replace(".7.", ".+7."),
                valid.replace(".7.", ".0."),
                valid.replace(".7.", ".-1."),
                valid.replace(".7.", ".9223372036854775808."),
                valid.replace("1794700800123", "01794700800123"),
                valid.replace("1794700800123", "9223372036854775808"),
                valid.replace(ID.toString(), ID.toString().toUpperCase(java.util.Locale.ROOT)),
                valid.replace(ID.toString(), "1-1-1-1-1"),
                " " + valid,
                valid + "\n",
                valid + "é",
                "x".repeat(512),
                "x".repeat(513))) {
            assertThatThrownBy(() -> codec.verifySignature(invalid))
                    .isInstanceOf(InvalidOrderLinkException.class)
                    .hasMessage("Order link is invalid")
                    .hasNoCause();
        }
        assertThatThrownBy(() -> codec.verifySignature(null))
                .isInstanceOf(InvalidOrderLinkException.class)
                .hasNoCause();
    }

    @Test
    void changingActiveKidPreservesOldVerificationAndNewSignatureUsesNewKey() {
        String old = codec.sign(STATE).value();
        var rotated = new HmacOrderLinkTokens(LinkKeyRing.parse(CONFIG, "new"));
        String fresh = rotated.sign(STATE).value();
        assertThat(fresh).startsWith("v1.new.").isNotEqualTo(old);
        assertThat(rotated.verifySignature(old)).isEqualTo(STATE);
        assertThat(codec.verifySignature(fresh)).isEqualTo(STATE);
        var dropped = new HmacOrderLinkTokens(LinkKeyRing.parse("{\"new\":\"" + NEW + "\"}", "new"));
        assertThatThrownBy(() -> dropped.verifySignature(old)).isInstanceOf(InvalidOrderLinkException.class);
    }

    @Test
    void invalidSecretConfigurationIsRejectedWithoutEchoingParserInputOrCause() {
        for (String invalid : List.of(
                "",
                "null",
                "[]",
                "{}",
                "{PRIVATE-KEY-MARKER",
                CONFIG + " {}",
                "{\"old\":\"" + OLD + "\",\"old\":\"" + NEW + "\"}",
                "{\"old\":null}",
                "{\"old\":\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[31]) + "\"}",
                "{\"old\":\"" + OLD + "=\"}",
                "{\"bad.kid\":\"" + OLD + "\"}"))
            assertThatThrownBy(() -> LinkKeyRing.parse(invalid, "old"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Order link key configuration is missing or invalid")
                    .hasNoCause();
        for (String kid : List.of("", "missing", "x".repeat(33), "http://key"))
            assertThatThrownBy(() -> LinkKeyRing.parse(CONFIG, kid))
                    .isInstanceOf(IllegalStateException.class)
                    .hasNoCause();
        assertThatThrownBy(() -> LinkKeyRing.parse(null, "old")).isInstanceOf(IllegalStateException.class);
        assertThat(LinkKeyRing.parse(CONFIG, "old").toString()).doesNotContain(OLD, NEW);
    }

    @Test
    void stateDoesNotSilentlyRoundStoredExpiryAndVersionSupportsPositiveBigint() {
        assertThatThrownBy(() -> new OrderLinkState(ID, 0, STATE.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        codec.sign(new OrderLinkState(ID, 1, STATE.expiresAt().plusNanos(1))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(OrderLinkState.millisecondInstant(STATE.expiresAt().plusNanos(999999)))
                .isEqualTo(STATE.expiresAt());
        assertThat(STATE.validAt(STATE.expiresAt().minusNanos(1))).isTrue();
        assertThat(STATE.validAt(STATE.expiresAt())).isFalse();
        var maximum = new OrderLinkState(ID, Long.MAX_VALUE, STATE.expiresAt());
        assertThat(codec.verifySignature(codec.sign(maximum).value())).isEqualTo(maximum);
    }

    private static String key(int offset) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) bytes[i] = (byte) (i + offset);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
