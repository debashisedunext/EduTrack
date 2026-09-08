package com.edunext.edutrack.domain.onboarding;

/**
 * B-125 · {@code ob_client_prereq_tasks.status} — plan §4's four values, and
 * the two absences matter as much as the values.
 *
 * <p><b>There is no {@code RETURNED}.</b> A returned submission is
 * {@link #PENDING} again, because that is what the client has to act on. A
 * fifth value would split "the client owes us this" across two states that
 * every count, every reminder and every progress bar would then have to
 * remember to add together. What was returned, by whom and why is in the
 * task's history and its comment thread — the status says whose move it is.
 *
 * <p><b>There is no {@code EXPIRED} either.</b> A task past its {@code dueAt}
 * is overdue rather than closed: plan §5.4 scans it as client-attributed time
 * and sends reminders, and a task that timed itself out would clear nothing
 * while making the gate look permanently unopenable.
 */
public enum ObPrereqTaskStatus {

    /** The client owes us this. Where a returned submission lands. */
    PENDING,

    /** Sent for verification. Only staff move it on from here. */
    SUBMITTED,

    /** Accepted. Counts towards the gate. */
    VERIFIED,

    /**
     * Waived with a logged reason — plan §5.3's only valve, and
     * <b>non-mandatory tasks only</b>. Enforced by the service with a 422 and
     * by {@code ck_ob_client_prereq_tasks_mandatory_not_skipped} at the
     * column, because a skippable mandatory task is not a mandatory task.
     */
    SKIPPED;

    /** Verified or skipped — the two states the gate counts as settled. */
    public boolean isSettled() {
        return this == VERIFIED || this == SKIPPED;
    }
}
