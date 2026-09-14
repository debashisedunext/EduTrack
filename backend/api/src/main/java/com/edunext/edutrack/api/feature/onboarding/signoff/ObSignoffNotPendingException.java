package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;

/**
 * 422 — the sign-off is this client's, and it is no longer open to a decision.
 *
 * <p>Accepted or objected in another tab, or withdrawn by staff. Named rather
 * than folded into {@link ObSignoffNotForClientException}'s 404 because the
 * caller has already proved they own the row, so there is nothing left to
 * enumerate and "this is no longer open" is the answer they can actually act
 * on — refresh and read the decision, rather than go looking for a sign-off
 * the 404 would imply had never existed.
 *
 * <p>The status is carried so the problem document can say which way it went.
 * That is not a disclosure: it is the client's own row, and
 * {@code listPortalSignoffs} already serves the same field.
 */
public class ObSignoffNotPendingException extends RuntimeException {

    private final ObSignoffStatus status;

    public ObSignoffNotPendingException(ObSignoffStatus status) {
        super("This sign-off is no longer open — it is " + status + ".");
        this.status = status;
    }

    public ObSignoffStatus status() {
        return status;
    }
}
