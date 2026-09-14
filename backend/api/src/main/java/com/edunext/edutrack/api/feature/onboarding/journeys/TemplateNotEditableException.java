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
 * {@code addStep}/{@code removeStep}/{@code removeStepItem}/etc. Editing a
 * published product means {@link ObJourneyTemplateService#beginRevision}
 * first, which clones a fresh, editable draft.
 *
 * <p><b>B-131 · adding a Task List entry is the one exception, and it is
 * narrow on purpose.</b> {@link ObJourneyTemplateService#addStepItem} is
 * guarded by {@code requireOfferable} instead, which admits the active
 * version as well as a draft — adding a checklist item takes nothing away
 * from a journey mid-flight and is the edit an admin actually needs on a
 * service clients are already on. That method's javadoc has the full
 * argument. Everything else on a published version still arrives here, and
 * a <em>retired</em> version refuses even the addition, through
 * {@link #retired}.
 */
class TemplateNotEditableException extends RuntimeException {

    TemplateNotEditableException(long templateId) {
        super("journey template " + templateId + " has already been published and can no longer "
                + "be edited in place; call beginRevision to open a new draft version");
    }

    private TemplateNotEditableException(String message) {
        super(message);
    }

    /**
     * B-131 · the refusal a <em>retired</em> version gets.
     *
     * <p>Separate wording because the standard message's advice is wrong
     * here: {@code beginRevision} refuses anything that is not the active
     * version ({@code TemplateNotActiveException}), so telling the caller to
     * open a draft from a superseded version sends them to a second error.
     * What they can actually do is edit the version the service currently
     * offers.
     */
    static TemplateNotEditableException retired(long templateId) {
        return new TemplateNotEditableException("journey template " + templateId
                + " is a retired version and is read-only for good — it is what the clients boarded "
                + "on it are still running. Add the item to the version this service currently offers.");
    }
}
