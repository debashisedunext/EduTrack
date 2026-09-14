package com.edunext.edutrack.api.feature.onboarding.projects;

/**
 * A project this caller can see and may not change — OB Viewer and OB Step
 * Owner.
 *
 * <p><b>403, not 404</b>, and the difference from
 * {@link NotAnOnboardingProjectWriterException} is the whole point: this caller
 * can already read the project, so pretending it does not exist would
 * contradict the page they are looking at. {@code ObClientReadOnlyException}
 * draws the same line one feature over.
 */
class ObProjectReadOnlyException extends RuntimeException {

    ObProjectReadOnlyException(long projectId) {
        super("project " + projectId + " is read-only for this caller");
    }
}
