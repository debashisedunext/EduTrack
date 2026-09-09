package com.edunext.edutrack.api.feature.portal;

import java.time.Instant;

/**
 * C-121 · what Redis holds for one portal refresh token. {@code
 * StoredRefreshToken}'s shape, minus the device-fingerprint and family fields
 * that class's reuse-detection scheme needs — this store does not implement
 * that scheme; see {@link PortalRefreshTokenStore}'s class note.
 *
 * @param jti          identifies this token, for logging.
 * @param clientAccountId {@code client_accounts.id} — never a {@code users} id.
 * @param issuedAt     UTC.
 * @param expiresAt    UTC — Redis expires the key at this instant on its own.
 */
record StoredPortalRefreshToken(String jti, long clientAccountId, Instant issuedAt, Instant expiresAt) {
}
