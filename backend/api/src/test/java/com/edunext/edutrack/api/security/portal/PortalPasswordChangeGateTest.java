package com.edunext.edutrack.api.security.portal;

import com.edunext.edutrack.api.feature.portal.ClientPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client portal's forced password change, as a decision.
 *
 * <p>What these pin is the security property the whole temporary-password flow
 * rests on: a login issued with a staff-readable password buys <b>one</b>
 * session, and that session can reach nothing but the form that replaces it.
 * Without this gate the flag is a suggestion the SPA is trusted to honour, and
 * anybody willing to skip a redirect operates the portal on a credential an
 * operator generated and can still recall.
 */
class PortalPasswordChangeGateTest {

    private final PortalPasswordChangeGate gate = new PortalPasswordChangeGate();

    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("41")
                .issuedAt(Instant.parse("2026-09-21T09:00:00Z"))
                .expiresAt(Instant.parse("2026-09-21T09:15:00Z"));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static Jwt mustChange() {
        return token(Map.of(ClientPrincipal.MUST_CHANGE_PASSWORD_CLAIM, true));
    }

    /**
     * A token from an account that has chosen its own password. The claim is
     * emitted only when true, so this one simply has no such claim.
     */
    private static Jwt settled() {
        return token(Map.of("ob_client_id", 9L));
    }

    @Test
    @DisplayName("refuses the portal to a client who has not replaced their temporary password")
    void refusesTheRestOfThePortal() {
        assertThat(gate.blocks(mustChange(), "/api/v1/portal/onboarding")).isTrue();
        assertThat(gate.blocks(mustChange(), "/api/v1/portal/tickets")).isTrue();
        assertThat(gate.blocks(mustChange(), "/api/v1/portal/onboarding/signoffs")).isTrue();
    }

    /**
     * The deadlock guard. Blocking the way out leaves the client with a
     * session that can do nothing at all, and no route that would fix it.
     */
    @Test
    @DisplayName("leaves the change-password route open, or there is no way out")
    void leavesTheWayOut() {
        assertThat(gate.blocks(mustChange(), "/api/v1/portal/me/password")).isFalse();
    }

    /**
     * Absence means "not required", deliberately. Every token minted before
     * the claim existed carries no such claim, and reading absence as
     * "required" would refuse every live session at once.
     */
    @Test
    @DisplayName("lets a token with no such claim through")
    void absentClaimIsNotRequired() {
        assertThat(gate.blocks(settled(), "/api/v1/portal/onboarding")).isFalse();
        assertThat(gate.blocks(token(Map.of()), "/api/v1/portal/tickets")).isFalse();
    }

    /**
     * The claim is emitted only when true. A false one is not a shape we mint,
     * but a gate that refused on any present claim would be one bug away from
     * locking out every client.
     */
    @Test
    @DisplayName("a claim that is present and false is not a refusal")
    void falseClaimIsNotARefusal() {
        Jwt notRequired = token(Map.of(ClientPrincipal.MUST_CHANGE_PASSWORD_CLAIM, false));

        assertThat(gate.blocks(notRequired, "/api/v1/portal/onboarding")).isFalse();
    }

    /**
     * Exact matching, not a prefix. A prefix rule would let
     * {@code /api/v1/portal/me/password/../onboarding} through on any
     * container that normalises after the check.
     */
    @Test
    @DisplayName("matches the allowed path exactly, so nothing rides in underneath it")
    void allowlistIsExact() {
        assertThat(gate.blocks(mustChange(), "/api/v1/portal/me/password/../onboarding")).isTrue();
        assertThat(gate.blocks(mustChange(), "/api/v1/portal/me/password/extra")).isTrue();
        assertThat(gate.blocks(mustChange(), "/api/v1/portal/me/passwordx")).isTrue();
    }

    /**
     * An unauthenticated caller is not this gate's business — they are on
     * {@code /portal/auth/login} and hold no token to read.
     */
    @Test
    @DisplayName("has no opinion without a token")
    void noTokenNoOpinion() {
        assertThat(gate.blocks(null, "/api/v1/portal/onboarding")).isFalse();
    }
}
