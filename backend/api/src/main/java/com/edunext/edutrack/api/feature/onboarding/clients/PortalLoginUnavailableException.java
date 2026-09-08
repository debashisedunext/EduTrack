package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-102 · {@code createPortalLogin: true} arrived and there is nothing behind
 * it yet.
 *
 * <h2>Refusing loudly, rather than boarding the client and sending nothing</h2>
 *
 * <p>The contract's create says the flag "creates a {@code client_accounts} row
 * with a generated username and a one-time password emailed to the primary
 * SPOC". <b>B-126 owns that</b> — the account panel, the credential mail and
 * its audit — and A-125's table is the only part of it that exists today.
 *
 * <p>The two honest options were to ignore the flag or to refuse the request,
 * and ignoring it is the dangerous one. Ignoring means a boarder ticks "create
 * the login now", sees a 201, tells the client their credentials are on the
 * way, and nothing was ever sent — a failure discovered by the client, days
 * later, and indistinguishable on our side from a mail that bounced. Refusing
 * costs one retry with the box unticked and cannot be misread.
 *
 * <p><b>This class is deleted by B-126</b>, along with its handler and the
 * branch that throws it. It is named here so that removal is a search rather
 * than an archaeology.
 */
class PortalLoginUnavailableException extends RuntimeException {

    PortalLoginUnavailableException() {
        super("A client portal login cannot be created yet — that lands with the OB-05 account "
                + "panel (B-126). Board the client with the box unticked and create the login "
                + "from the client page once it exists; no credential is issued either way.");
    }
}
