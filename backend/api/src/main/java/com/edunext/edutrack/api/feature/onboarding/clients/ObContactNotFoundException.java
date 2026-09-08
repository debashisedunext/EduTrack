package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-103 · no such SPOC <b>under this client</b> — 404.
 *
 * <p>The contact id is always resolved together with the client id it is nested
 * under, so a real contact belonging to somebody else's client answers exactly
 * the same as an invented one. A 404 rather than a 403 for the same reason
 * {@link ObClientNotFoundException} gives: the ids are sequential, and a status
 * that distinguished "not yours" from "not there" would enumerate the SPOC
 * table one integer at a time.
 *
 * <p>Note what this is <em>not</em> used for. A caller who may not write is
 * {@link ObClientReadOnlyException} and 403, because by then the scoped client
 * read has already succeeded and handed them the row.
 */
class ObContactNotFoundException extends RuntimeException {

    ObContactNotFoundException(long obClientId, long contactId) {
        super("no contact " + contactId + " on onboarding client " + obClientId);
    }
}
