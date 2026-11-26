package vn.cane.worker;

import static org.assertj.core.api.Assertions.*;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ConfiguredNotificationProviderTest {
    @TempDir
    Path directory;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void smtpCaptureReceivesMimeAndStableMessageIdAndTelegramMockNeverCallsNetwork(boolean configured)
            throws Exception {
        String replyTo = configured ? "support@example.invalid" : "";
        String feedbackTo = configured ? "feedback@example.invalid" : "";
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            var capture = executor.submit(() -> {
                try (var socket = server.accept();
                        var reader = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        var writer = new PrintWriter(
                                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    socket.setSoTimeout(5000);
                    writer.print("220 mock.local ESMTP\r\n");
                    writer.flush();
                    var data = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.equals("DATA")) {
                            writer.print("354 End with dot\r\n");
                            writer.flush();
                            while ((line = reader.readLine()) != null && !line.equals("."))
                                data.append(line.startsWith("..") ? line.substring(1) : line)
                                        .append("\r\n");
                            writer.print("250 captured\r\n");
                            writer.flush();
                        } else if (line.equals("QUIT")) {
                            writer.print("221 bye\r\n");
                            writer.flush();
                            break;
                        } else {
                            writer.print("250 mock.local\r\n");
                            writer.flush();
                        }
                    }
                    return data.toString();
                }
            });
            var provider = new ConfiguredNotificationProvider(
                    "local",
                    "capture",
                    "127.0.0.1",
                    server.getLocalPort(),
                    "",
                    "",
                    "dev@cane.invalid",
                    "Cane Shop",
                    replyTo,
                    feedbackTo,
                    "mock",
                    "",
                    directory.toString(),
                    JSON);
            var id = UUID.randomUUID();
            var lease = new OutboxLease(
                    id,
                    "order_placed",
                    UUID.randomUUID(),
                    null,
                    UUID.randomUUID(),
                    "email",
                    "guest@example.invalid",
                    1,
                    Instant.now());
            var message = new NotificationRenderer.Rendered(
                    "Cane Shop đã nhận đơn", "Xin chào bạn.\nHỗ trợ: MOCK", "<p>Xin chào bạn.</p>", null, 0);
            try {
                provider.send(lease, message);
                var mime = new MimeMessage(
                        Session.getInstance(new java.util.Properties()),
                        new ByteArrayInputStream(
                                capture.get(5, TimeUnit.SECONDS).getBytes(StandardCharsets.UTF_8)));
                assertThat(mime.getSubject()).isEqualTo(message.subject());
                assertThat(mime.getMessageID()).isEqualTo("<" + id + "@notifications.cane.invalid>");
                assertThat(mime.getHeader("Reply-To", null)).isEqualTo(replyTo.isEmpty() ? null : replyTo);
                assertThat(mime.getHeader("Return-Path", null))
                        .isEqualTo(feedbackTo.isEmpty() ? null : "<" + feedbackTo + ">");
                var parts = (jakarta.mail.Multipart) mime.getContent();
                assertThat(parts.getBodyPart(0).getContent().toString().replace("\r\n", "\n"))
                        .isEqualTo(message.text());
                var telegram = new OutboxLease(
                        UUID.randomUUID(),
                        "owner_alert",
                        lease.orderId(),
                        null,
                        lease.eventId(),
                        "telegram",
                        "12345678",
                        1,
                        Instant.now());
                provider.send(telegram, message);
                var mock = JSON.readTree(Files.readString(directory.resolve(telegram.id() + "-1.telegram.json")));
                assertThat(mock.path("mock").asBoolean()).isTrue();
                assertThat(mock.path("text").asString()).isEqualTo(message.text());
            } finally {
                provider.close();
            }
        }
    }
}
