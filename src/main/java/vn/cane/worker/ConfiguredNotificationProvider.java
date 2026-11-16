package vn.cane.worker;

import jakarta.annotation.PreDestroy;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** SMTP capture is an actual local SMTP roundtrip; Telegram mock is clearly tagged capture data. */
@Component
@Profile("worker & !api")
@ConditionalOnProperty(name = "cane.notifications.enabled", havingValue = "true")
public final class ConfiguredNotificationProvider implements NotificationProvider {
    private final String host, username, password, from, fromName, telegramMode, botToken;
    private final int port;
    private final boolean live;
    private final Path capture;
    private final JsonMapper json;
    private final java.util.concurrent.ScheduledExecutorService deadlines = Executors.newScheduledThreadPool(4);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ConfiguredNotificationProvider(
            @Value("${cane.environment}") String environment,
            @Value("${cane.notifications.smtp-mode:capture}") String smtpMode,
            @Value("${cane.notifications.smtp-host:127.0.0.1}") String host,
            @Value("${cane.notifications.smtp-port:1025}") int port,
            @Value("${cane.notifications.smtp-username:}") String username,
            @Value("${cane.notifications.smtp-password:}") String password,
            @Value("${cane.notifications.from:dev@cane.invalid}") String from,
            @Value("${cane.notifications.from-name:CANE dev}") String fromName,
            @Value("${cane.notifications.telegram-mode:mock}") String telegramMode,
            @Value("${cane.notifications.telegram-token:}") String botToken,
            @Value("${cane.notifications.capture-directory:}") String captureDirectory,
            JsonMapper json) {
        boolean local = java.util.Set.of("local", "test", "dev").contains(environment);
        if (!java.util.Set.of("capture", "live").contains(smtpMode)
                || !java.util.Set.of("mock", "live").contains(telegramMode)
                || port < 1
                || port > 65535) throw new IllegalStateException("Notification transport configuration invalid");
        live = smtpMode.equals("live");
        if ((!live || telegramMode.equals("mock")) && !local)
            throw new IllegalStateException("Mock notifications prohibited in this environment");
        if (!live && !java.util.Set.of("127.0.0.1", "localhost", "::1").contains(host))
            throw new IllegalStateException("SMTP capture requires a loopback server");
        if (live && (host.isBlank() || from.endsWith(".invalid")))
            throw new IllegalStateException("Live SMTP configuration incomplete");
        if (telegramMode.equals("live") && !botToken.matches("[0-9]+:[A-Za-z0-9_-]+"))
            throw new IllegalStateException("Telegram configuration incomplete");
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.from = from;
        this.fromName = fromName;
        this.telegramMode = telegramMode;
        this.botToken = botToken;
        this.json = json;
        if (telegramMode.equals("mock")) {
            if (captureDirectory.isBlank()) throw new IllegalStateException("External mock capture directory required");
            capture = Path.of(captureDirectory).toAbsolutePath().normalize();
            if (capture.startsWith(Path.of("").toAbsolutePath().normalize()))
                throw new IllegalStateException("Mock capture must be outside the code repository");
        } else capture = null;
    }

    @Override
    public void send(OutboxLease lease, NotificationRenderer.Rendered message) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Provider inside transaction");
        switch (lease.channel()) {
            case "email" -> smtp(lease, message);
            case "telegram" -> telegram(lease, message);
            default -> throw new IllegalStateException("Unsupported notification channel");
        }
    }

    private void smtp(OutboxLease lease, NotificationRenderer.Rendered content) throws Exception {
        var props = new Properties();
        props.setProperty("mail.smtp.host", host);
        props.setProperty("mail.smtp.port", Integer.toString(port));
        props.setProperty("mail.smtp.connectiontimeout", "5000");
        props.setProperty("mail.smtp.timeout", "10000");
        props.setProperty("mail.smtp.writetimeout", "10000");
        props.setProperty("mail.smtp.auth", Boolean.toString(!username.isBlank()));
        props.setProperty("mail.smtp.starttls.enable", Boolean.toString(live));
        props.setProperty("mail.smtp.starttls.required", Boolean.toString(live));
        props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        try (var sockets = new SmtpDeadlineSockets(deadlines, Duration.ofSeconds(30))) {
            props.put("mail.smtp.socketFactory", sockets);
            props.setProperty("mail.smtp.socketFactory.fallback", "false");
            var session = Session.getInstance(props);
            var message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(lease.recipient(), true));
            message.setSubject(content.subject(), StandardCharsets.UTF_8.name());
            var parts = new MimeMultipart("alternative");
            var plain = new MimeBodyPart();
            plain.setText(content.text(), StandardCharsets.UTF_8.name());
            parts.addBodyPart(plain);
            var html = new MimeBodyPart();
            html.setContent(content.html(), "text/html; charset=UTF-8");
            parts.addBodyPart(html);
            message.setContent(parts);
            message.saveChanges();
            // SMTP has no exactly-once API; a stable Message-ID is diagnostic/dedup assistance only.
            message.setHeader("Message-ID", "<" + lease.id() + "@notifications.cane.invalid>");
            try (Transport transport = session.getTransport("smtp")) {
                try {
                    transport.connect(
                            host, port, username.isBlank() ? null : username, username.isBlank() ? null : password);
                    if (sockets.expired()) throw new java.util.concurrent.TimeoutException();
                    transport.sendMessage(message, message.getAllRecipients());
                    if (sockets.expired()) throw new java.util.concurrent.TimeoutException();
                } catch (Exception failure) {
                    if (sockets.expired()) throw new java.util.concurrent.TimeoutException();
                    throw failure;
                }
            }
        }
    }

    private void telegram(OutboxLease lease, NotificationRenderer.Rendered content) throws Exception {
        String text = content.text();
        if (text.codePointCount(0, text.length()) > 4000) text = text.substring(0, text.offsetByCodePoints(0, 4000));
        var body = json.createObjectNode()
                .put("chat_id", lease.recipient())
                .put("text", text)
                .put("disable_web_page_preview", true);
        if (telegramMode.equals("mock")) {
            Files.createDirectories(capture);
            body.put("mock", true).put("outboxId", lease.id().toString()).put("attempt", lease.attempts());
            Files.writeString(
                    capture.resolve(lease.id() + "-" + lease.attempts() + ".telegram.json"),
                    json.writeValueAsString(body),
                    StandardCharsets.UTF_8);
            return;
        }
        var request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200
                || !json.readTree(response.body()).path("ok").asBoolean(false))
            throw new IllegalStateException("Telegram provider rejected message");
    }

    @PreDestroy
    void close() {
        deadlines.shutdownNow();
        http.close();
    }
}
