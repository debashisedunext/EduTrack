package com.edunext.edutrack.api.feature.tickets.links;

/**
 * C-064 · the contract's {@code TicketLinkType}, per {@code openapi.yaml}.
 *
 * <p>Four submittable values, named from blueprint §16 item 17 and §7.5's
 * create-form row: {@link #BLOCKS}, {@link #BLOCKED_BY}, {@link #DUPLICATE_OF},
 * {@link #RELATES_TO}. {@link #DUPLICATED_BY} is a fifth, non-submittable value
 * that only ever appears as a computed label — see {@link #inverse()} and
 * {@code TicketLinkService.canonicalize}.
 */
enum TicketLinkType {

    BLOCKS,
    BLOCKED_BY,
    DUPLICATE_OF,
    RELATES_TO,

    /**
     * Never stored and never accepted by {@code createTicketLink}
     * ({@code TicketLinkService} refuses it with 400). Exists only so a
     * {@code DUPLICATE_OF} row can be labelled correctly from the original
     * ticket's side of the relationship — the same reason {@link #BLOCKED_BY}
     * exists to label {@link #BLOCKS} from the blocked side, except that
     * direction already had two real, independently pickable names in
     * blueprint §7.5 and this one does not.
     */
    DUPLICATED_BY;

    /**
     * How this type reads from the <em>other</em> ticket.
     *
     * <p>{@code RELATES_TO} is symmetric. {@code BLOCKS}/{@code BLOCKED_BY} is
     * a genuine pair — either name is a legitimate thing for a caller to pick,
     * and {@code TicketLinkService.canonicalize} is what stops the pair from
     * producing two rows for one relationship. {@code DUPLICATE_OF}'s inverse,
     * {@code DUPLICATED_BY}, is not a pickable name at all: nothing in §7.5
     * offers "duplicated by" as a create-form option, only "duplicate of".
     */
    TicketLinkType inverse() {
        return switch (this) {
            case BLOCKS -> BLOCKED_BY;
            case BLOCKED_BY -> BLOCKS;
            case DUPLICATE_OF -> DUPLICATED_BY;
            case DUPLICATED_BY -> DUPLICATE_OF;
            case RELATES_TO -> RELATES_TO;
        };
    }
}
