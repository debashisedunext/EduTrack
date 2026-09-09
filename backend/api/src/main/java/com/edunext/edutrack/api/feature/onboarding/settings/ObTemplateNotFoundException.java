package com.edunext.edutrack.api.feature.onboarding.settings;

/** B-113 · 404 — no such notification template. */
class ObTemplateNotFoundException extends RuntimeException {

    ObTemplateNotFoundException(long templateId) {
        super("no notification template " + templateId);
    }
}
