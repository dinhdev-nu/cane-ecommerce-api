package vn.cane.bootstrap;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/** Internal, on-demand diagnostics; no public route, cache or background poller. */
@Component
public final class InfrastructureReadiness {
    private final DatabaseReadiness database;
    private final RedisConnectionFactory redis;

    public InfrastructureReadiness(DatabaseReadiness database, RedisConnectionFactory redis) {
        this.database = database;
        this.redis = redis;
    }

    public Snapshot inspect() {
        var databaseStatus = database.inspect();
        boolean redisAvailable;
        try (var connection = redis.getConnection()) {
            redisAvailable = "PONG".equals(connection.ping());
        } catch (RuntimeException failure) {
            redisAvailable = false;
        }
        return new Snapshot(databaseStatus, redisAvailable);
    }

    public record Snapshot(DatabaseReadiness.Status database, boolean redisAvailable) {
        public boolean ready() {
            return database == DatabaseReadiness.Status.READY;
        }

        public boolean degraded() {
            return ready() && !redisAvailable;
        }
    }
}
