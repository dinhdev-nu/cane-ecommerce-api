package vn.cane.admin.domain;

import java.time.Instant;
import java.util.UUID;

/** Current SQL identity/permissions; HTTP responses still use explicit DTOs. */
public record StaffActor(
        UUID id, String username, StaffRole role, int authVersion, boolean canConfirmBank, Instant sessionExpiresAt) {}
