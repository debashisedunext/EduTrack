package com.edunext.edutrack.api.security.portal;

import com.edunext.edutrack.api.feature.portal.ClientPrincipal;
import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * A-126 · which principal type may reach which route tree.
 *
 * <h2>Two trees, and the rule runs in both directions</h2>
 *
 * <p>A-125 built the type boundary: {@code CallerIdentity.of} answers empty for
 * a {@code CLIENT} token and {@link ClientPrincipal#of} answers empty for a
 * staff one, so neither can be mistaken for the other. What was missing is the
 * consequence — <b>a client on a staff route, and a staff user on the portal,
 * both answer 404</b>.
 *
 * <ul>
 *   <li>{@code /api/v1/portal/**} — clients only.</li>
 *   <li>every other {@code /api/**} — staff only.</li>
 * </ul>
 *
 * <h2>404 in both directions, for one reason and its mirror</h2>
 *
 * <p>A client reaching a staff route must not learn that route exists: the
 * staff surface is where owners, internal comments, escalations and TAT
 * internals live, and the onboarding plan's never-visible list names all four.
 * A 403 would confirm the endpoint to somebody outside the organisation.
 *
 * <p>The mirror is less obvious and matters as much. A staff user on
 * {@code /api/v1/portal/clients/7/prerequisites} must not learn whether client
 * 7 has a portal login — that is a fact about a customer's account, and a 403
 * discloses it. Both directions are the same no-existence-leak rule CONVENTIONS
 * §7 applies to rows, one level up.
 *
 * <h2>Why staff are refused the portal at all</h2>
 *
 * <p>They have their own views of everything the portal shows, scoped by
 * {@code OnboardingScopeResolver}. The portal's DTOs are deliberately separate —
 * plan §2.3's "separate portal DTO serializers, never staff DTOs with fields
 * hidden client-side" — and letting a staff token through would make those
 * serializers a second, unaudited path to the same data with none of the
 * scoping the staff routes carry.
 */
@Component
public class PortalRouteGuard {

    /** The client principal's own tree. Plan §2.3. */
    public static final String PORTAL_PREFIX = "/api/v1/portal/";

    /** Everything this guard has an opinion about at all. */
    private static final String API_PREFIX = "/api/v1/";

    /**
     * Whether the path is the portal's.
     *
     * <p>Prefix matching, like {@code ModuleAccessGuard}'s and for its reason:
     * a prefix that is wrong here widens what is guarded rather than what is
     * allowed, which costs a 404 to somebody who could have had a 200 and
     * discloses nothing.
     */
    public boolean isPortalRoute(String requestPath) {
        return requestPath != null && requestPath.startsWith(PORTAL_PREFIX);
    }

    /** True for the API surface this guard polices at all. */
    public boolean guards(String requestPath) {
        return requestPath != null && requestPath.startsWith(API_PREFIX);
    }

    /**
     * Whether this caller must be refused this path.
     *
     * <p><b>An unauthenticated request is not this guard's business.</b> It
     * answers false for a caller who is neither, because the chain's
     * {@code .authenticated()} rule has already refused those with a considered
     * 401 — and answering 404 here instead would tell somebody who simply has
     * not signed in that the route does not exist.
     *
     * <p>That is the opposite default to {@code ModuleAccessGuard.blocks},
     * which treats an absent caller as blocked. The difference is deliberate:
     * that guard runs after authorization and can assume somebody is signed in,
     * whereas this one answers a question about <em>which kind</em> of signed-in
     * caller — a question that does not arise until there is one.
     */
    public boolean blocks(Optional<CallerIdentity> staff, Optional<ClientPrincipal> client,
                          String requestPath) {

        if (!guards(requestPath)) {
            return false;
        }
        boolean portal = isPortalRoute(requestPath);

        if (client.isPresent()) {
            return !portal;
        }
        if (staff.isPresent()) {
            return portal;
        }
        return false;
    }
}
