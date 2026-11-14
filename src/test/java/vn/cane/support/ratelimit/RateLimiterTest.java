package vn.cane.support.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import vn.cane.fixture.MutableClock;

class RateLimiterTest {
    @ParameterizedTest
    @EnumSource(RateGroup.class)
    void thresholdsExpireExactlySixtySecondsFromFirstCallAndDeniedCallsDoNotExtendWindow(RateGroup group) {
        var clock = clock();
        var limiter = new RateLimiter("test", new ProcessRateCounter(clock), new ProcessRateCounter(clock));
        for (int index = 0; index < group.limit(); index++)
            assertThat(limiter.check(group, "192.0.2.9").allowed()).isTrue();
        assertThat(limiter.check(group, "192.0.2.9")).isEqualTo(new RateLimiter.Decision(false, 60, false));
        clock.advance(Duration.ofMillis(59_001));
        assertThat(limiter.check(group, "192.0.2.9")).isEqualTo(new RateLimiter.Decision(false, 1, false));
        clock.advance(Duration.ofMillis(999));
        assertThat(limiter.check(group, "192.0.2.9")).isEqualTo(new RateLimiter.Decision(true, 60, false));
    }

    @Test
    void keysSeparateEnvironmentGroupAndCanonicalIpAndContainOnlyIpDigest() {
        var keys = new ArrayList<String>();
        RateCounter counter = key -> {
            keys.add(key);
            return new RateCounter.Hit(1, 60_000);
        };
        var limiter = new RateLimiter("test", counter, counter);
        limiter.check(RateGroup.LOGIN, "192.000.002.009");
        limiter.check(RateGroup.LOGIN, "::ffff:192.0.2.9");
        limiter.check(RateGroup.QUOTE, "192.0.2.9");
        limiter.check(RateGroup.LOGIN, "192.0.2.10");
        new RateLimiter("staging", counter, counter).check(RateGroup.LOGIN, "192.0.2.9");
        assertThat(keys.get(0))
                .isEqualTo(keys.get(1))
                .matches("cane:test:limit:login:[0-9a-f]{64}")
                .doesNotContain("192.0.2.9");
        assertThat(keys.get(2)).isNotEqualTo(keys.get(0));
        assertThat(keys.get(3)).isNotEqualTo(keys.get(0));
        assertThat(keys.get(4)).isNotEqualTo(keys.get(0));
    }

    @Test
    void failureUsesFallbackOncePerRequestDoesNotRetryRedisAndLogsNoExceptionOrAddress() {
        var clock = clock();
        var failures = new AtomicBoolean(true);
        var attempts = new AtomicInteger();
        RateCounter redis = key -> {
            attempts.incrementAndGet();
            if (failures.get()) throw new DataAccessResourceFailureException("private-failure-sentinel 192.0.2.9");
            return new RateCounter.Hit(1, 60_000);
        };
        var limiter = new RateLimiter("test", redis, new ProcessRateCounter(clock));
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RateLimiter.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logger.addAppender(events);
        try {
            for (int index = 0; index < 5; index++)
                assertThat(limiter.check(RateGroup.LOGIN, "192.0.2.9").allowed())
                        .isTrue();
            assertThat(limiter.check(RateGroup.LOGIN, "192.0.2.9"))
                    .isEqualTo(new RateLimiter.Decision(false, 60, true));
            assertThat(attempts).hasValue(6);
            failures.set(false);
            assertThat(limiter.check(RateGroup.LOGIN, "192.0.2.9").fallback()).isFalse();
            assertThat(events.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("private-failure-sentinel", "192.0.2.9");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previousLevel);
            events.stop();
        }
    }

    @Test
    void concurrentFallbackAdmitsExactlyFiveAndRestartCanBeginANewWindow() throws Exception {
        var clock = clock();
        RateCounter unavailable = key -> {
            throw new DataAccessResourceFailureException("Controlled outage");
        };
        var limiter = new RateLimiter("test", unavailable, new ProcessRateCounter(clock));
        var start = new CountDownLatch(1);
        var allowed = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 100; index++)
                futures.add(executor.submit(() -> {
                    start.await();
                    if (limiter.check(RateGroup.LOGIN, "192.0.2.9").allowed()) allowed.incrementAndGet();
                    return null;
                }));
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(allowed).hasValue(5);
        assertThat(limiter.check(RateGroup.LOGIN, "192.0.2.9").allowed()).isFalse();
        assertThat(new RateLimiter("test", unavailable, new ProcessRateCounter(clock))
                        .check(RateGroup.LOGIN, "192.0.2.9")
                        .allowed())
                .isTrue();
        clock.advance(Duration.ofSeconds(60));
        assertThat(limiter.check(RateGroup.LOGIN, "192.0.2.9").allowed()).isTrue();
    }

    @Test
    void rejectsUnknownNamespaceAndInvalidPeerBeforeAnyCounterCall() {
        RateCounter unused = key -> {
            throw new AssertionError("Counter must not be called");
        };
        assertThatThrownBy(() -> new RateLimiter("other", unused, unused)).isInstanceOf(IllegalArgumentException.class);
        var limiter = new RateLimiter("test", unused, unused);
        assertThatThrownBy(() -> limiter.check(RateGroup.LOGIN, "host.example"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MutableClock clock() {
        return new MutableClock(Instant.parse("2026-11-14T00:00:00Z"));
    }
}
