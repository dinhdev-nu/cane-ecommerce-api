package vn.cane.worker;

/** A return means provider ACK, never inbox delivery. No database transaction may span this call. */
public interface NotificationProvider {
    void send(OutboxLease lease, NotificationRenderer.Rendered message) throws Exception;
}
