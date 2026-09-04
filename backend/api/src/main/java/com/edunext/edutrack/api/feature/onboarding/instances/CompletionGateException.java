package com.edunext.edutrack.api.feature.onboarding.instances;

import java.util.List;

/**
 * 422 — C-106's own server-side completion gate. Plan §5.8: "a service
 * completes only when every [mandatory] sub-category is answered" (the
 * False-needs-a-remark half already lives in
 * {@code ck_ob_journey_step_items_remark} and cannot fail here), plus the
 * architect's addition 7 (required documents attached) and, where the step
 * demands it, an accepted client sign-off (§8).
 *
 * <p>All three are independent failures and are reported together rather
 * than one refusal at a time — a caller fixing the task list should not
 * have to resubmit twice more to discover the document checklist and the
 * sign-off were also outstanding.
 */
class CompletionGateException extends RuntimeException {

    private final List<String> unansweredMandatoryItems;
    private final long missingRequiredDocs;
    private final boolean signoffMissing;

    CompletionGateException(long stepId, List<String> unansweredMandatoryItems,
            long missingRequiredDocs, boolean signoffMissing) {
        super(describe(stepId, unansweredMandatoryItems, missingRequiredDocs, signoffMissing));
        this.unansweredMandatoryItems = unansweredMandatoryItems;
        this.missingRequiredDocs = missingRequiredDocs;
        this.signoffMissing = signoffMissing;
    }

    List<String> unansweredMandatoryItems() {
        return unansweredMandatoryItems;
    }

    long missingRequiredDocs() {
        return missingRequiredDocs;
    }

    boolean signoffMissing() {
        return signoffMissing;
    }

    private static String describe(long stepId, List<String> unanswered, long missingDocs, boolean signoffMissing) {
        StringBuilder reasons = new StringBuilder();
        if (!unanswered.isEmpty()) {
            reasons.append(unanswered.size()).append(" mandatory item(s) unanswered: ").append(unanswered);
        }
        if (missingDocs > 0) {
            if (!reasons.isEmpty()) {
                reasons.append("; ");
            }
            reasons.append(missingDocs).append(" required document(s) not attached");
        }
        if (signoffMissing) {
            if (!reasons.isEmpty()) {
                reasons.append("; ");
            }
            reasons.append("client sign-off not yet accepted");
        }
        return "journey step " + stepId + " cannot complete — " + reasons;
    }
}
