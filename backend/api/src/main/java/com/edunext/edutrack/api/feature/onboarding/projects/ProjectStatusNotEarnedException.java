package com.edunext.edutrack.api.feature.onboarding.projects;

/**
 * A request asked for {@code COMPLETED}.
 *
 * <p>{@code ObProjectStatus.COMPLETED} is stamped when the project's last
 * journey completes and is accepted from no request —
 * {@code LiveStatusNotEarnedException} refuses the identical thing for a
 * client's {@code LIVE}, and for the identical reason: a status that means
 * "the work is finished" is worth nothing if somebody can type it while the
 * work is not.
 */
class ProjectStatusNotEarnedException extends RuntimeException {

    ProjectStatusNotEarnedException() {
        super("COMPLETED is stamped when the project's last journey completes, not set by hand");
    }
}
