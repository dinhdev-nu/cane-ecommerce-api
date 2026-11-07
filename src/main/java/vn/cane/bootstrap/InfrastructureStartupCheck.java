package vn.cane.bootstrap;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/** Connection smoke only; schema compatibility and business readiness are implemented in WP01. */
@Component
public final class InfrastructureStartupCheck implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(InfrastructureStartupCheck.class);
    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;

    public InfrastructureStartupCheck(DataSource dataSource, RedisConnectionFactory redisConnectionFactory) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)
                          AND NOT pg_has_role(current_user, d.datdba, 'MEMBER')
                          AND NOT has_database_privilege(current_user, current_database(), 'CREATE')
                          AND NOT EXISTS (
                            SELECT 1 FROM pg_namespace n
                            WHERE left(n.nspname, 3) <> 'pg_' AND n.nspname <> 'information_schema'
                              AND (pg_has_role(current_user, n.nspowner, 'MEMBER')
                                OR has_schema_privilege(current_user, n.oid, 'CREATE'))
                          )
                        FROM pg_roles r JOIN pg_database d ON d.datname = current_database()
                        WHERE r.rolname = current_user
                        """)) {
            if (!result.next() || !result.getBoolean(1)) {
                throw new IllegalStateException("Runtime database role must not have ownership or DDL privileges");
            }
        } catch (java.sql.SQLException exception) {
            // Do not include driver messages: they can contain connection or SQL details.
            throw new IllegalStateException(
                    "PostgreSQL startup check failed; verify local connection and runtime role");
        }
        LOG.info("PostgreSQL runtime connection verified; business schema readiness is pending WP01");
        try (var connection = redisConnectionFactory.getConnection()) {
            if (!"PONG".equals(connection.ping())) {
                throw new IllegalStateException("Unexpected Redis ping response");
            }
            LOG.info("Redis connection verified");
        } catch (RuntimeException exception) {
            LOG.warn("Redis unavailable: infrastructure degraded; business fallback is pending WP01");
        }
    }
}
