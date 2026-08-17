package com.edunext.edutrack.api.feature.tickets;

/**
 * C-020 · a level change on an assigned ticket that carried no reason —
 * blueprint §4B.1, "mandatory free-text reason when the ticket is already
 * assigned. Optional at creation".
 *
 * <p><b>400 rather than 422</b>, and the distinction is the one
 * {@code ReopenExceptionHandler} draws for its own pair. A 422 says "nothing
 * you sent is wrong, the row is in the wrong state" — which is why the reopen
 * of an open ticket is one, and why rewording the reason cannot help there. Here
 * the opposite is true: the request is missing a field it needed, the caller can
 * supply it, and resending works. That is a 400 keyed onto {@code reason}, so
 * S-20's dialog marks the textarea the user has to fill in.
 *
 * <p><b>Why the rule is the assignee and not the role.</b> §4B.1 could have made
 * the reason unconditional and nobody would have complained. It does not,
 * because the two cases are genuinely different: a ticket still in triage is
 * having its level <em>set</em>, and "why did you pick High" has no answer
 * beyond the triage itself. Once it is assigned, somebody is planning their week
 * around the date it carries, and moving that date without saying why is the
 * change that generates the "who did this and why" question a fortnight later —
 * which is exactly what the {@code LEVEL_CHANGED} row exists to answer, and it
 * cannot answer it with a null remark.
 *
 * <p>The reason is echoed as prose and carries nothing from the row: the ticket
 * id is already the caller's, and no level, assignee or date appears in the
 * message.
 */
class LevelReasonRequiredException extends RuntimeException {

    LevelReasonRequiredException() {
        super("This ticket is assigned, so a reason for the level change is required.");
    }
}
