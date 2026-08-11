package com.edunext.edutrack.api.feature.masters.resources;

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
        Boolean isActive) {

    public static final ResourceFilter NONE = new ResourceFilter(null, null, null, null, null);

    /** Blank is not a search term — a cleared search box must not match nothing. */
    public ResourceFilter {
        q = q == null || q.isBlank() ? null : q.trim();
        role = role == null || role.isBlank() ? null : role.trim();
    }
}
