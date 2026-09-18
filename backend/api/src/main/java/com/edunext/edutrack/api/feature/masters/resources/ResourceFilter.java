package com.edunext.edutrack.api.feature.masters.resources;

import java.util.List;

/**
 * B-010 · the four S-07 filters plus free-text search, as one value.
 *
 * <p>A record rather than five parameters threaded through the controller, the
 * service, the repository and the export writer. Every one of those needs the
 * whole set — the export exists precisely to produce "what I was looking at" —
 * and a five-argument signature repeated four times is where a caller
 * eventually transposes {@code projectId} and {@code managerId} and nothing
 * complains, because both are {@code Long}.
 *
 * <p>Every field is nullable, meaning "not filtered". That includes
 * {@code isActive}: null is "both", not "active", because S-07's status filter
 * has three positions and defaulting to active would hide every deactivated
 * resource from the screen whose job is to reactivate them.
 */
public record ResourceFilter(
        String q,
        String role,
        Long projectId,
        Long managerId,
        Boolean isActive,
        /**
         * Live onboarding module grants to keep, or empty for "not filtered".
         *
         * <p>The sixth filter, and the only one that is not a column on
         * {@code users}: it reads {@code user_module_access}, which is the
         * identity layer's table rather than this feature's. It is here anyway
         * because the question it answers is a directory question — *which
         * people may I name to this job* — and the alternative was a second
         * directory endpoint for the onboarding module to keep in step with
         * this one.
         *
         * <p>A list rather than one value because the real question is never
         * about a single role: who may review is {@code OB_MANAGER} or
         * {@code OB_ADMIN}, and asking twice and merging in the client is how
         * two pages of a cursor-paged list get stitched together wrongly.
         */
        List<String> obModuleRoles) {

    public static final ResourceFilter NONE =
            new ResourceFilter(null, null, null, null, null, List.of());

    /**
     * The five S-07 filters, with no module-role restriction.
     *
     * <p>The sixth is an onboarding concern that the resource grid, the export
     * and every assignee picker have no opinion about, and every one of them
     * constructs this. A convenience constructor rather than a null argument
     * threaded through thirty call sites, each of which would then have to be
     * read to find out that the null means "every role" rather than "none".
     */
    public ResourceFilter(String q, String role, Long projectId, Long managerId, Boolean isActive) {
        this(q, role, projectId, managerId, isActive, List.of());
    }

    /** Blank is not a search term — a cleared search box must not match nothing. */
    public ResourceFilter {
        q = q == null || q.isBlank() ? null : q.trim();
        role = role == null || role.isBlank() ? null : role.trim();
        /*
          Null and empty are the same answer — "every role" — and collapsing
          them here means the repository asks one question rather than two.
          Blank entries are dropped rather than passed on: an `IN ('')` is a
          clause that matches nothing, so a stray `&obModuleRole=` would empty
          a picker with no error anywhere to explain it.
        */
        obModuleRoles = obModuleRoles == null ? List.of() : obModuleRoles.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(String::trim)
                .toList();
    }
}
