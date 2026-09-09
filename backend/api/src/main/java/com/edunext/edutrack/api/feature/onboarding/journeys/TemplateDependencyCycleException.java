package com.edunext.edutrack.api.feature.onboarding.journeys;

/**
 * C-123 · plan §5 item 5's "cycle-free" picker, enforced rather than trusted
 * to the UI: naming a dependency whose own chain already leads back to this
 * template — directly, or through any number of other services — would make
 * every journey on the cycle wait forever.
 */
class TemplateDependencyCycleException extends RuntimeException {

    TemplateDependencyCycleException(long templateId, long dependsOnTemplateId) {
        super("journey template " + dependsOnTemplateId + " already depends, directly or "
                + "transitively, on template " + templateId + " — choosing it here would close a cycle");
    }
}
