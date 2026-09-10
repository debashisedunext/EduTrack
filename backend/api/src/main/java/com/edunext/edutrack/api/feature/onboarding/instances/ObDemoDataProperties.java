package com.edunext.edutrack.api.feature.onboarding.instances;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code edutrack.onboarding.demo-data.*} — the switches that make a
 * freshly instantiated journey walkable end to end without the surfaces the
 * product has not built yet.
 *
 * <h2>What it is standing in for</h2>
 *
 * <p>A step's required documents are counted against the {@code CLEAN}
 * attachments on that step, and <b>no route anywhere creates a STEP-owned
 * attachment</b>: {@code ObAttachmentOwner.STEP} exists, the pipeline can store
 * one, and the only two callers pass {@code CLIENT} and {@code PREREQ_TASK}.
 * So a service whose template declares a required document cannot be completed
 * by anybody, through any screen, in any environment. That is a gap in the
 * product rather than a property of it, and this switch is scaffolding held
 * against the gap until the upload surface is written.
 *
 * <h2>Off by default, guarded by profile</h2>
 *
 * <p>{@code ObDemoDataConfig} refuses to start with this on outside a
 * development profile, on {@code PortalDevCredentialConfig}'s pattern and for
 * its reason. The failure mode here is quieter than a leaked password and
 * worth naming: a production journey whose documents arrive already attached
 * is a compliance record asserting that a client supplied paperwork nobody
 * ever asked them for.
 *
 * @param preSatisfyStepDocuments whether instantiating a journey should attach
 *                                a placeholder against each required document
 *                                on each of its steps. False unless a
 *                                deployment says otherwise.
 */
@ConfigurationProperties(prefix = "edutrack.onboarding.demo-data")
record ObDemoDataProperties(
        Boolean preSatisfyStepDocuments
) {
    ObDemoDataProperties {
        if (preSatisfyStepDocuments == null) preSatisfyStepDocuments = Boolean.FALSE;
    }

    /**
     * Boxed component, unboxed once, here — so the branch that decides whether
     * to fabricate a compliance record fails closed rather than throwing.
     */
    boolean seedsStepDocuments() {
        return Boolean.TRUE.equals(preSatisfyStepDocuments);
    }
}
