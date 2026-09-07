package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-102 · this caller can see the client and may not edit it — 403.
 *
 * <h2>403 here and 404 on the create, which is not an inconsistency</h2>
 *
 * <p>CONVENTIONS.md §3 reserves 403 for "a permitted-by-role failure that does
 * <b>not depend on a row existing</b>", and §7 answers 404 wherever the refusal
 * would otherwise confirm a row. Both are satisfied: the caller reaching here
 * has already been handed this client by {@code GET
 * /onboarding/clients/{obClientId}}, so a status that admits the client exists
 * discloses nothing they were not already shown.
 *
 * <p>The alternative — 404 on the edit as well — would tell a Viewer that a
 * client they are looking at does not exist, which is the kind of refusal that
 * gets reported as a bug and then worked around.
 *
 * <p>Plan §3: OB Viewer is "everything, read-only" and OB Step Owner may update
 * only their own steps. Neither owns the client record.
 */
class ObClientReadOnlyException extends RuntimeException {

    ObClientReadOnlyException(String clientName) {
        super("Your onboarding role can read " + clientName + " but not change it. "
                + "Editing the client record is an OB Admin, Onboarding Manager or Sales action.");
    }
}
