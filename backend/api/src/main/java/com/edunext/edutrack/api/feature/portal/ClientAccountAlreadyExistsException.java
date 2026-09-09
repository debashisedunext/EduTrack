package com.edunext.edutrack.api.feature.portal;

/**
 * B-126 · 409 — this client already has a portal login.
 *
 * <p>Refused rather than quietly turned into a reset.
 * {@code uq_client_accounts_ob_client} allows one account per client precisely
 * so that there are never two credentials to revoke when somebody leaves — and
 * an operator who pressed "create" is not asking for a new link to be mailed to
 * a login that already exists. The screen offers reset separately, which is the
 * call they would then make knowingly.
 */
class ClientAccountAlreadyExistsException extends RuntimeException {

    ClientAccountAlreadyExistsException(long obClientId) {
        super("onboarding client " + obClientId + " already has a portal login");
    }
}
