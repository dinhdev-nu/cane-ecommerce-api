package vn.cane.support.transaction;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Transaction-scoped, non-blocking lock. Call before order/receipt/SKU locks. */
@Component
public final class AdvisoryLocks {
    private final NamedParameterJdbcTemplate jdbc;

    public AdvisoryLocks(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void acquire(String namespace, UUID actorId, String key) {
        TransactionAccess.requireWrite();
        boolean acquired = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(:lockId)",
                Map.of("lockId", lockId(namespace, actorId, key)),
                Boolean.class));
        if (!acquired) throw new RequestInProgressException();
    }

    // The 64-bit hash only serializes contenders; callers still compare the complete key/hash.
    static long lockId(String namespace, UUID actorId, String key) {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(key);
        if (namespace.isBlank() || key.isBlank()) throw new IllegalArgumentException("Lock namespace/key is required");
        byte[] namespaceBytes = namespace.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        var bytes = ByteBuffer.allocate(4 + namespaceBytes.length + 1 + 16 + 4 + keyBytes.length);
        bytes.putInt(namespaceBytes.length).put(namespaceBytes).put((byte) (actorId == null ? 0 : 1));
        if (actorId != null) bytes.putLong(actorId.getMostSignificantBits()).putLong(actorId.getLeastSignificantBits());
        bytes.putInt(keyBytes.length).put(keyBytes);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes.array(), 0, bytes.position());
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
