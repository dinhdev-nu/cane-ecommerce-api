package vn.cane.bootstrap;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.SettingsEffects;
import vn.cane.catalog.application.PricesCache;
import vn.cane.catalog.application.PricesInvalidation;
import vn.cane.catalog.infrastructure.RedisPricesCache;
import vn.cane.support.transaction.Transactions;

@Configuration(proxyBeanMethods = false)
public class PricesConfiguration {
    @Bean
    PricesCache pricesCache(StringRedisTemplate redis, JsonMapper json, Clock clock, Environment environment) {
        return new RedisPricesCache(redis, json, clock, environment.getRequiredProperty("cane.environment"));
    }

    @Bean
    PricesInvalidation pricesInvalidation(Transactions transactions, PricesCache cache) {
        return new PricesInvalidation(transactions, cache::evict);
    }

    @Bean
    SettingsEffects settingsEffects(PricesInvalidation prices) {
        return prices::afterCommit;
    }
}
