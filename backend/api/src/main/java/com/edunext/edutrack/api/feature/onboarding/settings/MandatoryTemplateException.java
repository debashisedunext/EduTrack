package com.edunext.edutrack.api.feature.onboarding.settings;

/**
 * B-113 · 409 — an escalation or sign-off mail cannot be switched off.
 *
 * <p>The rule is stated over the category rather than per template, so an
 * escalation event declared next month is covered the moment it exists.
 *
 * <p>OB-12 should render the toggle as a locked statement rather than as a
 * control whose only outcome is this refusal — the contract says so, and a
 * disabled control that explains itself is better than one that fails. This
 * exists for the callers that are not that screen.
 */
class MandatoryTemplateException extends RuntimeException {

    MandatoryTemplateException(String eventCode, ObTemplateDtos.Category category) {
        super(eventCode + " is a " + category + " mail and cannot be switched off");
    }
}
