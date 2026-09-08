package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-102 · no such client, <b>or one outside the caller's A-112 scope</b>. Both
 * answer 404 and this exception cannot tell them apart, because
 * {@code ObClientReadRepository} applies the scope predicate inside the
 * statement and returns nothing either way.
 *
 * <p>That is the whole point: a 403 on a client id confirms the client exists,
 * which is the existence leak CONVENTIONS.md §7 and CLAUDE.md both refuse.
 */
class ObClientNotFoundException extends RuntimeException {

    ObClientNotFoundException(long obClientId) {
        super("no onboarding client " + obClientId);
    }
}
