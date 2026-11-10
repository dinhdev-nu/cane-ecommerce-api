package vn.cane.bootstrap;

import java.util.Arrays;
import java.util.Set;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/** Rejects ambiguous runtime roles before HTTP, schedulers or connections are created. */
public final class RuntimeModeInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final Set<String> MODES = Set.of("api", "worker");
    private static final Set<String> ENVIRONMENTS = Set.of("local", "test", "staging", "production");

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        String[] profiles = environment.getActiveProfiles();
        var modes = Arrays.stream(profiles).filter(MODES::contains).toList();
        var environments =
                Arrays.stream(profiles).filter(ENVIRONMENTS::contains).toList();
        if (modes.size() != 1 || environments.size() != 1 || profiles.length != 2) {
            throw new IllegalStateException(
                    "Select exactly one environment (local/test/staging/production) and one runtime role (api/worker)");
        }
        String mode = modes.getFirst();
        requireEquals(environment, "cane.environment", environments.getFirst());
        String expectedWebType = mode.equals("api") ? "servlet" : "none";
        requireEquals(environment, "cane.runtime.mode", mode);
        requireEquals(environment, "spring.main.web-application-type", expectedWebType);
        requireEquals(environment, "spring.flyway.enabled", "false");
        requireEquals(environment, "spring.sql.init.mode", "never");
        requireEquals(environment, "server.forward-headers-strategy", "none");
        for (String property : Set.of(
                "spring.datasource.url",
                "spring.datasource.username",
                "spring.datasource.password",
                "spring.data.redis.host",
                "spring.data.redis.username",
                "spring.data.redis.password")) {
            requireConfigured(environment, property);
        }
        if (!environment.getRequiredProperty("spring.datasource.url").startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("spring.datasource.url must use PostgreSQL JDBC");
        }
    }

    private static void requireEquals(ConfigurableEnvironment environment, String property, String expected) {
        if (!expected.equals(environment.getProperty(property))) {
            throw new IllegalStateException("Invalid protected runtime setting: " + property);
        }
    }

    private static void requireConfigured(ConfigurableEnvironment environment, String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank() || value.contains("<") || value.startsWith("CHANGE_ME")) {
            throw new IllegalStateException("Missing configuration: " + property);
        }
    }
}
