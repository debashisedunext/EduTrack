package com.edunext.edutrack.api.feature.onboarding.prereqs;

/**
 * B-124 · a draft of the prerequisites master is already open.
 *
 * <p><b>One draft at a time, and the reason is not tidiness.</b> The master
 * is org-wide and singular, so two drafts would each be "the next version"
 * and publishing either would silently discard the other's work. The
 * database refuses it too — {@code uq_ob_prereq_template_versions_draft}
 * over a generated column — so this exception is the readable half of a
 * rule that holds either way.
 */
class PrereqDraftAlreadyExistsException extends RuntimeException {

    PrereqDraftAlreadyExistsException(int version) {
        super("version " + version + " of the prerequisites master is already an open draft; "
                + "publish or discard it before starting another");
    }
}
