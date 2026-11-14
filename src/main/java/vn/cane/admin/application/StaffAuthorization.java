package vn.cane.admin.application;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import vn.cane.admin.domain.StaffActor;
import vn.cane.admin.domain.StaffRole;

/** Re-read before replay/business guards, using the caller transaction for writes. */
@Service
@Profile("api & !worker")
public final class StaffAuthorization {
    private final StaffSessionService sessions;

    public StaffAuthorization(StaffSessionService sessions) {
        this.sessions = sessions;
    }

    public StaffActor requireCurrent(StaffPrincipal principal) {
        if (principal == null) throw StaffSessionService.missingSession();
        var current = sessions.authenticateHash(principal.tokenHash()).orElseThrow(StaffSessionService::missingSession);
        if (!current.actor().id().equals(principal.actor().id())) throw StaffSessionService.missingSession();
        return current.actor();
    }

    public StaffActor requireOwner(StaffPrincipal principal) {
        StaffActor actor = requireCurrent(principal);
        if (actor.role() != StaffRole.OWNER) throw new AccessDeniedException("Owner permission is required");
        return actor;
    }

    public StaffActor requireBankConfirmation(StaffPrincipal principal) {
        StaffActor actor = requireCurrent(principal);
        if (!actor.canConfirmBank()) throw new AccessDeniedException("Bank confirmation permission is required");
        return actor;
    }
}
