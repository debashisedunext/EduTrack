package com.edunext.edutrack.api.feature.onboarding.signoff;

/**
 * 422 — {@code sentToContactId} does not name an active contact on the client
 * this journey belongs to.
 *
 * <p>Checked here rather than left to the foreign key, and the check is on the
 * <em>client</em> as well as on the id: {@code fk_ob_signoffs_contact} would
 * happily accept a contact belonging to somebody else's client, which is
 * {@code ObSignoffAcceptService}'s own observation about why it refuses to take
 * {@code signed_by_contact_id} from a caller. A sign-off mailed to another
 * client's SPOC is a cross-client disclosure that the schema cannot catch.
 *
 * <p>Inactive is refused for the plainer reason: the row is the address the mail
 * goes to, and a retired contact is an inbox nobody reads.
 */
class ObSignoffContactInvalidException extends RuntimeException {

    ObSignoffContactInvalidException(long contactId, long obClientId) {
        super("Contact " + contactId + " is not an active contact on client " + obClientId
                + ". Pick a contact from this client's own active SPOCs.");
    }
}
