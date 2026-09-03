package com.edunext.edutrack.api.feature.onboarding.journeys;

/**
 * {@code beginRevision} clones the currently <b>active</b> version of a
 * product's template. Naming a draft or a retired version here is refused —
 * a draft is already open for editing, and a retired version is superseded
 * history, not "the template" an admin means when they start editing it.
 */
class TemplateNotActiveException extends RuntimeException {

    TemplateNotActiveException(long templateId) {
        super("journey template " + templateId + " is not the active version for its product");
    }
}
