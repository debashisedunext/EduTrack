package com.edunext.edutrack.api.feature.tickets;

import java.util.regex.Pattern;

/**
 * C-011 · the {@code {PROJECT_CODE}-{YY}-{NNNNN}} format — {@code CRM-26-00347}.
 *
 * <p>Pure and static on purpose. The hard part of ticket-ID generation is
 * allocating the number safely ({@link TicketSequenceRepository}); the easy part
 * is rendering it, and keeping the two apart means the rendering rules can be
 * exercised across a decade of year rollovers and a five-digit overflow in
 * microseconds, with no database in sight.
 *
 * <h2>Two rules the format carries that are easy to lose</h2>
 *
 * <p><b>The sequence does not reset in January.</b> {@code ticket_seq} is a
 * per-project counter, so {@code CRM-26-00347} is followed by
 * {@code CRM-27-00348}, never {@code CRM-27-00001} (PLAN.md §3.2). The year is
 * <i>descriptive</i>: it tells a reader roughly when the ticket was raised and
 * plays no part in uniqueness. Resetting would need a composite counter and
 * would open a collision window at midnight on 1 January, which is a worse
 * trade than a slightly surprising number.
 *
 * <p><b>Five digits is a minimum width, not a ceiling.</b> Project number
 * 100&nbsp;000 renders as {@code CRM-30-100000} rather than wrapping or
 * truncating. {@code tickets.ticket_code} is {@code VARCHAR(30)} against a
 * {@code VARCHAR(10)} project code, which leaves room for sixteen digits — the
 * padding is cosmetic alignment for the list screens, and must never become a
 * modulo.
 *
 * <p>The contract agrees: {@code TicketId} in {@code contracts/openapi.yaml} is
 * {@code ^[A-Z][A-Z0-9]{1,9}-\d{2}-\d{5,}$}. It was written {@code \d{5}} —
 * exactly five — and C-011 widened it, because that schema also types the
 * {@code ticketId} <b>path parameter</b>: an exact five would have let the
 * hundred-thousandth ticket be created and stored correctly and then be
 * unreachable, with the generated Zod rejecting every attachment, comment and
 * history call that addressed it. Narrowing it back is a breaking change.
 * {@code TicketCodeTest} pins both sides of the boundary against the contract's
 * own pattern so the two cannot drift apart again silently.
 */
final class TicketCode {

    /**
     * The contract's own {@code projectCode} pattern, copied verbatim from
     * {@code contracts/openapi.yaml} {@code ProjectCreateRequest}.
     *
     * <p>Asserted here rather than assumed. A project code containing a hyphen
     * or lowercase would still produce a <i>unique</i> ticket code, so nothing
     * would break loudly — it would just quietly make ticket IDs unparseable by
     * eye and by anything that splits on {@code -}, across every ticket that
     * project ever raises. By the time anyone notices, the codes are in email
     * subjects and client spreadsheets and cannot be reissued.
     */
    static final Pattern PROJECT_CODE = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");

    private TicketCode() {
    }

    /**
     * Renders one ticket code.
     *
     * @param projectCode {@code projects.project_code} — must match {@link #PROJECT_CODE}
     * @param year        the four-digit calendar year; only its last two digits are shown
     * @param sequence    the value just allocated by
     *                    {@link TicketSequenceRepository#allocateNextSequence(long)} — strictly positive
     * @throws IllegalArgumentException if any input could produce a malformed or
     *                                  ambiguous code. There is no lenient mode: a
     *                                  bad ticket code is permanent.
     */
    static String format(String projectCode, int year, long sequence) {
        if (projectCode == null || !PROJECT_CODE.matcher(projectCode).matches()) {
            throw new IllegalArgumentException(
                    "project_code must match " + PROJECT_CODE.pattern() + " to prefix a ticket code");
        }
        // Guards the exact shape of a LAST_INSERT_ID() read that landed on the
        // wrong connection: MySQL answers 0 for a session that has issued no
        // insert. Formatting it would mint CRM-26-00000 and, on the next
        // request, mint it again — a duplicate-key failure at best and a
        // silently reused ID at worst. See TicketCodeGenerator's transaction note.
        if (sequence < 1) {
            throw new IllegalArgumentException("ticket sequence must be positive, was " + sequence);
        }
        if (year < 2000 || year > 2999) {
            throw new IllegalArgumentException("year outside the two-digit-safe range: " + year);
        }
        return "%s-%02d-%05d".formatted(projectCode, year % 100, sequence);
    }
}
