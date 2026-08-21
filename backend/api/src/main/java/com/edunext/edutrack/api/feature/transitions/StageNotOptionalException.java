package com.edunext.edutrack.api.feature.transitions;

/**
 * C-047 · the stage the ticket is standing in is not marked
 * {@code workflow_stages.is_optional}, so it may not be skipped.
 *
 * <h2>422, not 400</h2>
 *
 * <p>{@link StageMayNotReturnToException}'s reasoning, one route over, and it
 * applies more strongly here: the request carries no stage code at all in the
 * common case — the caller sent a reason and nothing else — so there is no
 * field a 400 could be keyed onto. What is wrong is <em>where the ticket
 * is</em>, and the identical request would succeed once it reaches a stage the
 * template marks optional.
 *
 * <h2>Why the rule exists at all</h2>
 *
 * <p>Blueprint §4A.6: "Skipping an <b>optional</b> stage requires PM/Admin and
 * a reason." {@code is_optional} is §7's own column for saying which stages
 * those are — its DDL comment reads "can be skipped with reason" — and B-041's
 * designer is where a workflow author sets it. Without this check the column
 * describes nothing and a PM could skip DEV on a ticket nobody has written a
 * line of code for, in a ledger that cannot be corrected afterwards.
 *
 * <p>The message names the stage rather than the flag, because the caller
 * cannot see column names and the stage's display name is what the ribbon
 * segment they clicked is labelled with.
 */
class StageNotOptionalException extends RuntimeException {

    StageNotOptionalException(String stageDisplayName, String stageCode) {
        super(stageDisplayName + " (" + stageCode + ") is not an optional stage on this ticket's workflow, "
                + "so it may not be skipped — only stages the template marks optional can be.");
    }
}
