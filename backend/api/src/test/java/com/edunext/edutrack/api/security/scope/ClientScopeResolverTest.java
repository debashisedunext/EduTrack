package com.edunext.edutrack.api.security.scope;

import com.edunext.edutrack.api.security.PrincipalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-126 · the pinning half.
 *
 * <p>Every assertion here is about <b>refusing</b>. A resolver that answered
 * the right id for a client and something permissive for everybody else would
 * pass a happy-path suite completely, and is exactly the shape that serves one
 * customer another customer's data — so the cases that matter are the staff
 * caller, the anonymous one, and the client whose link is absent.
 */
class ClientScopeResolverTest {

    private final ClientScopeResolver resolver = new ClientScopeResolver();

    private static Authentication client(Integer clientId, Integer obClientId) {
        Jwt.Builder builder = Jwt.withTokenValue("client")
                .header("alg", "HS256")
                .subject("55")
                .claim(PrincipalType.CLAIM, "CLIENT")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (clientId != null) builder.claim("client_id", clientId);
        if (obClientId != null) builder.claim("ob_client_id", obClientId);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }

    private static Authentication staff() {
        return new JwtAuthenticationToken(Jwt.withTokenValue("staff")
                .header("alg", "HS256")
                .subject("1")
                .claim("role", "ADMIN")
                .claim("projects", List.of())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build(), List.of());
    }

    @Test
    @DisplayName("answers the client's own ids")
    void answersOwnIds() {
        Authentication caller = client(7, 9);

        assertThat(resolver.ticketingClientId(caller)).contains(7L);
        assertThat(resolver.onboardingClientId(caller)).contains(9L);
    }

    @Test
    @DisplayName("a staff caller has no client scope at all")
    void staffHasNoClientScope() {
        // Empty, which every caller turns into a 404. The alternative reading —
        // "no restriction found, so apply none" — is how a portal query run by
        // a staff token returns every client's rows.
        assertThat(resolver.onboardingClientId(staff())).isEmpty();
        assertThat(resolver.ticketingClientId(staff())).isEmpty();
    }

    @Test
    @DisplayName("an anonymous caller has none either")
    void anonymousHasNoClientScope() {
        assertThat(resolver.onboardingClientId(null)).isEmpty();
        assertThat(resolver.ticketingClientId(
                new UsernamePasswordAuthenticationToken("nobody", null))).isEmpty();
    }

    @Test
    @DisplayName("a client linked to only one module has scope in that one only")
    void oneSidedLinkIsOrdinary() {
        // §2.3: the two client masters are disjoint and bridged by
        // client_accounts. An onboarding client with no ticketing counterpart is
        // the ordinary case, not an error.
        Authentication onboardingOnly = client(null, 9);

        assertThat(resolver.onboardingClientId(onboardingOnly)).contains(9L);
        assertThat(resolver.ticketingClientId(onboardingOnly)).isEmpty();
    }

    @Test
    @DisplayName("owns its own client and nobody else's")
    void ownershipIsExact() {
        Authentication caller = client(7, 9);

        assertThat(resolver.ownsOnboardingClient(caller, 9L)).isTrue();
        assertThat(resolver.ownsOnboardingClient(caller, 10L)).isFalse();
        assertThat(resolver.ownsTicketingClient(caller, 7L)).isTrue();
        assertThat(resolver.ownsTicketingClient(caller, 8L)).isFalse();
    }

    @Test
    @DisplayName("an absent link owns nothing — including a row whose id is also absent")
    void absentLinkOwnsNothingIncludingNull() {
        // The case a null-equals-null bug would let through: a client with no
        // onboarding link matching a row whose ob_client_id is also null.
        Authentication ticketingOnly = client(7, null);

        assertThat(resolver.ownsOnboardingClient(ticketingOnly, null)).isFalse();
        assertThat(resolver.ownsOnboardingClient(ticketingOnly, 9L)).isFalse();
    }

    @Test
    @DisplayName("a staff caller owns no client, whatever id is asked about")
    void staffOwnsNothing() {
        assertThat(resolver.ownsOnboardingClient(staff(), 9L)).isFalse();
        assertThat(resolver.ownsTicketingClient(staff(), 7L)).isFalse();
        assertThat(resolver.ownsOnboardingClient(staff(), null)).isFalse();
    }
}
