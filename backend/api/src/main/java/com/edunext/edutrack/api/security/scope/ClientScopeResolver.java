package com.edunext.edutrack.api.security.scope;

import com.edunext.edutrack.api.feature.portal.ClientPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * A-126 · every portal query pinned to the principal's own client ids.
 *
 * <h2>The staff resolvers narrow; this one identifies</h2>
 *
 * <p>{@code ScopeResolver} and {@code OnboardingScopeResolver} compose a
 * {@link org.springframework.data.jpa.domain.Specification} into a query
 * because a staff caller's scope is a <em>set</em> — the projects they belong
 * to, the journeys containing their steps — and the set is only knowable as a
 * predicate.
 *
 * <p>A client's scope is one row per module, and it is on the token. So this
 * resolver answers an id rather than building a predicate, and the callers use
 * it as an equality: {@code where ob_client_id = ?}. Producing a
 * {@code Specification} for a single known value would be the same rule wearing
 * a costume, and would hide the property that makes the portal safe — <b>there
 * is nothing to compose wrongly.</b>
 *
 * <h2>Two ids, because the two client masters are disjoint</h2>
 *
 * <p>Plan §2.3: the client login spans both modules and the ticketing and
 * onboarding client masters stay separate, bridged by {@code client_accounts}.
 * A-125's {@link ClientPrincipal} therefore carries both, and either may be
 * null — an onboarding client with no ticketing counterpart is the ordinary
 * case, not an error.
 *
 * <h2>Absent means refuse, and it must</h2>
 *
 * <p>Every method here answers empty rather than throwing, and every caller is
 * expected to turn empty into a <b>404</b>. That is the same direction
 * {@code OnboardingScopeResolver}'s {@code DENY_ALL} takes for a caller with no
 * onboarding standing: a scope that cannot be established denies, because the
 * alternative reading — "no restriction was found, so apply none" — is how a
 * portal serves one customer another customer's journeys.
 *
 * <p>This is the second layer, not the first: {@code PortalRouteFilter} has
 * already refused a non-client before any of this runs, so a staff caller never
 * reaches a portal query to be scoped. Both exist because either alone is one
 * mistake away from serving the wrong customer.
 */
@Component
public class ClientScopeResolver {

    /**
     * The onboarding client this caller speaks for, if any.
     *
     * @return empty for a staff caller, an unauthenticated one, or a client
     *         whose account is not linked to an onboarding client — all three
     *         of which the caller must treat as 404.
     */
    public Optional<Long> onboardingClientId(Authentication authentication) {
        return ClientPrincipal.of(authentication).map(ClientPrincipal::obClientId);
    }

    /** The ticketing client this caller speaks for, if any. */
    public Optional<Long> ticketingClientId(Authentication authentication) {
        return ClientPrincipal.of(authentication).map(ClientPrincipal::clientId);
    }

    /**
     * Whether this caller may see the named onboarding client.
     *
     * <p>Delegates to {@link ClientPrincipal#ownsOnboardingClient}, whose own
     * note records the property that matters: a null id on the principal
     * answers false for every candidate <b>including null</b>, so a client with
     * no onboarding link cannot match a row whose id is also absent.
     */
    public boolean ownsOnboardingClient(Authentication authentication, Long candidate) {
        return ClientPrincipal.of(authentication)
                .map(principal -> principal.ownsOnboardingClient(candidate))
                .orElse(false);
    }

    /** The ticketing mirror of {@link #ownsOnboardingClient}. */
    public boolean ownsTicketingClient(Authentication authentication, Long candidate) {
        return ClientPrincipal.of(authentication)
                .map(principal -> principal.ownsTicketingClient(candidate))
                .orElse(false);
    }
}
