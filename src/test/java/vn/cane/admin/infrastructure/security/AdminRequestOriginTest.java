package vn.cane.admin.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminRequestOriginTest {
    private final AdminRequestOrigin policy = new AdminRequestOrigin("https://cane.example.test", "production");

    @Test
    void matchesSchemeHostPortAndOnlyUsesRefererWhenOriginIsAbsent() {
        var request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://CANE.example.test:443");
        assertThat(policy.permits(request)).isTrue();
        request.removeHeader("Origin");
        request.addHeader("Referer", "https://cane.example.test/admin?tab=orders");
        assertThat(policy.permits(request)).isTrue();
        request.addHeader("Origin", "https://evil.example.test");
        assertThat(policy.permits(request)).isFalse();
        request.removeHeader("Origin");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        assertThat(policy.permits(request)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "null",
                "http://cane.example.test",
                "https://cane.example.test:444",
                "https://cane.example.test.evil",
                "https://user@cane.example.test",
                "https://cane.example.test/",
                "https://cane.example.test?q=1",
                "https://cane.example.test#x",
                "https://cane.example.test https://evil.test"
            })
    void rejectsMissingOrInvalidOriginsEvenWithForgedTrustedHost(String value) {
        var request = new MockHttpServletRequest();
        request.addHeader("Host", "cane.example.test");
        request.addHeader("X-Forwarded-Host", "cane.example.test");
        if (value != null) request.addHeader("Origin", value);
        assertThat(policy.permits(request)).isFalse();
    }

    @Test
    void rejectsAmbiguousHeadersAndMalformedReferer() {
        var request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://cane.example.test");
        request.addHeader("Origin", "https://cane.example.test");
        assertThat(policy.permits(request)).isFalse();
        request.removeHeader("Origin");
        request.addHeader("Referer", "https://cane.example.test@evil.test/admin");
        assertThat(policy.permits(request)).isFalse();
        request.removeHeader("Referer");
        request.addHeader("Referer", "https://cane.example.test/admin");
        request.addHeader("Referer", "https://cane.example.test/admin");
        assertThat(policy.permits(request)).isFalse();
    }

    @Test
    void validatesConfigurationWithoutLeakingRejectedValueAndProtectsOnlyAdminWrites() {
        assertThatThrownBy(() -> new AdminRequestOrigin("https://user:PRIVATE@cane.test/path", "production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("PRIVATE");
        assertThatThrownBy(() -> new AdminRequestOrigin("http://cane.test", "staging"))
                .isInstanceOf(IllegalStateException.class);
        new AdminRequestOrigin("http://localhost:3000", "local");
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE", "GET", "OPTIONS", "HEAD"}) {
            var request = new MockHttpServletRequest(method, "/api/v1/admin/auth/login");
            request.setServletPath("/api/v1/admin/auth/login");
            assertThat(AdminRequestOrigin.requiresProtection(request))
                    .isEqualTo(!java.util.Set.of("GET", "OPTIONS", "HEAD").contains(method));
            request.setServletPath("/api/v1/checkout/place");
            assertThat(AdminRequestOrigin.requiresProtection(request)).isFalse();
            request.setServletPath("/api/v1/administrator");
            assertThat(AdminRequestOrigin.requiresProtection(request)).isFalse();
        }
    }
}
