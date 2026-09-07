package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.security.PrincipalType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

/**
 * A-125 · who is calling a portal route.
 *
 * <p>{@code CallerIdentity}'s opposite number, and deliberately not a subtype
 * of it. The two are built from the same token format by the same library and
 * are mutually exclusive: this refuses a staff token, {@code CallerIdentity}
 * refuses a portal one, and neither can be widened into the other. See
 * {@link PrincipalType} for why a shared type with a {@code CLIENT} role would
 * have been a row-scoping hole rather than a simplification.
 *
 * <h2>What a portal caller is allowed to be</h2>
 *
 * <p>Two ids and nothing else. There is no role, no permission list, no project
 * list — a client has none of those, and a field that is always empty is a
 * field somebody eventually treats as "unrestricted".
 *
 * <p>{@link #clientId} and {@link #obClientId} are the whole authorisation
 * story: A-126's {@code ClientScopeResolver} pins {@code /portal/tickets/**} to
 * the first and {@code /portal/onboarding/**} to the second. <b>A null one
 * means that tree is empty for this caller</b> — never that it is unfiltered.
 * That reading is the single most important line in this file, and
 * {@link #ownsTicketingClient} and {@link #ownsOnboardingClient} exist so no
 * caller has to remember it: a null answers false rather than being compared.
 *
 * @param accountId  {@code client_accounts.id}. <b>Never a {@code users} id.</b>
 *                   The sequences overlap, so this must not be compared with
 *                   one, and the type boundary is what stops it.
 * @param clientId   the ticketing master's row this login speaks for, or null.
 * @param obClientId the onboarding master's row, or null. At least one of the
 *                   two is non-null — {@code ck_client_accounts_has_a_master}
 *                   guarantees it in the schema, so nothing here has to handle
 *                   an account belonging to nobody.
 */
public record ClientPrincipal(long accountId, Long clientId, Long obClientId) {

    /** {@code client_id} on the token. Absent means this login holds no ticketing client. */
    static final String CLIENT_ID_CLAIM = "client_id";

    /** {@code ob_client_id} on the token. Absent means this login holds no onboarding client. */
    static final String OB_CLIENT_ID_CLAIM = "ob_client_id";

    /**
     * The portal caller behind an authentication, or empty if there is not one.
     *
     * <p>Empty covers every "this is not a portal caller" case with one answer —
     * anonymous, a staff token, a malformed subject, a token claiming CLIENT
     * with no client ids at all. None of them is distinguished, because a
     * portal route's response to all four is identical and telling them apart
     * would only ever be useful to somebody probing.
     */
    public static Optional<ClientPrincipal> of(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return fromToken(jwtAuthentication.getToken());
        }
        return Optional.empty();
    }

    private static Optional<ClientPrincipal> fromToken(Jwt jwt) {
        // The mirror of CallerIdentity's refusal. A staff token must not become
        // a portal caller either — its `sub` is a users id, and reading it as
        // an account id would scope the portal to whichever client_accounts row
        // shares the number.
        if (PrincipalType.of(jwt.getClaimAsString(PrincipalType.CLAIM)) != PrincipalType.CLIENT) {
            return Optional.empty();
        }
        Long accountId = asId(jwt.getSubject());
        if (accountId == null) {
            return Optional.empty();
        }
        Long clientId = asClaimId(jwt, CLIENT_ID_CLAIM);
        Long obClientId = asClaimId(jwt, OB_CLIENT_ID_CLAIM);
        // A token holding neither is a token that can see nothing. It is
        // refused rather than admitted-and-empty so that the "null means empty"
        // rule below never has to carry a caller who is entirely empty — that
        // is a minting bug, and an authenticated principal who owns nothing is
        // a shape somebody will eventually special-case into meaning "all".
        if (clientId == null && obClientId == null) {
            return Optional.empty();
        }
        return Optional.of(new ClientPrincipal(accountId, clientId, obClientId));
    }

    /**
     * Does this caller speak for that ticketing client?
     *
     * <p>A null {@link #clientId} answers false for every argument, including a
     * null one. Two nulls comparing equal is exactly how "this login holds no
     * ticketing client" would turn into "this login matches a row whose client
     * is unset".
     */
    public boolean ownsTicketingClient(Long candidate) {
        return clientId != null && clientId.equals(candidate);
    }

    /** As {@link #ownsTicketingClient}, one master over. */
    public boolean ownsOnboardingClient(Long candidate) {
        return obClientId != null && obClientId.equals(candidate);
    }

    private static Long asId(String subject) {
        try {
            return subject == null ? null : Long.valueOf(subject.trim());
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }

    /**
     * A claim that should hold a number.
     *
     * <p>Anything that is not one reads as absent rather than throwing, for
     * {@code CallerIdentity.asId}'s stated reason: a token we signed that says
     * something we do not understand makes the caller narrower, never wider.
     */
    private static Long asClaimId(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        return switch (value) {
            case null -> null;
            case Number number -> number.longValue();
            case String text -> asId(text);
            default -> null;
        };
    }
}
