package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-109 · {@code ob-client-no-prereq-master} — 409. Nothing is published on
 * OB-14 to snapshot a checklist from.
 *
 * <h2>Checked before the create rather than caught out of it</h2>
 *
 * <p>{@code ObClientPrereqService.instantiate} raises its own {@code
 * NoActivePrereqMasterException} for this, and that exception is
 * package-private to B-125's package — deliberately, and not something to
 * widen from here. {@code ProductWithoutTemplateException} makes the identical
 * call for journey templates one guard earlier in the same {@code create}:
 * checking first, with {@link
 * com.edunext.edutrack.api.feature.onboarding.prereqs.ObClientPrereqService#hasActivePrereqMaster()}
 * as the read, keeps the failure ahead of any write rather than unwound out of
 * one.
 *
 * <p>A master unpublished between this check and the snapshot is a race the
 * transaction settles by rolling the whole create back — the same thing
 * {@code ProductWithoutTemplateException} says about a template deactivated in
 * the same window.
 */
class NoPublishedPrerequisitesException extends RuntimeException {

    NoPublishedPrerequisitesException() {
        super("no version of the prerequisites master is active; publish one in OB-14 "
                + "before boarding a client");
    }
}
