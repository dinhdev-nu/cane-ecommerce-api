package vn.cane.fixture;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Uses the same infrastructure scripts as Compose, with disposable credentials and test namespace. */
public final class TestInfrastructure {
    public static final String API_PASSWORD = secret();
    public static final String WORKER_PASSWORD = secret();
    public static final String MIGRATION_PASSWORD = secret();
    public static final String REDIS_PASSWORD = secret();
    public static final String POSTGRES_IMAGE =
            "postgres@sha256:5c4783a8cc3f0074912164f94dfa1be4e043438f0f707cf968d7af9a0d31a2c8";
    public static final String REDIS_IMAGE =
            "redis@sha256:298641b35ac51aa999f9bb85707d6681fe635ce59cda51886a0b6adc1da37b8e";

    private TestInfrastructure() {}

    public static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("cane_local")
                .withUsername("cane_prep_admin")
                .withPassword(secret())
                .withEnv("CANE_API_DB_PASSWORD", API_PASSWORD)
                .withEnv("CANE_WORKER_DB_PASSWORD", WORKER_PASSWORD)
                .withEnv("CANE_MIGRATION_PASSWORD", MIGRATION_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(
                                Path.of("scripts/dev/init-roles.sql").toAbsolutePath()),
                        "/docker-entrypoint-initdb.d/10-cane-roles.sql")
                .withLabel("vn.cane.scope", "prep02-it");
    }

    public static GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                .withExposedPorts(6379)
                .withEnv("CANE_REDIS_PASSWORD", REDIS_PASSWORD)
                .withEnv("CANE_ENVIRONMENT", "test")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(
                                Path.of("scripts/dev/redis-start.sh").toAbsolutePath()),
                        "/usr/local/bin/cane-redis.sh")
                .withCreateContainerCmdModifier(command -> command.withUser("redis"))
                .withCommand("/bin/sh", "/usr/local/bin/cane-redis.sh")
                .waitingFor(Wait.forListeningPort())
                .withLabel("vn.cane.scope", "prep02-it");
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
