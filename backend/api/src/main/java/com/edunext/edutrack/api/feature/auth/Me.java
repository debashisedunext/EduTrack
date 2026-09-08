package com.edunext.edutrack.api.feature.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A-020 · {@code Me} in {@code contracts/openapi.yaml} — {@code UserRef} plus
 * the caller's own scope.
 *
 * <p>Named for the contract schema rather than for Java taste, so the document
 * springdoc emits and the hand-authored contract converge on one name. D-005
 * fails the build when the generated client drifts, and a schema renamed on
 * the Java side reads as drift.
 *
 * <p>{@code avatarUrl} is in the contract but not in this response: the
 * {@code users} table has no avatar column, and inventing a placeholder URL
 * would be a field the frontend learns to trust and then finds empty. It is
 * optional in the schema, so omitting it is conformant.
 *
 * <p><b>{@code permissions} is advisory to the UI, never load-bearing.</b> It
 * exists so the client can grey out an action it knows will be refused.
 * Authorisation itself is decided server-side by A-033 and A-034 on every
 * request; a client that removed a button still cannot call the endpoint.
 */
@Schema(description = "The authenticated caller, with the scope their role grants.")
record Me(

        @Schema(description = "Stable numeric id. The `sub` claim of the access token.")
        long id,

        @Schema(description = "Full name, for display.")
        String displayName,

        @Schema(description = "ADMIN, PM, DEVELOPER, QA, DEPLOYMENT or SUPPORT.")
        String role,

        String username,

        String email,

        @Schema(description = "Dotted capability codes from the §2 matrix. Advisory to the UI only.")
        List<String> permissions,

        @Schema(description = "Projects this user belongs to. The PM/Support row scope of §10.2.")
        List<Long> projectIds,

        @Schema(description = "Direct reportees. One level, not the whole tree.")
        List<Long> reporteeIds,

        @Schema(description = "Display timezone. Storage is UTC everywhere; this is applied at presentation.")
        String timezone,

        /**
         * A-116 · which modules this user may reach — {@code TICKETING},
         * {@code ONBOARDING}, or both.
         *
         * <p>Advisory to the UI in exactly the sense {@code permissions} is,
         * and for the same reason: A-111's {@code ModuleAccessFilter} decides
         * reachability server-side on every request and answers 404, so a
         * client that rendered a card for a module it does not hold would get
         * a not-found rather than a leak. What this buys is the launcher being
         * able to render the right cards on first paint instead of discovering
         * the answer by firing two requests and watching one 404.
         *
         * <p>The same staleness bargain the claim makes: a grant revoked
         * mid-session stays visible here until the access token expires, at
         * most fifteen minutes. Stated rather than discovered.
         */
        @Schema(description = "Modules this user may reach: TICKETING, ONBOARDING, or both. "
                + "Advisory to the UI; reachability is decided server-side per request.")
        List<String> modules
) {

    static Me from(AuthenticatedUser user) {
        return new Me(
                user.id(),
                user.fullName(),
                user.roleCode(),
                user.username(),
                user.email(),
                user.permissions(),
                user.projectIds(),
                user.reporteeIds(),
                user.timezone(),
                user.modules());
    }
}
