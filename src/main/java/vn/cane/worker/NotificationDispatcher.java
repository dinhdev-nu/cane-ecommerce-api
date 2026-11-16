package vn.cane.worker;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("worker & !api")
@ConditionalOnProperty(name = "cane.notifications.enabled", havingValue = "true")
public final class NotificationDispatcher {
    private final OutboxDispatchService service;
    private final NotificationSourceLoader sources;
    private final NotificationRenderer renderer;
    private final NotificationProvider provider;
    private final NotificationAlerts alerts;
    private final java.util.concurrent.ExecutorService senders = Executors.newFixedThreadPool(4);

    public NotificationDispatcher(
            OutboxDispatchService service,
            NotificationSourceLoader sources,
            NotificationRenderer renderer,
            NotificationProvider provider,
            NotificationAlerts alerts) {
        this.service = service;
        this.sources = sources;
        this.renderer = renderer;
        this.provider = provider;
        this.alerts = alerts;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void dispatch() {
        try {
            var tasks = service.claim().stream()
                    .map(lease -> senders.submit(() -> send(lease)))
                    .toList();
            // Each provider call enforces its own total deadline; fixed pool bounds concurrency, including timeouts.
            for (var task : tasks) {
                try {
                    task.get();
                } catch (java.util.concurrent.ExecutionException failure) {
                    warn();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            warn();
        }
    }

    private void send(OutboxLease lease) {
        if (!service.hasTime(lease)) {
            service.failed(lease, "LEASE_EXPIRED");
            return;
        }
        NotificationRenderer.Rendered message;
        try {
            var source = sources.load(lease);
            if (source.skip()) {
                service.skipped(lease);
                return;
            }
            message = renderer.render(lease, source);
            if (message.expiredOrderId() != null) alerts.expired(message.expiredOrderId(), message.expiredVersion());
        } catch (RuntimeException failure) {
            service.failed(lease, "SOURCE_INVALID");
            return;
        }
        try {
            if (!service.hasTime(lease)) {
                service.failed(lease, "LEASE_EXPIRED");
                return;
            }
            provider.send(lease, message);
        } catch (java.net.SocketTimeoutException | java.util.concurrent.TimeoutException timeout) {
            service.failed(lease, "PROVIDER_TIMEOUT");
            return;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            service.failed(lease, "PROVIDER_FAILED");
            return;
        }
        service.sent(lease);
    }

    private static void warn() {
        LoggerFactory.getLogger(NotificationDispatcher.class).warn("Notification dispatch unavailable");
    }

    @PreDestroy
    void close() {
        senders.shutdownNow();
        try {
            senders.awaitTermination(35, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
