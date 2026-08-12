package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * A-031 · where each role lands after signing in. Blueprint §7.1 and screen
 * S-01.
 *
 * <h2>Server-side, and deliberately so</h2>
 *
 * <p>The frontend already knows how to <i>follow</i> {@code landingRoute} — the
 * contract has carried the field since D-001 and {@code authStore} has consumed
 * it since A-030. What it must not hold is the <b>map</b>. The server already
 * knows the role and the blueprint's mapping, and a second copy in TypeScript is
 * the one nobody updates when B-011 adds a role: the new role would silently
 * land wherever the TypeScript default happens to point, and nothing would fail.
 *
 * <p>These are the same values Stream D's MSW handler serves, so the contract,
 * the mock and the server agree rather than drifting apart the first time
 * someone tests against one and ships against the other.
 *
 * <h2>The route for a role this map has never seen</h2>
 *
 * <p><b>Returns the dashboard, and logs.</b> Returning {@code null} would be
 * worse than wrong: {@link Session} omits null fields, the frontend would apply
 * its own fallback, and the result — everybody lands on the dashboard — is
 * indistinguishable from the map working correctly. A role added by B-011's
 * Resource Master and never mapped here is a real gap, and it should be visible
 * in a log rather than hidden behind a sensible-looking default.
 *
 * <p>Logged at WARN rather than thrown. A missing landing route is a
 * misconfiguration, not a security failure, and refusing the login over it would
 * turn "somebody added a role" into "that role cannot sign in".
 *
 * <h2>One route this application does not serve yet</h2>
 *
 * <p>{@code /stages/queue} is <b>C-062</b> (blueprint S-31, the QA and
 * Deployment team inbox) and does not exist in the router today. It is mapped
 * here anyway, because this map states where the blueprint says those roles
 * belong — not where the frontend currently happens to have a screen. The
 * frontend guards against a route it cannot serve, so QA and Deployment land
 * somewhere real today and land <i>here</i> the day C-062 ships, with no change
 * needed on either side.
 */
final class LandingRoutes {

    private static final Logger log = LoggerFactory.getLogger(LandingRoutes.class);

    static final String DASHBOARD = "/dashboard";

    /**
     * Keyed on the role <i>code</i>, never the surrogate id — the same choice
     * {@code AuthUserRepository} makes for the {@code role} claim, and for the
     * same reason: ids are environment-specific and a map keyed on them is wrong
     * the moment it meets a database seeded in a different order.
     */
    private static final Map<String, String> BY_ROLE_CODE = Map.of(
            "ADMIN", DASHBOARD,
            "PM", DASHBOARD,
            "DEVELOPER", "/my-tasks",
            "SUPPORT", "/tickets",
            "QA", "/stages/queue",
            "DEPLOYMENT", "/stages/queue");

    private LandingRoutes() {
    }

    static String forRole(String roleCode) {
        // Guarded rather than passed straight through: Map.of() is an immutable
        // map whose get(null) throws NPE rather than returning null, so the
        // obvious one-liner turns a missing role code into a 500 on a request
        // that has already authenticated somebody.
        String route = roleCode == null ? null : BY_ROLE_CODE.get(roleCode);
        if (route == null) {
            log.warn("auth: no landing route mapped for role '{}' - falling back to {}. "
                    + "Add it to LandingRoutes when a role is introduced.", roleCode, DASHBOARD);
            return DASHBOARD;
        }
        return route;
    }
}
