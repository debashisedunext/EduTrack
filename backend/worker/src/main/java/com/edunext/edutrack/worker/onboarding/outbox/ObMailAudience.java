package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;

/**
 * B-111 · who is reading this mail — and therefore how it is written.
 *
 * <p>Several §7 events go to both populations from one trigger: the gate
 * opening notifies "SPOC + owners", go-live notifies the client and the staff
 * who got them there. One wording cannot serve both. A mail to the client
 * saying "the client's gate opened" reads as though it were meant for somebody
 * else, and a mail to a step owner saying "your onboarding has started" is
 * simply false.
 *
 * <p>Derived from the recipient rather than declared per event, because the
 * queue row already knows: {@link ObRecipient.Staff} is a {@code users} row and
 * {@link ObRecipient.Client} is an {@code ob_client_contacts} row, and A-107's
 * CHECK guarantees exactly one. Nothing has to be kept in step.
 *
 * <p>It decides three things: which {@link ObMailTemplate} is chosen, which
 * base URL the button points at (staff app or client portal — see
 * {@link ObMailLinks}), and which footer the layout prints. Internal detail
 * never crosses it: CP-03 hides owner names, internal comments and block
 * reasons from the client, so a client-facing template does not reference them
 * even when the payload carries them.
 */
enum ObMailAudience {

    /** A member of staff — an owner, a verifier, a manager. */
    STAFF,

    /** A client contact. Their view of the module is CP-03's, not OB-05's. */
    CLIENT;

    static ObMailAudience of(ObRecipient recipient) {
        return switch (recipient) {
            case ObRecipient.Staff ignored -> STAFF;
            case ObRecipient.Client ignored -> CLIENT;
        };
    }
}
