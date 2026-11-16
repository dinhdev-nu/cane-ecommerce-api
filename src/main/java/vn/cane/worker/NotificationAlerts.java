package vn.cane.worker;

import java.util.ArrayList;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.cane.admin.application.CheckoutSettings;
import vn.cane.admin.application.SettingsReader;
import vn.cane.support.audit.BusinessEvent;
import vn.cane.support.audit.BusinessEventWriter;
import vn.cane.support.outbox.Notification;
import vn.cane.support.outbox.OutboxWriter;
import vn.cane.support.transaction.AdvisoryLocks;
import vn.cane.support.transaction.Transactions;

@Component
@Profile("worker & !api")
public final class NotificationAlerts {
    private final Transactions tx;
    private final AdvisoryLocks locks;
    private final JdbcOutboxQueue queue;
    private final BusinessEventWriter events;
    private final OutboxWriter outbox;
    private final SettingsReader settings;
    private final JsonMapper json;

    public NotificationAlerts(
            Transactions tx,
            AdvisoryLocks locks,
            JdbcOutboxQueue queue,
            BusinessEventWriter events,
            OutboxWriter outbox,
            SettingsReader settings,
            JsonMapper json) {
        this.tx = tx;
        this.locks = locks;
        this.queue = queue;
        this.events = events;
        this.outbox = outbox;
        this.settings = settings;
        this.json = json;
    }

    public void expired(UUID orderId, long version) {
        tx.write(() -> {
            create(
                    orderId,
                    null,
                    "owner_alert",
                    "order:" + orderId + ":link_expired:" + version,
                    json.createObjectNode().put("alertType", "link_expired").put("linkVersion", version),
                    null,
                    true);
            return null;
        });
    }
    /** Joins the fenced exhausted transition. Even alert failures get an event, never another alert. */
    public void exhausted(OutboxLease lease) {
        var source = queue.event(lease.eventId());
        boolean recurse = source.kind().equals("notification_exhausted")
                || source.facts().path("alertType").asString("").equals("notification_exhausted");
        create(
                lease.orderId(),
                lease.paymentId(),
                "notification_exhausted",
                "outbox:" + lease.id() + ":exhausted",
                json.createObjectNode()
                        .put("alertType", "notification_exhausted")
                        .put("failedOutboxId", lease.id().toString()),
                lease.channel(),
                !recurse);
    }

    private void create(
            UUID orderId,
            UUID paymentId,
            String kind,
            String source,
            ObjectNode facts,
            String excluded,
            boolean notify) {
        locks.acquire("notification-alert", orderId == null ? new UUID(0, 0) : orderId, source);
        if (queue.eventBySource(source) != null) return;
        UUID id = UUID.randomUUID();
        events.append(new BusinessEvent(id, orderId, paymentId, kind, null, null, "system", null, null, facts, source));
        if (!notify) return;
        var config = settings.read().get("owner_notify");
        var candidates = new ArrayList<Notification>();
        for (var channel : Notification.Channel.values()) {
            if (channel.databaseValue().equals(excluded)) continue;
            String recipient =
                    CheckoutSettings.text(config, channel == Notification.Channel.EMAIL ? "email" : "telegramChatId");
            if (recipient != null && !recipient.isBlank())
                candidates.add(new Notification(
                        UUID.randomUUID(),
                        Notification.Kind.OWNER_ALERT,
                        orderId,
                        paymentId,
                        channel,
                        recipient,
                        Notification.Audience.OWNER));
        }
        outbox.enqueue(id, candidates);
    }
}
