package vn.cane.bootstrap.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationalPricesInvalidationTest {
    @Test
    void invalidConfigurationWarnsWithoutThrowingOrDisclosingConfiguration() {
        for (var environment : List.of(
                Map.<String, String>of(),
                Map.of(
                        "CANE_ENVIRONMENT", "test",
                        "CANE_REDIS_HOST", "private-host.invalid",
                        "CANE_REDIS_PORT", "invalid-private-port",
                        "CANE_REDIS_USER", "private-user",
                        "CANE_REDIS_PASSWORD", "fixture-secret"))) {
            var buffer = new StringWriter();
            var output = new PrintWriter(buffer);

            assertThatCode(() -> OperationalPricesInvalidation.evictWithWarning(environment, "test", output))
                    .doesNotThrowAnyException();
            assertThat(buffer.toString())
                    .isEqualTo(
                            "WARNING: Database changes committed, but prices cache invalidation failed. Check environment and Redis configuration; do not repeat the committed operation."
                                    + System.lineSeparator());
        }
    }
}
