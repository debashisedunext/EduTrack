package com.edunext.edutrack.api.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A-071 · where a request appears to come from, in one place.
 *
 * <p>Extracted rather than copied. A-076 wrote this reading inside
 * {@code AuthController.clientKeyOf} to key the login rate limiter, and the
 * audit log needs the same reading for {@code audit_logs.ip_address} — at which
 * point there would have been two, and the failure mode of two is that a
 * deployment topology change (an extra proxy hop, a load balancer that rewrites
 * the header) is fixed in one and not the other. The rate limiter would then be
 * budgeting one address while the audit log recorded a different one, and both
 * would look right in isolation.
 *
 * <h2>A hint, not an identity</h2>
 *
 * <p>{@code X-Forwarded-For} is caller-supplied and trivially forged. A-076's
 * javadoc accepts that for the limiter — the per-source cap exists to make
 * casual enumeration slow, not to be unforgeable — and the same caveat applies
 * with more force here, because an audit log invites being read as evidence.
 * <b>The address on an audit row is what the request claimed, not where it came
 * from.</b> It is recorded because it is usually true and always useful for
 * correlation, not because it can be relied on against somebody who is trying.
 *
 * <p>The first entry is read because a proxy appends itself to the right; the
 * leftmost is therefore the original client, and everything after it is
 * infrastructure.
 */
public final class ClientAddress {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /** What is recorded when the container reports no address at all. */
    public static final String UNKNOWN = "unknown";

    private ClientAddress() {
    }

    /** @return the apparent client address; {@link #UNKNOWN} rather than null. */
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int firstComma = forwarded.indexOf(',');
            String first = (firstComma < 0 ? forwarded : forwarded.substring(0, firstComma)).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? UNKNOWN : remote;
    }
}
