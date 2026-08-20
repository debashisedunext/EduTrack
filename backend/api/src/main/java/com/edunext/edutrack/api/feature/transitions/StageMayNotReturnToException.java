package com.edunext.edutrack.api.feature.transitions;

import java.util.List;

/**
 * C-046 · the destination is a real stage of this ticket's template, but not
 * one the current stage is allowed to return to
 * ({@code workflow_stages.can_return_to}).
 *
 * <h2>422, not 400</h2>
 *
 * <p>Nothing the caller sent is malformed: {@code toStageCode} names a stage
 * that exists, and on a different ticket — or from a different current stage —
 * the identical request would succeed. What is wrong is the <em>state</em>, and
 * that is what 422 is for in this codebase ({@code NotCurrentStageOwnerException}
 * and {@code NoOpenStageException} are both refused the same way from the same
 * route). The mock has answered 422 for this case since D-004, and a server that
 * answered 400 would have the client rendering it as a field error under a
 * dropdown whose value is perfectly valid.
 *
 * <p>{@code UnknownTransitionStageException} stays 400 and is the genuinely
 * different case: a stage code that is not on the template at all cannot be
 * right from any state.
 *
 * <h2>The message names the targets</h2>
 *
 * <p>Listing where the stage <em>may</em> return to leaks nothing — the
 * workflow template is readable by anyone who can see the ticket, through
 * {@code GET /masters/workflow-templates} — and it is the difference between a
 * user retrying at random and picking the right stage first time. A stage with
 * no return targets says so explicitly rather than printing an empty list.
 */
class StageMayNotReturnToException extends RuntimeException {

    StageMayNotReturnToException(String fromStageDisplayName, String toStageCode, List<String> allowed) {
        super(fromStageDisplayName + " may not return to " + toStageCode + " — "
                + (allowed == null || allowed.isEmpty()
                        ? "it has no return targets at all"
                        : "the stages it may return to are " + String.join(", ", allowed)));
    }
}
