package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-102 · {@code ob-client-pan-duplicate} — 409, and <b>final</b>.
 *
 * <p>Unlike a similar name, this cannot be forced. Two rows for one legal
 * entity is the state plan §1.1 item 6 exists to prevent, and there is no
 * legitimate case for a second: a PAN identifies the company, not the
 * engagement. A client who buys a second product gets a second purchase and a
 * second journey on the row that already exists.
 *
 * @param existingName the client already holding this PAN, or null where the
 *                     caller's own scope cannot see it. Naming it ends the
 *                     question; naming one the caller may not see would use
 *                     the duplicate guard to enumerate other people's clients.
 */
class DuplicateClientPanException extends RuntimeException {

    private final String existingName;

    DuplicateClientPanException(String existingName) {
        super(existingName == null
                ? "this PAN is already recorded against another onboarding client"
                : "this PAN is already recorded against " + existingName);
        this.existingName = existingName;
    }

    String existingName() {
        return existingName;
    }
}
