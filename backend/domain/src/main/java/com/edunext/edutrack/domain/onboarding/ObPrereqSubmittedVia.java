package com.edunext.edutrack.domain.onboarding;

/**
 * B-125 · {@code ob_client_prereq_tasks.submitted_via} — which path a
 * submission came in by.
 *
 * <p>Kept rather than inferred from which submitter column is set, because
 * the question it answers is about the <em>client's</em> engagement with the
 * portal, and that is what decides whether the portal is working. A SPOC who
 * emails a document their implementor then records is a different fact from
 * staff doing the work themselves.
 */
public enum ObPrereqSubmittedVia {

    /** The client did it themselves. */
    PORTAL,

    /** Staff recorded it on their behalf after it arrived by email. */
    STAFF
}
