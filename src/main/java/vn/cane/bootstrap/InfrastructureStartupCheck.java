package vn.cane.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** Fails context refresh before HTTP connectors or scheduled jobs start. */
@Component
public final class InfrastructureStartupCheck implements SmartInitializingSingleton {
    private static final Logger LOG = LoggerFactory.getLogger(InfrastructureStartupCheck.class);
    private final InfrastructureReadiness readiness;

    public InfrastructureStartupCheck(InfrastructureReadiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public void afterSingletonsInstantiated() {
        var status = readiness.inspect();
        if (!status.ready()) {
            throw new IllegalStateException(
                    "PostgreSQL startup check failed: " + status.database().name());
        }
        LOG.atInfo()
                .addKeyValue("component", "startup")
                .addKeyValue("database", status.database())
                .addKeyValue("redis", status.redisAvailable() ? "UP" : "DEGRADED")
                .log("Runtime infrastructure verified");
        if (status.degraded()) {
            LOG.atWarn()
                    .addKeyValue("component", "redis")
                    .log("Redis unavailable; runtime is operating with degraded Redis");
        }
    }
}
