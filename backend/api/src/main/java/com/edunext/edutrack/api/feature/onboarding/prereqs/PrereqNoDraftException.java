package com.edunext.edutrack.api.feature.onboarding.prereqs;

/**
 * B-124 · there is no editable draft of the prerequisites master.
 *
 * <p>409 rather than 404: the master exists, and this call needs it to be
 * in a state it is not in. Editing the active version in place would change
 * what an in-flight client is being asked for, which is the whole thing
 * versioning exists to prevent — {@code beginObPrereqTemplateRevision}
 * opens a draft to edit instead.
 */
class PrereqNoDraftException extends RuntimeException {

    PrereqNoDraftException() {
        super("there is no open draft of the prerequisites master; call "
                + "POST /onboarding/prereq-template/revisions to start one");
    }
}
