package com.edunext.edutrack.api.feature.onboarding.prereqs;

/**
 * B-124's core guarantee: <b>a version that has ever been published cannot
 * be mutated again</b> — not while it is the active version, and not after
 * a later version has superseded it.
 *
 * <p>{@code ObPrereqTemplateVersion}'s javadoc spells out why the test is
 * {@code publishedAt == null} and never {@code !isActive}: a retired
 * version is still the one some client was snapshotted from, and the
 * checklist they agreed to has to keep reading back as it was. An edit here
 * would rewrite what a boarded client was asked for.
 */
class PrereqTaskNotEditableException extends RuntimeException {

    PrereqTaskNotEditableException(long templateTaskId, int version) {
        super("prerequisite template task " + templateTaskId + " belongs to version " + version
                + ", which has been published and can no longer be edited in place; call "
                + "POST /onboarding/prereq-template/revisions to open a new draft");
    }
}
