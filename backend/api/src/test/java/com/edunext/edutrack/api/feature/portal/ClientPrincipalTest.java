package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.PrincipalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-125 · the type boundary between a staff caller and a portal one.
 *
 * <p>These are the tests that matter most in the task. The boundary is not a
 * convenience — {@code client_accounts.id} and {@code users.id} are separate
 * sequences that overlap, so if a portal token could become a
 * {@code CallerIdentity} then {@code assigned_to = me} would compare a client's
 * account id against staff ids and return whichever staff member shares the
 * number. Not throw, not deny: return somebody else's rows.
 *
 * <p>So the collision is not hypothesised here, it is <em>constructed</em>:
 * every test below uses id {@link #COLLIDING_ID} as both a staff id and an
 * account id, because a test that used 7 and 9000 would pass whether or not the
 * boundary existed.
 */
class ClientPrincipalTest {

    /** One number, two meanings. The whole point of the type boundary. */
    private static final long COLLIDING_ID = 7L;

    // ── a portal token is not a staff identity ──────────────────────────────

    @Test
    @DisplayName("a CLIENT token does not become a CallerIdentity, even holding a valid staff id")
    void aClientTokenIsNotAStaffIdentity() {
        assertThat(CallerIdentity.of(auth(clientToken(COLLIDING_ID, 3L, 4L))))
                .as("""
                        A portal token became a staff identity. `sub` here is a client_accounts \
                        id, and users.id shares the sequence — so every staff scope check would \
                        now compare a client against staff ids and match on the collision.""")
                .isEmpty();
    }

    /**
     * The second, independent barrier. A portal token carries no {@code role},
     * and {@code CallerIdentity} already refuses a token without one — so the
     * refusal holds even if the principal-type check were removed.
     *
     * <p>Asserted separately because "two barriers" is only true while both are
     * actually load-bearing, and a later refactor that gives portal tokens a
     * role claim "for symmetry" would quietly remove this one.
     */
    @Test
    @DisplayName("a CLIENT token carries no role, which refuses it a second time over")
    void aClientTokenHasNoRoleClaim() {
        assertThat(clientToken(COLLIDING_ID, 3L, 4L).getClaimAsString("role")).isNull();
    }

    /**
     * <b>The test the other two only look like.</b>
     *
     * <p>{@link #aClientTokenIsNotAStaffIdentity} was written first and proved
     * nothing: its fixture has no {@code role}, so {@code CallerIdentity} was
     * refusing it on the role barrier and would have gone on refusing it with
     * the principal-type check deleted. Found by deleting that check and
     * watching all ten tests stay green — the same way {@code PermissionMatrix}
     * found its own first draft asserting nothing.
     *
     * <p>So this one carries <em>both</em> a CLIENT principal type and a valid
     * staff-shaped role claim. That combination cannot be minted by
     * {@code AccessTokenIssuer}; it is what a token would look like if somebody
     * later "harmonised" the two issuers, or if a portal token were ever
     * enriched with a role for symmetry. Either would silently hand a client
     * every staff scope keyed on {@code userId} — so the barrier has to hold on
     * the principal type alone, and this is the test that says it does.
     */
    @Test
    @DisplayName("principal_type alone refuses a CLIENT token, even one carrying a valid role")
    void thePrincipalTypeBarrierHoldsOnItsOwn() {
        Jwt clientTokenWithARole = Jwt.withTokenValue("t").header("alg", "none")
                .subject(String.valueOf(COLLIDING_ID))
                .claim(PrincipalType.CLAIM, PrincipalType.CLIENT.name())
                .claim("role", "ADMIN")
                .claim(ClientPrincipal.CLIENT_ID_CLAIM, 3L)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .build();

        assertThat(CallerIdentity.of(auth(clientTokenWithARole)))
                .as("""
                        A CLIENT token carrying a role became a staff identity — as ADMIN, \
                        with a `sub` that is a client_accounts id. This is the whole reason \
                        the principal-type check exists, and it must not depend on portal \
                        tokens happening to omit a role.""")
                .isEmpty();
    }

    // ── a staff token is not a portal caller ────────────────────────────────

    @Test
    @DisplayName("a staff token does not become a ClientPrincipal, even holding a valid account id")
    void aStaffTokenIsNotAPortalCaller() {
        assertThat(ClientPrincipal.of(auth(staffToken(COLLIDING_ID))))
                .as("""
                        A staff token became a portal caller. `sub` here is a users id, and \
                        reading it as an account id scopes the portal to whichever \
                        client_accounts row shares the number.""")
                .isEmpty();
    }

    @Test
    @DisplayName("a token with no principal_type at all is staff — the pre-A-125 shape still works")
    void anAbsentClaimIsStaff() {
        Jwt legacy = Jwt.withTokenValue("t").header("alg", "none")
                .subject(String.valueOf(COLLIDING_ID))
                .claim("role", "ADMIN")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .build();

        assertThat(CallerIdentity.of(auth(legacy))).isPresent();
        assertThat(ClientPrincipal.of(auth(legacy))).isEmpty();
    }

    // ── what a portal caller may see ────────────────────────────────────────

    @Test
    void aPortalCallerCarriesItsTwoClientIdsAndItsAccountId() {
        ClientPrincipal caller = ClientPrincipal.of(auth(clientToken(COLLIDING_ID, 3L, 4L))).orElseThrow();

        assertThat(caller.accountId()).isEqualTo(COLLIDING_ID);
        assertThat(caller.clientId()).isEqualTo(3L);
        assertThat(caller.obClientId()).isEqualTo(4L);
    }

    /**
     * The rule A-126 depends on: a null client id means <em>that tree is
     * empty</em>, never that it is unfiltered.
     */
    @Test
    @DisplayName("a null client id owns nothing — including a row whose own client id is null")
    void aNullClientIdOwnsNothing() {
        ClientPrincipal onboardingOnly =
                ClientPrincipal.of(auth(clientToken(COLLIDING_ID, null, 4L))).orElseThrow();

        assertThat(onboardingOnly.ownsTicketingClient(3L)).isFalse();
        // The one that would slip through `Objects.equals`: two nulls comparing
        // equal is how "holds no ticketing client" becomes "matches every row
        // whose client is unset".
        assertThat(onboardingOnly.ownsTicketingClient(null)).isFalse();
        assertThat(onboardingOnly.ownsOnboardingClient(4L)).isTrue();
    }

    @Test
    @DisplayName("a CLIENT token holding neither client is refused, not admitted empty")
    void aTokenOwningNothingIsRefused() {
        assertThat(ClientPrincipal.of(auth(clientToken(COLLIDING_ID, null, null)))).isEmpty();
    }

    @Test
    void anonymousIsNeitherKindOfCaller() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(ClientPrincipal.of(anonymous)).isEmpty();
        assertThat(CallerIdentity.of(anonymous)).isEmpty();
    }

    @Test
    @DisplayName("a subject that is not a number scopes to nothing rather than throwing")
    void anUnreadableSubjectIsRefused() {
        Jwt broken = Jwt.withTokenValue("t").header("alg", "none")
                .subject("not-a-number")
                .claim(PrincipalType.CLAIM, "CLIENT")
                .claim(ClientPrincipal.CLIENT_ID_CLAIM, 3L)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .build();

        assertThat(ClientPrincipal.of(auth(broken))).isEmpty();
    }

    /**
     * An unrecognised principal type reads as STAFF, not CLIENT.
     *
     * <p>Which looks like the wrong default until you ask what could produce
     * one: a token signed by a newer deploy, held by a member of staff. Reading
     * it as CLIENT would lock them out mid-deploy, and it cannot let a client
     * in because the missing {@code role} claim refuses them anyway.
     */
    @Test
    void anUnknownPrincipalTypeIsStaff() {
        assertThat(PrincipalType.of("ROBOT")).isEqualTo(PrincipalType.STAFF);
        assertThat(PrincipalType.of(null)).isEqualTo(PrincipalType.STAFF);
        assertThat(PrincipalType.of("  client  ")).isEqualTo(PrincipalType.CLIENT);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static JwtAuthenticationToken auth(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, List.of());
    }

    /** A portal token: principal_type CLIENT, no role claim, client ids. */
    private static Jwt clientToken(long accountId, Long clientId, Long obClientId) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none")
                .subject(String.valueOf(accountId))
                .claim(PrincipalType.CLAIM, PrincipalType.CLIENT.name())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600));
        if (clientId != null) {
            builder.claim(ClientPrincipal.CLIENT_ID_CLAIM, clientId);
        }
        if (obClientId != null) {
            builder.claim(ClientPrincipal.OB_CLIENT_ID_CLAIM, obClientId);
        }
        return builder.build();
    }

    /** A staff token, as AccessTokenIssuer mints one. */
    private static Jwt staffToken(long userId) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("role", "DEVELOPER")
                .claim("projects", List.of(1))
                .claim("modules", List.of("TICKETING"))
                .claim("moduleRoles", Map.of())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .build();
    }
}
