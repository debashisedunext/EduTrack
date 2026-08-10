package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-026 · the decision A-032's filter chain will consult on every authenticated
 * request.
 *
 * <p>Tested now, ahead of the chain that will call it, for the reason
 * {@link PasswordChangeGate} gives: the allowlist is the part that is dangerous
 * to get wrong in either direction. Too tight and a user is deadlocked — refused
 * from the endpoint that is their only way out. Too loose and a person operating
 * on an administrator-generated password that was emailed in plain text can use
 * the application normally, which is the state the flag exists to prevent.
 */
class PasswordChangeGateTest {

    private final PasswordChangeGate gate = new PasswordChangeGate();

    private static Jwt token(boolean mustChangePassword) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("header.payload.signature")
                .header("alg", "HS256")
                .subject("42")
                .jti("f47ac10b-58cc-4372-a567-0e02b2c3d479")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .claim("role", "DEVELOPER");
        if (mustChangePassword) {
            builder.claim(AccessTokenIssuer.MUST_CHANGE_PASSWORD_CLAIM, true);
        }
        return builder.build();
    }

    // ── the ordinary case ───────────────────────────────────────────────────

    /**
     * The overwhelming majority of requests. A gate that blocked these would be
     * a total outage, which is why absence of the claim reads as "not required".
     */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/tickets", "/api/v1/me/password", "/api/v1/dashboard/summary"})
    @DisplayName("a token without the claim is never blocked, on any path")
    void aTokenWithoutTheClaimPassesEverywhere(String path) {
        assertThat(gate.blocks(token(false), path)).isFalse();
    }

    // ── the flagged case ────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/tickets",
            "/api/v1/tickets/1234",
            "/api/v1/me",
            "/api/v1/dashboard/summary",
            "/api/v1/reports/effort"})
    @DisplayName("a flagged token is refused everywhere outside the allowlist")
    void aFlaggedTokenIsBlocked(String path) {
        assertThat(gate.blocks(token(true), path)).isTrue();
    }

    /**
     * The deadlock check. Blocking the change-password endpoint would leave a
     * flagged user with a token that can do exactly nothing, including the one
     * thing they are being told to do.
     */
    @Test
    @DisplayName("the way out stays open — a flagged token may still change its password")
    void theChangePasswordRouteIsAlwaysReachable() {
        assertThat(gate.blocks(token(true), "/api/v1/me/password")).isFalse();
    }

    /**
     * Someone who does not want to set a password right now must be able to end
     * their session rather than be held inside it.
     */
    @Test
    @DisplayName("a flagged token may still sign out")
    void logoutStaysReachable() {
        assertThat(gate.blocks(token(true), "/api/v1/auth/logout")).isFalse();
    }

    /**
     * The claim is minted once and never mutates, so the only way a client
     * observes a cleared flag is by refreshing. Blocking refresh would strand
     * the user on the stale claim for the token's full fifteen minutes.
     */
    @Test
    @DisplayName("a flagged token may still refresh — that is how the cleared flag is observed")
    void refreshStaysReachable() {
        assertThat(gate.blocks(token(true), "/api/v1/auth/refresh")).isFalse();
    }

    /**
     * Exact matching, not {@code startsWith}. A prefix rule opens every path that
     * merely begins with an allowed one — and on a container that normalises
     * after the check, {@code /api/v1/me/password/../../tickets} with it.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/me/password/history",
            "/api/v1/me/passwordless",
            "/api/v1/auth/logout-all",
            "/api/v1/me/password/../../tickets"})
    @DisplayName("a path that merely starts with an allowed one is still blocked")
    void theAllowlistIsExactNotAPrefix(String path) {
        assertThat(gate.blocks(token(true), path)).isTrue();
    }

    /**
     * A claim of the wrong shape — a string {@code "true"}, say, or a number —
     * must not be truthy. Only a real boolean {@code true} blocks, which is the
     * only thing {@code AccessTokenIssuer} emits.
     */
    @Test
    @DisplayName("only a boolean true counts; a string 'true' does not")
    void onlyABooleanTrueBlocks() {
        Instant now = Instant.now();
        Jwt oddlyTyped = Jwt.withTokenValue("header.payload.signature")
                .header("alg", "HS256")
                .subject("42")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .claim(AccessTokenIssuer.MUST_CHANGE_PASSWORD_CLAIM, "true")
                .build();

        assertThat(gate.blocks(oddlyTyped, "/api/v1/tickets")).isFalse();
    }
}
