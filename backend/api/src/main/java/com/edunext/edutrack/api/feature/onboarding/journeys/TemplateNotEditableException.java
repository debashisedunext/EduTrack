package com.edunext.edutrack.api.feature.onboarding.journeys;

/**
 * C-101's core guarantee: <b>a template that has ever been published cannot
 * be mutated again</b> — not while it is the active version, and not after a
 * later version has superseded it. {@code ObJourneyTemplate}'s own javadoc
 * spells out why the test is {@code publishedAt == null}, never
 * {@code !isActive}: a retired version can still be the one a running
 * journey pinned at instantiation, and it must stay exactly as it was
 * published for as long as that journey exists.
 *
 * <p>Only a draft — a version that has never been published — accepts
 * {@code addStep}/{@code removeStep}/{@code addStepItem}/etc. Editing a
 * published product means {@link ObJourneyTemplateService#beginRevision}
 * first, which clones a fresh, editable draft.
 */
class TemplateNotEditableException extends RuntimeException {

    TemplateNotEditableException(long templateId) {
        super("journey template " + templateId + " has already been published and can no longer "
                + "be edited in place; call beginRevision to open a new draft version");
    }
}
