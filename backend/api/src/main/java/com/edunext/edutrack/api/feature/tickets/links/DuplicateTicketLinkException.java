package com.edunext.edutrack.api.feature.tickets.links;

/**
 * C-064 · this exact relationship already exists.
 *
 * <p>409, matching {@code uq_ticket_links (source_ticket_id, target_ticket_id,
 * link_type)}. Raised from {@code TicketLinkRepository
 * .existsBySourceTicketIdAndTargetTicketIdAndLinkType} against the
 * <b>canonical</b> triple — {@code TicketLinkService.canonicalize} has already
 * turned a submitted {@code BLOCKED_BY} into its {@code BLOCKS} equivalent by
 * the time this check runs, so "A blocks B" and "B is blocked by A" collide
 * here rather than producing two rows for one fact.
 */
class DuplicateTicketLinkException extends RuntimeException {

    DuplicateTicketLinkException() {
        super("This relationship already exists.");
    }
}
