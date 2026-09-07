package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-103 · two SPOCs of one client may not share an email — 409
 * {@code ob-contact-email-duplicate}.
 *
 * <p>{@code uq_ob_client_contacts_email} says the same thing and would say it
 * as a constraint name; this is the readable half. The check is what produces
 * the message, the index is what is true under a race — the split
 * {@code ObClientWriteService} already documents for the PAN guard.
 *
 * <p><b>Scoped to the client, not global.</b> The same address under two
 * different clients is legitimate and stays allowed: one consultant is
 * frequently the SPOC at both, and the module addresses them per client. That
 * is also what the index is on.
 *
 * <p><b>Matched case-insensitively</b>, agreeing with
 * {@code utf8mb4_0900_ai_ci}, so the check refuses what the database would have
 * refused rather than a narrower set — a service check stricter than its index
 * is confusing, and one looser is decorative.
 *
 * <p>An <em>inactive</em> contact still holds its address. The index does not
 * exclude them, and re-adding a departed SPOC as a second row would split their
 * history across two ids; the panel reactivates the row they already have.
 */
class DuplicateContactEmailException extends RuntimeException {

    DuplicateContactEmailException(String email, boolean holderIsInactive) {
        super(holderIsInactive
                ? email + " belongs to a contact on this client who has been removed. Reactivate "
                        + "them rather than adding a second row — a new id would split what they "
                        + "have already signed off across two people."
                : email + " is already a contact on this client. One person, one row.");
    }
}
