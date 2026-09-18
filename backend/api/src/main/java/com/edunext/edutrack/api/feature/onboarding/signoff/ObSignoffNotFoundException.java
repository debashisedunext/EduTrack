package com.edunext.edutrack.api.feature.onboarding.signoff;

/**
 * 404 — the sign-off or journey addressed does not exist, or belongs to a client
 * outside the caller's A-112 scope.
 *
 * <p>The two cases are deliberately indistinguishable, on
 * {@code ObSignoffCertificateNotFoundException}'s own reasoning one class over:
 * a 403 on the second would confirm the row exists, which is the existence leak
 * CLAUDE.md refuses. {@link ObSignoffAdminRepository} produces the same empty
 * Optional for both, so this class could not tell them apart even if it wanted
 * to.
 *
 * <p>Two factories rather than two exception types, because the caller's
 * <em>answer</em> is identical and only the sentence differs. A second class
 * would be a second thing for the handler to map onto the same problem body.
 */
class ObSignoffNotFoundException extends RuntimeException {

    private ObSignoffNotFoundException(String message) {
        super(message);
    }

    static ObSignoffNotFoundException signoff(long signoffId) {
        return new ObSignoffNotFoundException("no sign-off " + signoffId);
    }

    static ObSignoffNotFoundException journey(long journeyId) {
        return new ObSignoffNotFoundException("no journey " + journeyId);
    }

    static ObSignoffNotFoundException step(long stepId) {
        return new ObSignoffNotFoundException("no step " + stepId);
    }
}
