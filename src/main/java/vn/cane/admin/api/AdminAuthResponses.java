package vn.cane.admin.api;

import java.time.Instant;
import java.util.UUID;
import vn.cane.admin.domain.StaffActor;

/** Related HTTP allowlists; neither response serializes a principal or a database row. */
public final class AdminAuthResponses {
    private AdminAuthResponses() {}

    static Session session(StaffActor actor, String csrfToken) {
        return new Session(user(actor), permissions(actor), csrfToken, actor.sessionExpiresAt());
    }

    static Login login(StaffActor actor) {
        return new Login(user(actor), permissions(actor), actor.sessionExpiresAt());
    }

    private static User user(StaffActor actor) {
        return new User(actor.id(), actor.username(), actor.role().databaseValue());
    }

    private static Permissions permissions(StaffActor actor) {
        return new Permissions(actor.canConfirmBank());
    }

    public record User(UUID id, String username, String role) {}

    public record Permissions(boolean canConfirmBank) {}

    public record Session(User user, Permissions permissions, String csrfToken, Instant sessionExpiresAt) {}

    public record Login(User user, Permissions permissions, Instant sessionExpiresAt) {}
}
