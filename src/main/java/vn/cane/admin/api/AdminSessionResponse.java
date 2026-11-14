package vn.cane.admin.api;

import java.time.Instant;
import java.util.UUID;
import vn.cane.admin.domain.StaffActor;

/** Explicit HTTP allowlist; never serialize the session principal or database row. */
public record AdminSessionResponse(User user, Permissions permissions, String csrfToken, Instant sessionExpiresAt) {
    static AdminSessionResponse from(StaffActor actor, String csrfToken) {
        return new AdminSessionResponse(
                new User(actor.id(), actor.username(), actor.role().databaseValue()),
                new Permissions(actor.canConfirmBank()),
                csrfToken,
                actor.sessionExpiresAt());
    }

    public record User(UUID id, String username, String role) {}

    public record Permissions(boolean canConfirmBank) {}
}
