package vn.cane.support.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientIpResolverTest {
    @Test
    void untrustedPeerCannotChooseForwardedAddress() {
        var resolver = new ClientIpResolver(List.of("10.0.0.0/24"));
        assertThat(resolver.resolve("203.0.113.9", "198.51.100.1, 10.0.0.2")).isEqualTo("203.0.113.9");
        assertThat(new ClientIpResolver(List.of()).resolve("127.0.0.1", "198.51.100.1"))
                .isEqualTo("127.0.0.1");
    }

    @Test
    void takesFirstUntrustedAddressFromTrustedRightHandChainAndIgnoresSpoofedLeftSide() {
        var resolver = new ClientIpResolver(List.of("127.0.0.1/32", "10.0.0.0/24"));
        assertThat(resolver.resolve("127.0.0.1", "spoofed.invalid, 203.0.113.9, 10.0.0.2"))
                .isEqualTo("203.0.113.9");
        assertThat(resolver.resolve("127.0.0.1", "203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve("10.0.0.2", null)).isEqualTo("10.0.0.2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "example.com", "", "203.0.113.9,", "999.1.2.3", "[::1]:80", "fe80::1%eth0"})
    void malformedTrustedForwardingFallsBackToActualPeerWithoutDns(String value) {
        assertThat(new ClientIpResolver(List.of("127.0.0.1")).resolve("127.0.0.1", value))
                .isEqualTo("127.0.0.1");
    }

    @Test
    void boundedForwardingAndIpv6HaveStableCanonicalBuckets() {
        var resolver = new ClientIpResolver(List.of("::1/128", "127.0.0.1"));
        assertThat(resolver.resolve("127.0.0.1", "1".repeat(4097))).isEqualTo("127.0.0.1");
        assertThat(resolver.resolve("127.0.0.1", "1.1.1.1,".repeat(33))).isEqualTo("127.0.0.1");
        assertThat(resolver.resolve("::1", "2001:db8::9"))
                .isEqualTo(ClientIpResolver.canonicalAddress("2001:0db8:0:0:0:0:0:9"));
        assertThat(ClientIpResolver.canonicalAddress("::ffff:192.0.2.9")).isEqualTo("192.0.2.9");
        assertThat(ClientIpResolver.canonicalAddress("192.000.002.009")).isEqualTo("192.0.2.9");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "host.example",
                "10.0.0.1/33",
                "::1/129",
                "127.0.0.1/",
                "127.0.0.1/-1",
                "10.1.2",
                "",
                "1.2.3.4/24/1"
            })
    void invalidProxyConfigurationIsRejected(String range) {
        assertThatThrownBy(() -> new ClientIpResolver(List.of(range))).isInstanceOf(IllegalArgumentException.class);
    }
}
