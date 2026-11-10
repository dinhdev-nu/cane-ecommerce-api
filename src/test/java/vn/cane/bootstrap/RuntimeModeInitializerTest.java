package vn.cane.bootstrap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import vn.cane.CaneApplication;

class RuntimeModeInitializerTest {
    @ParameterizedTest
    @CsvSource({"local,api,servlet", "test,worker,none", "staging,api,servlet", "production,worker,none"})
    void acceptsOneEnvironmentAndOneRole(String environment, String mode, String webType) {
        try (var context = context(environment, mode, webType)) {
            assertThatCode(() -> new RuntimeModeInitializer().initialize(context))
                    .doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "local", "api", "local,api,worker", "local,test,api", "unknown,api", "local,api,extra"})
    void rejectsMissingAmbiguousOrUnsupportedProfiles(String profiles) {
        try (var context = context("local", "api", "servlet")) {
            context.getEnvironment().setActiveProfiles(profiles.isEmpty() ? new String[0] : profiles.split(","));
            assertThatThrownBy(() -> new RuntimeModeInitializer().initialize(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly one");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "spring.datasource.url",
                "spring.datasource.username",
                "spring.datasource.password",
                "spring.data.redis.host",
                "spring.data.redis.username",
                "spring.data.redis.password"
            })
    void rejectsBlankConnectionSettingsWithoutEchoingSecrets(String property) {
        try (var context = context("local", "api", "servlet")) {
            ((MockEnvironment) context.getEnvironment()).setProperty(property, " ");
            assertThatThrownBy(() -> new RuntimeModeInitializer().initialize(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Missing configuration: " + property)
                    .hasMessageNotContaining("unit-fixture-only");
        }
    }

    @ParameterizedTest
    @CsvSource({
        "spring.main.web-application-type,none",
        "cane.runtime.mode,worker",
        "spring.flyway.enabled,true",
        "spring.sql.init.mode,always",
        "cane.environment,production",
        "server.forward-headers-strategy,framework"
    })
    void rejectsOverridesThatBreakTheRuntimeBoundary(String property, String value) {
        try (var context = context("local", "api", "servlet")) {
            ((MockEnvironment) context.getEnvironment()).setProperty(property, value);
            assertThatThrownBy(() -> new RuntimeModeInitializer().initialize(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("protected runtime setting");
        }
    }

    @Test
    void bootLoadsTheGuardBeforeTryingToConnectOrStartHttp() {
        var application = new SpringApplication(CaneApplication.class);
        application.setDefaultProperties(Map.of("logging.level.root", "OFF"));
        assertThatThrownBy(() -> application.run("--spring.profiles.active=local,api,worker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
    }

    private static GenericApplicationContext context(String environment, String mode, String webType) {
        var properties = new MockEnvironment()
                .withProperty("cane.environment", environment)
                .withProperty("server.forward-headers-strategy", "none")
                .withProperty("cane.runtime.mode", mode)
                .withProperty("spring.main.web-application-type", webType)
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("spring.sql.init.mode", "never")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/cane_test")
                .withProperty("spring.datasource.username", "cane_api")
                .withProperty("spring.datasource.password", "unit-fixture-only")
                .withProperty("spring.data.redis.host", "localhost")
                .withProperty("spring.data.redis.username", "cane_runtime")
                .withProperty("spring.data.redis.password", "unit-fixture-only");
        properties.setActiveProfiles(environment, mode);
        var context = new GenericApplicationContext();
        context.setEnvironment(properties);
        return context;
    }
}
