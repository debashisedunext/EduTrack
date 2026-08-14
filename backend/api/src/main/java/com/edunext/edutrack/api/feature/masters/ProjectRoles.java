package com.edunext.edutrack.api.feature.masters;

import java.util.Locale;
import java.util.Set;

/**
 * The vocabulary of {@code project_members.role_in_project}, in one place.
 *
 * <h2>Why this is a class and not a literal in two DTOs</h2>
 *
 * <p>B-011 wrote the six codes as a {@code @Pattern} regex on
 * {@code ResourceDtos.ProjectAssignment}; B-017 needs the same six on the Team
 * tab's write and patch, and a second copy is how the pair drifts. The specific
 * drift is silent: nothing re-checks the regex against
 * {@code ck_project_members_role}, so a seventh role added to one statement and
 * not the other does not fail a request, fail a save, or fail a build — it
 * simply means one screen accepts a value the other refuses, and the discovery
 * is somebody asking why the resource form can set a role the Team tab cannot.
 * B-013 made exactly this argument about §10.3 being written down three times.
 *
 * <p>{@link ProjectRolesTest} is the seam: it asserts {@link #REGEX} and
 * {@link #CODES} against each other and against the constraint's own list, so
 * the next divergence is a red build rather than a support ticket.
 *
 * <h2>Why these six and not {@code RoleCode}</h2>
 *
 * <p>The set differs from the six global roles of blueprint §2 <b>in both
 * directions</b>, which is why reusing that enum would have been wrong twice:
 *
 * <ul>
 *   <li>{@code VIEWER} is here and not there. Read-only access to one project is
 *       a per-project grant; a global viewer role would mean read-only access to
 *       everything, which is the opposite thing.</li>
 *   <li>{@code ADMIN} is there and not here. An Admin already sees every project
 *       through {@code ScopeResolver}, so an {@code ADMIN} membership would be a
 *       grant that changes nothing — and a grant that changes nothing is one
 *       somebody later assumes does something.</li>
 * </ul>
 *
 * <p>Blueprint §7.4 S-10 names them "PM / Dev / Support / QA / Deploy / Viewer";
 * the contract's {@code ProjectRoleCode} is the same set on the wire.
 */
public final class ProjectRoles {

    /**
     * For {@code @Pattern}, which needs a compile-time constant.
     *
     * <p>Anchored by Bean Validation's full-match semantics, so no {@code ^$} is
     * needed and adding one would be the sort of difference that makes two
     * copies look intentionally distinct.
     */
    public static final String REGEX = "PM|DEVELOPER|SUPPORT|QA|DEPLOYMENT|VIEWER";

    /** The message every field using {@link #REGEX} shows. One rule, one wording. */
    public static final String MESSAGE =
            "roleInProject must be PM, DEVELOPER, SUPPORT, QA, DEPLOYMENT or VIEWER";

    /** The same six, for code that has to decide rather than validate. */
    public static final Set<String> CODES =
            Set.of("PM", "DEVELOPER", "SUPPORT", "QA", "DEPLOYMENT", "VIEWER");

    private ProjectRoles() {
    }

    /**
     * Upper-cases and trims, mapping blank to null.
     *
     * <p><b>Null is a value here, not a missing one.</b> It is what the column
     * holds for "same as their global role", which B-011 established as the
     * common case: a membership forced to restate the person's role becomes a
     * stale override the first time that role changes.
     *
     * <p>{@link Locale#ROOT}, for the reason {@code ProjectService.normaliseCode}
     * gives — a server under a Turkish locale upper-cases {@code i} to
     * {@code İ}, and a role code is an identifier rather than prose.
     */
    public static String normalise(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
