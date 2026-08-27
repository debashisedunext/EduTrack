package com.edunext.edutrack.api.feature.transitions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * C-046 · the shapes {@code POST /tickets/{ticketId}/rework} takes and answers
 * — {@code ReworkRequest} and {@code RibbonResponse} in the contract,
 * {@code HandoffDtos}' and {@code ForceMoveDtos}' own precedent for a
 * feature-local copy.
 */
final class ReworkDtos {

    private ReworkDtos() {
    }

    /**
     * @param toStageCode optional since the desk's Reopen. It must be one of
     *                    the current stage's {@code can_return_to} targets,
     *                    which is {@link ReworkService}'s own check — see
     *                    there for why {@code advance} does not make it.
     *                    <b>Omitted, the server answers it</b> when the stage
     *                    declares exactly one target, which is what this
     *                    contract already claimed: {@code ForceMoveRequest}
     *                    says "unlike {@code rework}/{@code skip} there is no
     *                    default; the caller always names the destination",
     *                    and that sentence was only true of force-move. Two or
     *                    more targets is a real choice and is still refused
     *                    with {@link ToStageRequiredException}, because "a
     *                    rework with a guessed destination moves a ticket
     *                    somewhere nobody asked for" — the guess is what was
     *                    forbidden, not the workflow's own single answer.
     *                    {@link ReworkService#resolveReturnTarget} records why
     *                    no client could supply this field for itself
     * @param reason      mandatory. §4A.1 requires it on every backward move,
     *                    and {@code TicketJournal.append} enforces it at the
     *                    ledger too — so this constraint is the caller-facing
     *                    half of a rule that is not only ours to keep
     * @param action      which backward action this is: {@code REWORK},
     *                    {@code VERIFY_FAILED}, {@code DEPLOY_FAILED} or
     *                    {@code SIGNOFF_REJECTED}. Defaults to {@code REWORK}
     *                    — the mock has defaulted it since D-004 and the
     *                    create form does not always offer the choice
     * @param defects     "expected on a QA failure" per the contract. Folded
     *                    into the stored reason — {@link ReworkService} says why
     * @param toUserId    who picks the work back up; {@code null} lets
     *                    {@code resolveAssignee} answer from the receiving
     *                    stage's owning role, which is the common case (QA
     *                    fails a ticket without naming which developer)
     * @param effortHours the hours being confirmed for the stage being left.
     *                    <b>Optional here and mandatory on a handoff</b>, and
     *                    that asymmetry is the contract's: {@code
     *                    HandoffRequest} makes it required because §4A.6's
     *                    dialog blocks on it (decision G-1), while §4A.1's
     *                    rework has no such dialog — a QA engineer failing a
     *                    ticket is not confirming anybody's hours but their own
     */
    record ReworkRequest(
            @Size(max = 20) String toStageCode,
            @NotBlank @Size(min = 3, max = 2000) String reason,
            @Size(max = 20) String action,
            List<@Size(max = 500) String> defects,
            Long toUserId,
            BigDecimal effortHours) {
    }

    /** {@code RibbonResponse} — the envelope is {@code { data }}. */
    record RibbonResponse(RibbonWire.Ribbon data) {
    }
}
