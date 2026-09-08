package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-104 · no such purchase <b>under this client</b> — 404.
 *
 * <p>The application id is always resolved together with the client id it is
 * nested under, so a real purchase belonging to somebody else's client answers
 * exactly the same as an invented one. {@link ObContactNotFoundException} gives
 * the reasoning at length and it is unchanged here: the ids are sequential, and
 * a status that distinguished "not yours" from "not there" would enumerate the
 * purchase table one integer at a time — which for this table would also
 * enumerate <em>who bought what</em>, a fact about another organisation's
 * commercial relationship rather than about this caller's own work.
 *
 * <p>A caller who may not write is {@link ObClientReadOnlyException} and 403,
 * because by then the scoped client read has already handed them the row.
 */
class ObApplicationNotFoundException extends RuntimeException {

    ObApplicationNotFoundException(long obClientId, long applicationId) {
        super("no purchase " + applicationId + " on onboarding client " + obClientId);
    }
}
