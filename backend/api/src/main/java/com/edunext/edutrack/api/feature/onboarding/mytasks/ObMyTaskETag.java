package com.edunext.edutrack.api.feature.onboarding.mytasks;

/**
 * {@code ETag} for {@code /onboarding/my-tasks/{taskId}} — {@code ObProjectETag}'s
 * rules, applied to this resource.
 *
 * <p>Derived from the content rather than from a timestamp, for the reason that
 * class states: a timestamp tag moves when a save rewrites identical values,
 * costing a reload that protects nothing.
 *
 * <p><b>A validator, not a guard.</b> This resource is read only — the
 * transitions that change a task are the journey-step routes and carry their
 * own preconditions — so there is no {@code If-Match} half here. What the tag
 * buys is the focused page, which somebody leaves open while they work, being
 * answered {@code 304} on a revisit that has not changed.
 *
 * <p><b>A 32-bit hash, and two states of one task can collide.</b> Recorded
 * rather than fixed here: every tag in this repository is derived the same way,
 * and a stronger one is a change worth making across all of them at once.
 */
final class ObMyTaskETag {

    private ObMyTaskETag() {
    }

    static String of(ObMyTaskDtos.ObMyTask task) {
        return Integer.toHexString(task.hashCode());
    }

    /** {@code *} matches anything, per RFC 9110. */
    static boolean matches(String ifNoneMatch, String current) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String candidate = ifNoneMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }
}
