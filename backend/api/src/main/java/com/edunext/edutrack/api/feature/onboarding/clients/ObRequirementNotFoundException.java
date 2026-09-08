package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-106 · no such requirement <b>under this client</b> — 404.
 *
 * <p>The requirement id is always resolved together with the client id it is
 * nested under, so a real requirement belonging to somebody else's client
 * answers exactly the same as an invented one. {@link ObContactNotFoundException}
 * gives the reasoning at length and it is unchanged here: the ids are
 * sequential, and a status that distinguished "not yours" from "not there"
 * would enumerate the table one integer at a time. What that leaks on this
 * table is less commercially sensitive than {@link ObApplicationNotFoundException}'s
 * and more textually revealing — a requirement is prose somebody wrote about
 * another organisation's systems, staffing or migration, and the count alone
 * says how complicated their onboarding is.
 *
 * <p>A caller who may not write is {@link ObClientReadOnlyException} and 403,
 * because by then the scoped client read has already handed them the row.
 */
class ObRequirementNotFoundException extends RuntimeException {

    ObRequirementNotFoundException(long obClientId, long requirementId) {
        super("no requirement " + requirementId + " on onboarding client " + obClientId);
    }
}
