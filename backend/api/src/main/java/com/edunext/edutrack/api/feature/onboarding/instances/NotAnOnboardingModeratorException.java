package com.edunext.edutrack.api.feature.onboarding.instances;

/**
 * C-107 · 403 — the caller holds a role inside the {@code ONBOARDING} module
 * and it is neither {@code OB_MANAGER} nor {@code OB_ADMIN}. Plan §3's
 * "override steps with logged reason" is a capability the caller either
 * holds or does not, independent of which step is named — unlike {@link
 * NotStepOwnerException}, which depends on <em>this step's own</em> owner
 * columns and is a 422 for exactly that reason (see that class's own
 * javadoc). A capability failure that does not depend on the row existing is
 * {@code 403}, per {@code contracts/openapi.yaml}'s own note on this route's
 * response and CONVENTIONS.md §7's distinction from a row-scope 404.
 *
 * <p><b>Not the same refusal as holding no onboarding standing at all.</b>
 * {@code ObJourneyStepLifecycleService#requireModerator}'s own javadoc
 * explains why that case is a 404 — indistinguishable from the step not
 * existing — rather than this exception, and this class is never thrown for
 * a null or blank {@code moduleRole}.
 *
 * <p>Checked as a plain predicate against {@link
 * com.edunext.edutrack.api.security.CallerIdentity#moduleRole}, not a
 * {@code @PreAuthorize} expression — {@code OnboardingScopeResolver}'s own
 * reasoning applies here too: {@code moduleRoles} is not converted into a
 * Spring authority by {@code JwtAuthoritiesConverter}, so there is no
 * {@code hasAuthority(...)} this could be spelled as today.
 */
class NotAnOnboardingModeratorException extends RuntimeException {

    NotAnOnboardingModeratorException(String moduleRole) {
        super("skipping a step needs the onboarding module role OB_MANAGER or OB_ADMIN, caller holds "
                + (moduleRole == null || moduleRole.isBlank() ? "none" : moduleRole));
    }
}
