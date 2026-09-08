package com.edunext.edutrack.api.feature.onboarding.prereqs;

/** B-124 · no {@code ob_prereq_template_task_docs} row for the given id. */
class PrereqTemplateTaskDocNotFoundException extends RuntimeException {

    PrereqTemplateTaskDocNotFoundException(long docId) {
        super("prerequisite template task document " + docId + " does not exist");
    }
}
