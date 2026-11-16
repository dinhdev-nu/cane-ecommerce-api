package vn.cane.worker;

import static org.assertj.core.api.Assertions.*;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NotificationPolicyTest {
    @Test
    void backoffIncludesFirstAttemptJitterAndCap() {
        assertThat(OutboxDispatchService.backoff(1, 0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(OutboxDispatchService.backoff(1, 0.5)).isEqualTo(Duration.ofSeconds(33));
        assertThat(OutboxDispatchService.backoff(8, 0)).isEqualTo(Duration.ofSeconds(3600));
        assertThat(OutboxDispatchService.backoff(8, 0.999)).isLessThan(Duration.ofSeconds(4320));
        assertThatThrownBy(() -> OutboxDispatchService.backoff(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allDynamicHtmlIsEscaped() {
        assertThat(NotificationRenderer.escape("<script a=\"x\">&'</script>"))
                .isEqualTo("&lt;script a=&quot;x&quot;&gt;&amp;&#39;&lt;/script&gt;");
    }

    @Test
    void totalDeadlineClosesSocketEvenIfProviderNeverFinishesAResponse() throws Exception {
        try (var server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                var scheduler = Executors.newScheduledThreadPool(1);
                var sockets = new SmtpDeadlineSockets(scheduler, Duration.ofMillis(200));
                var client = sockets.createSocket("127.0.0.1", server.getLocalPort());
                var peer = server.accept()) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.getInputStream().read()).isInstanceOf(java.io.IOException.class);
            assertThat(sockets.expired()).isTrue();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2000);
        }
    }
}
