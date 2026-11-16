package vn.cane.worker;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.SocketFactory;

/** Close the underlying socket at the total deadline, without waiting for JavaMail's transport monitor. */
final class SmtpDeadlineSockets extends SocketFactory implements AutoCloseable {
    private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean expired = new AtomicBoolean();
    private final ScheduledFuture<?> deadline;

    SmtpDeadlineSockets(ScheduledExecutorService scheduler, Duration budget) {
        deadline = scheduler.schedule(
                () -> {
                    expired.set(true);
                    closeSockets();
                },
                budget.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    boolean expired() {
        return expired.get();
    }

    @Override
    public Socket createSocket() throws IOException {
        var socket = new Socket();
        sockets.add(socket);
        if (expired.get()) {
            socket.close();
            throw new SocketTimeoutException();
        }
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connect(host, port, null, 0);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return createSocket(host.getHostAddress(), port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
        return connect(host, port, local, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
        return connect(host.getHostAddress(), port, local, localPort);
    }

    private Socket connect(String host, int port, InetAddress local, int localPort) throws IOException {
        var socket = createSocket();
        if (local != null) socket.bind(new InetSocketAddress(local, localPort));
        socket.connect(new InetSocketAddress(host, port), 5000);
        return socket;
    }

    private void closeSockets() {
        for (var socket : sockets)
            try {
                socket.close();
            } catch (IOException ignored) {
                /* Fixed provider error only. */
            }
    }

    @Override
    public void close() {
        deadline.cancel(false);
        closeSockets();
    }
}
